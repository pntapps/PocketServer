package com.pntapps.pocketserver.server

import android.net.NetworkCapabilities
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import com.pntapps.pocketserver.catalog.Catalog
import com.pntapps.pocketserver.catalog.CatalogApp
import com.pntapps.pocketserver.linux.LinuxPaths
import com.pntapps.pocketserver.linux.Proot
import com.pntapps.pocketserver.linux.RootfsInstaller
import com.pntapps.pocketserver.linux.SharedStorage
import com.pntapps.pocketserver.terminal.TerminalSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit

sealed interface SystemState {
    data object NotInstalled : SystemState
    data class Installing(val progress: RootfsInstaller.Progress?) : SystemState
    data object Ready : SystemState
    data class Failed(val message: String) : SystemState
}

sealed interface AppPhase {
    data object NotInstalled : AppPhase
    data class Installing(val step: String) : AppPhase
    data object Stopped : AppPhase
    data object Starting : AppPhase
    data class Running(val since: Long) : AppPhase
    data object Stopping : AppPhase
    data class Failed(val message: String) : AppPhase
}

data class AppState(
    val phase: AppPhase,
    /** Install finished successfully at some point (a later start failure does not change this). */
    val installed: Boolean,
    /** Wi-Fi access switched on (only for apps with [CatalogApp.lanToggle]). */
    val lan: Boolean = false,
    val credentials: List<Pair<String, String>> = emptyList(),
) {
    val active get() = phase is AppPhase.Starting || phase is AppPhase.Running
}

/**
 * Owns the Ubuntu system and every app process. Nothing here runs on its own: processes exist only
 * between a user's Start and Stop, and [ServerService] keeps the device awake only while one does.
 */
class AppManager private constructor(private val context: Context) {
    val paths = LinuxPaths(context)
    private val proot = Proot(paths)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processes = mutableMapOf<String, Process>()
    private val installs = mutableMapOf<String, Process>()
    private val installLock = Mutex()

    /** Long jobs (installs, Ubuntu setup, backups) in progress; see [keepAlive]. */
    private val work = java.util.concurrent.atomic.AtomicInteger()
    val busy get() = work.get() > 0

    /**
     * Runs a long job under the foreground service (wake lock, no Wi-Fi lock). Without it Android
     * freezes a backgrounded app's process tree and can kill CPU-heavy children such as dpkg or npm.
     */
    suspend fun <T> keepAlive(block: suspend () -> T): T {
        work.incrementAndGet()
        ServerService.sync(context, runningCount)
        try {
            return block()
        } finally {
            work.decrementAndGet()
            ServerService.sync(context, runningCount)
        }
    }

    /** Stop requested while the process was still being spawned; startProcess honours it. */
    private val stopRequested = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Set while a backup or restore runs: starts, installs and charger resumes are refused, since a
     * restore replaces app folders and a backup reads them (a running database would be corrupted).
     */
    @Volatile var maintenance: String? = null

    /** Stopped by the starter because it never became ready; reported as Failed once it has exited. */
    private val timedOut = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val _system = MutableStateFlow<SystemState>(
        if (paths.isInstalled) SystemState.Ready else SystemState.NotInstalled
    )
    val system: StateFlow<SystemState> = _system.asStateFlow()

    private val _apps = MutableStateFlow(Catalog.apps.associate { it.id to loadState(it) })
    val apps: StateFlow<Map<String, AppState>> = _apps.asStateFlow()

    val runningCount get() = _apps.value.values.count { it.active }

    /** Battery rules (on-demand timer, idle stop, charger pause...). Enforced only while [ServerService] runs. */
    val power = PowerPolicy(context, this)

    /** One-line power status for the dashboard, e.g. "Auto-stop in 42 min" (null: nothing to say). */
    val powerStatus: StateFlow<String?> get() = power.status

    @Volatile private var heatLimitC = PowerSettings().heatLimitC

    /** Overheating protection for servers, installs and terminal sessions (event-driven, process-wide). */
    val thermal = ThermalGuard(context) { heatLimitC }

    init {
        scope.launch { power.store.settings.collect { heatLimitC = it.heatLimitC; thermal.evaluate() } }
        // evaluate() also runs on IO threads; the power policy's state is main-thread only.
        thermal.onChange { level -> context.mainExecutor.execute { onHeat(level) } }
        thermal.start()
    }

    private fun onHeat(level: Heat) {
        if (level < Heat.Hot) return
        val r = thermal.reading()
        val busy = runningCount > 0 || synchronized(installs) { installs.isNotEmpty() }
        if (busy) {
            synchronized(installs) { installs.values.forEach { it.destroyForcibly() } }
            power.stopEverything(
                "Stopped: too hot (${r.batteryC.toInt()} °C)", "Servers stopped: device too hot",
                "Battery ${r.batteryC.toInt()} °C. Everything was stopped to protect the device; starting is " +
                    "blocked until it cools below ${heatLimitC - ThermalRules.COOL_MARGIN_C} °C.",
                alert = true,
            )
        }
        // At critical temperature even interactive shells go: a runaway compile must not cook the device.
        if (level >= Heat.Critical) context.mainExecutor.execute { TerminalSessions.closeAll() }
    }

    /**
     * Wraps a guest command so it runs on the efficiency cores at low priority. Serving files and
     * direct-play media don't need the big cores, which produce most of the heat and battery drain.
     */
    private fun cool(argv: List<String>): List<String> {
        val cores = ThermalGuard.efficiencyCores
        val niced = listOf("/usr/bin/nice", "-n", "10") + argv
        return if (cores != null) listOf("/usr/bin/taskset", "-c", cores) + niced else niced
    }

    private fun appDir(id: String) = File(paths.rootfs, "opt/pocketserver/apps/$id")
    private fun installedMarker(id: String) = File(appDir(id), ".installed")
    private fun lanFlag(id: String) = File(appDir(id), "lan-access")
    fun logFile(id: String) = File(paths.logs, "$id.log")

    private fun loadState(app: CatalogApp): AppState {
        val installed = installedMarker(app.id).exists()
        return AppState(if (installed) AppPhase.Stopped else AppPhase.NotInstalled, installed, lanFlag(app.id).exists(), readCredentials(app.id))
    }

    private fun readCredentials(id: String): List<Pair<String, String>> =
        File(appDir(id), "credentials").takeIf { it.exists() }?.readLines()
            ?.filter { '=' in it }?.map { it.substringBefore('=') to it.substringAfter('=') }
            .orEmpty()

    /** Re-reads installed state and credentials of stopped apps from disk (after a restore). */
    fun reload() {
        _apps.update { current ->
            current.mapValues { (id, state) -> if (state.active) state else loadState(Catalog.byId(id)) }
        }
    }

    private fun setPhase(id: String, phase: AppPhase) {
        val state = AppState(phase, installedMarker(id).exists(), lanFlag(id).exists(), readCredentials(id))
        _apps.update { it + (id to state) }
        ServerService.sync(context, runningCount)
    }

    fun installSystem() {
        if (_system.value is SystemState.Installing) return
        _system.value = SystemState.Installing(null)
        scope.launch {
            _system.value = try {
                keepAlive { RootfsInstaller(paths).install { _system.value = SystemState.Installing(it) } }
                SystemState.Ready
            } catch (t: Throwable) {
                SystemState.Failed(t.message ?: t.toString())
            }
        }
    }

    /** Copies the recipes bundled in the APK into Ubuntu (they are refreshed on every app update). */
    private fun syncRecipes(id: String) {
        val base = File(paths.rootfs, "opt/pocketserver")
        val assets = context.assets
        fun copy(asset: String, target: File) {
            target.parentFile?.mkdirs()
            // Strip CR so scripts edited on Windows still run under bash.
            val text = assets.open(asset).use { it.readBytes().decodeToString() }.replace("\r", "")
            target.writeText(text)
            Os.chmod(target.path, 0x1ED)
        }
        copy("catalog/lib.sh", File(base, "lib.sh"))
        assets.list("catalog/$id")?.forEach { copy("catalog/$id/$it", File(base, "recipes/$id/$it")) }
    }

    fun install(id: String) {
        val current = _apps.value.getValue(id)
        if (current.installed || (current.phase !is AppPhase.NotInstalled && current.phase !is AppPhase.Failed)) return
        (maintenance ?: paths.storage.problem() ?: thermal.blockReason())?.let { setPhase(id, AppPhase.Failed(it)); return }
        setPhase(id, AppPhase.Installing("Queued"))
        // One keep-alive over the whole chain: no gap between requirement and app in which a
        // backgrounded app would lose its foreground service.
        scope.launch { keepAlive {
            // Requirements first (e.g. the Dev Toolchain under every Web Dev Pack service).
            for (req in Catalog.byId(id).requires) {
                if (_apps.value.getValue(req).installed) continue
                setPhase(id, AppPhase.Installing("Waiting for ${Catalog.byId(req).name}"))
                if (_apps.value.getValue(req).phase !is AppPhase.Installing) setPhase(req, AppPhase.Installing("Queued"))
                if (!runInstall(req)) {
                    setPhase(id, AppPhase.Failed("${Catalog.byId(req).name} could not be installed. See its log."))
                    return@keepAlive
                }
            }
            runInstall(id)
        } }
    }

    /**
     * Switches network access for a database. start.sh reads the flag, so a running app is
     * restarted to apply it. Logins are always required either way.
     */
    fun setLanAccess(id: String, on: Boolean) {
        val flag = lanFlag(id)
        if (on) flag.writeText("1") else flag.delete()
        setPhase(id, _apps.value.getValue(id).phase)
        restartIfRunning(id)
    }

    /** Small status files a recipe writes for its card (e.g. Vaultwarden's first-account). */
    fun appFile(id: String, name: String): String? =
        runCatching { File(appDir(id), name).takeIf { it.exists() }?.readText()?.trim() }.getOrNull()

    /**
     * The only email that may create Vaultwarden's first account (start.sh invites it; sign-ups
     * themselves never open). A running vault restarts to send the invite.
     */
    fun setVaultOwner(email: String) {
        File(appDir(VAULT), "owner-email").writeText(email.trim() + "\n")
        restartIfRunning(VAULT)
    }

    private fun restartIfRunning(id: String) {
        val phase = _apps.value.getValue(id).phase
        if (phase !is AppPhase.Running && phase !is AppPhase.Starting) return
        stop(id)
        scope.launch {
            withTimeoutOrNull(30_000) {
                apps.first { it.getValue(id).phase.let { p -> p is AppPhase.Stopped || p is AppPhase.Failed } }
            }
            start(id)
        }
    }

    /**
     * Moves a /shared media folder to the SD card or back (see [SharedStorage]). Only with every
     * app stopped and nothing installing: servers must never see their files disappear mid-way.
     */
    fun moveSharedFolder(folder: String, toSd: Boolean) {
        val storage = paths.storage
        if (storage.move.value is SharedStorage.Move.Working) return
        if (runningCount > 0 || busy || maintenance != null) {
            storage.fail("Stop all apps (and wait for installs or backups to finish) before moving folders.")
            return
        }
        maintenance = "Moving $folder ${if (toSd) "to the SD card" else "to this device"}. Try again when it has finished."
        scope.launch {
            try {
                keepAlive { storage.moveFolder(folder, toSd) { thermal.blockReason() } }
            } finally {
                maintenance = null
            }
        }
    }

    /** Installs every app of a group (e.g. the whole Web Dev Pack); they run one after another. */
    fun installGroup(group: String) = Catalog.apps.filter { it.group == group }.forEach { install(it.id) }

    /**
     * Runs one recipe's install.sh. Installs are serialized: apt holds a system-wide lock, and two
     * heavy installs at once would only double the heat. Returns true on success.
     */
    private suspend fun runInstall(id: String): Boolean = installLock.withLock { keepAlive { runInstallLocked(id) } }

    private fun runInstallLocked(id: String): Boolean {
        // A queued duplicate (two services needing the same toolchain) finds it already done.
        if (installedMarker(id).exists()) { setPhase(id, AppPhase.Stopped); return true }
        thermal.blockReason()?.let { setPhase(id, AppPhase.Failed(it)); return false }
        setPhase(id, AppPhase.Installing("Preparing"))
        return run {
            val log = logFile(id).apply { parentFile?.mkdirs(); writeText("") }
            val code = try {
                syncRecipes(id)
                val process = proot.processBuilder(
                    cool(listOf("/bin/bash", "/opt/pocketserver/recipes/$id/install.sh")),
                    extraEnv = mapOf("APP_ID" to id),
                ).start()
                synchronized(installs) { installs[id] = process }
                process.outputStream.close()
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.startsWith("::step:: ")) setPhase(id, AppPhase.Installing(line.removePrefix("::step:: ")))
                        log.appendText(line + "\n")
                    }
                }
                process.waitFor()
            } catch (t: Throwable) {
                log.appendText("${t.message}\n"); -1
            } finally {
                synchronized(installs) { installs.remove(id) }
            }
            if (code == 0) {
                installedMarker(id).writeText(System.currentTimeMillis().toString())
                setPhase(id, AppPhase.Stopped)
            } else {
                val last = log.readLines().lastOrNull { it.isNotBlank() } ?: "exit code $code"
                setPhase(id, AppPhase.Failed("Install failed: $last"))
            }
            code == 0
        }
    }

    fun start(id: String) {
        power.onUserStart(id)
        startProcess(id)
    }

    /** Start requested by the power policy (charger reconnected), not by the user. */
    internal fun resume(id: String) = startProcess(id)

    private fun startProcess(id: String) {
        val app = Catalog.byId(id)
        if (!app.service) return
        val phase = _apps.value.getValue(id).phase
        if (phase !is AppPhase.Stopped && phase !is AppPhase.Failed) return
        (maintenance ?: paths.storage.problem() ?: thermal.blockReason())?.let { setPhase(id, AppPhase.Failed(it)); return }
        setPhase(id, AppPhase.Starting)
        scope.launch {
            val log = logFile(id)
            val process = try {
                syncRecipes(id)
                rotate(log)
                proot.processBuilder(
                    cool(listOf("/bin/bash", "/opt/pocketserver/recipes/$id/start.sh")),
                    extraEnv = mapOf("APP_ID" to id, "PS_LAN_IPS" to lanAddresses().joinToString(" ")),
                ).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
            } catch (t: Throwable) {
                stopRequested.remove(id)
                setPhase(id, AppPhase.Failed(t.message ?: t.toString())); return@launch
            }
            process.outputStream.close()
            synchronized(processes) { processes[id] = process }

            // Watch for exit in the background; whatever the reason, the app is no longer running.
            // Launched before honouring an early Stop, so that Stop also ends in Stopped.
            scope.launch {
                val code = process.waitFor()
                // Only this run's own entry: a newer run of the same app must not be touched.
                val current = synchronized(processes) { processes.remove(id, process) }
                if (!current) return@launch
                val now = _apps.value.getValue(id).phase
                setPhase(id, when {
                    timedOut.remove(id) -> AppPhase.Failed("Did not start in time. See the log.")
                    now is AppPhase.Failed -> now
                    now is AppPhase.Stopping || code == 0 -> AppPhase.Stopped
                    else -> AppPhase.Failed("Stopped unexpectedly (code $code). See the log.")
                })
            }
            if (stopRequested.remove(id)) { stop(id); return@launch }

            // Ready when the port answers. Big apps (Jellyfin) can take a minute on first start.
            val deadline = System.currentTimeMillis() + 180_000
            while (process.isAlive && System.currentTimeMillis() < deadline) {
                if (ready(app)) {
                    // A Stop (user, heat, power) while booting wins over "ready".
                    if (_apps.value[id]?.phase != AppPhase.Starting) return@launch
                    setPhase(id, AppPhase.Running(System.currentTimeMillis()))
                    repinCores()
                    return@launch
                }
                delay(1000)
            }
            if (process.isAlive) {
                // Failed only once the process is really gone (the exit watcher sets it), so a
                // Restart can never run next to the old, still-stopping instance.
                timedOut += id
                stop(id)
            }
        }
    }

    fun stop(id: String) {
        val process = synchronized(processes) { processes[id] } ?: run {
            // Still spawning: remember the request so the starter stops it as soon as it exists.
            if (_apps.value[id]?.phase == AppPhase.Starting) { stopRequested += id; setPhase(id, AppPhase.Stopping) }
            return
        }
        setPhase(id, AppPhase.Stopping)
        scope.launch {
            // Signalling proot kills the guest abruptly, which can corrupt app databases. Ask the
            // server processes themselves to shut down first; escalate only if they ignore it.
            guestPids(id).forEach { android.os.Process.sendSignal(it, 15 /* SIGTERM */) }
            if (process.waitFor(10, TimeUnit.SECONDS)) return@launch
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                // Killing proot alone can leave a guest that ignores SIGTERM (Jellyfin) running detached.
                guestPids(id).forEach { android.os.Process.sendSignal(it, 9 /* SIGKILL */) }
                process.destroyForcibly()
            }
        }
    }

    /**
     * Puts every process we run back on the efficiency cores if it drifted off them (all threads).
     * Reads /proc and only spawns `taskset` on a mismatch, so a normal tick costs almost nothing.
     */
    fun repinCores() {
        val cores = ThermalGuard.efficiencyCores ?: return
        val wanted = cores.split(',').map { it.trim().toInt() }.toSet()
        val mask = wanted.fold(0L) { m, c -> m or (1L shl c) }.toString(16)
        val myPid = android.os.Process.myPid()
        val myUid = android.os.Process.myUid()
        File("/proc").listFiles { f -> f.name.all(Char::isDigit) }?.forEach { dir ->
            val pid = dir.name.toInt()
            if (pid == myPid) return@forEach
            val status = runCatching { File(dir, "status").readLines() }.getOrNull() ?: return@forEach
            val uid = status.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
            if (uid != myUid) return@forEach
            val allowed = status.firstOrNull { it.startsWith("Cpus_allowed_list:") }?.substringAfter(':')?.trim() ?: return@forEach
            if (parseCpuList(allowed) != wanted) {
                runCatching { ProcessBuilder("/system/bin/taskset", "-a", "-p", mask, "$pid").start().waitFor() }
            }
        }
    }

    private fun parseCpuList(list: String): Set<Int> = list.split(',').flatMap { part ->
        val (a, b) = if ('-' in part) part.split('-').let { it[0] to it[1] } else part to part
        (a.trim().toInt()..b.trim().toInt()).toList()
    }.toSet()

    /** Runs a short command inside Ubuntu on the efficiency cores; returns its exit code. */
    fun runInGuest(script: String): Int =
        runCatching { proot.processBuilder(cool(listOf("/bin/bash", "-c", script))).start().let { p ->
            p.outputStream.close(); p.inputStream.readBytes(); p.waitFor()
        } }.getOrDefault(-1)

    /** PIDs of everything running under the proot instance that started [id]. */
    private fun guestPids(id: String): List<Int> {
        val parents = mutableMapOf<Int, Int>()
        var root: Int? = null
        File("/proc").listFiles { f -> f.name.all(Char::isDigit) }?.forEach { dir ->
            val pid = dir.name.toInt()
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            parents[pid] = stat.substringAfterLast(')').trim().split(' ')[1].toInt()
            val cmd = runCatching { File(dir, "cmdline").readText() }.getOrNull() ?: return@forEach
            if ("libproot.so" in cmd && "recipes/$id/start.sh" in cmd) root = pid
        }
        val top = root ?: return emptyList()
        val tree = mutableListOf<Int>()
        var frontier = listOf(top)
        while (frontier.isNotEmpty()) {
            frontier = parents.filter { it.value in frontier }.keys.toList()
            tree += frontier
        }
        return tree
    }

    /** Stops every app and also forgets apps paused while waiting for the charger. */
    fun stopAll() {
        power.cancelWaiting()
        // Also apps still spawning (no process yet): stop() remembers those for the starter.
        val ids = synchronized(processes) { processes.keys.toSet() } + _apps.value.filterValues { it.active }.keys
        ids.forEach(::stop)
    }

    /**
     * Caps server logs while they run (start-time rotation alone lets a weeks-long home-server run
     * grow its log without bound). Copy-truncate: the server keeps appending to the same file.
     */
    fun capLogs() {
        _apps.value.filterValues { it.active }.keys.forEach { id ->
            val log = logFile(id)
            if (log.length() <= 2_000_000) return@forEach
            runCatching {
                log.copyTo(File(log.path + ".1"), overwrite = true)
                java.io.RandomAccessFile(log, "rw").use { it.setLength(0) }
            }
        }
    }

    fun lanAddresses(): List<String> {
        // Only Wi-Fi/Ethernet: with a VPN or mobile data active, the default network's address is
        // useless to devices on the local network (wrong QR codes, URLs and HTTPS certificates).
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.filter { n ->
            cm.getNetworkCapabilities(n)?.let {
                (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }.flatMap { n -> cm.getLinkProperties(n)?.linkAddresses.orEmpty() }
            .map { it.address }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct()
    }

    /** Port open, and for plain-HTTP apps a non-5xx answer (Jellyfin opens its port long before it can serve). */
    private fun ready(app: CatalogApp): Boolean {
        if (!portOpen(app.port)) return false
        // HTTPS apps answer through nginx at once; probe their local plain-HTTP port instead, which
        // reaches the real backend (code-server, PHP) and only answers once it is up.
        val (port, path) = when {
            app.https && app.localPort != null -> app.localPort to (app.healthPath ?: "/")
            app.https -> return true
            else -> app.port to (app.healthPath ?: return true)
        }
        return runCatching {
            val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 1000; conn.readTimeout = 2000; conn.instanceFollowRedirects = false
            try {
                conn.responseCode in 100..499 &&
                    (app.healthText == null || conn.inputStream.bufferedReader().use { it.readText() }.contains(app.healthText))
            } finally { conn.disconnect() }
        }.getOrDefault(false)
    }

    private fun portOpen(port: Int) = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
    }.isSuccess

    /** Rotates a log over 1 MB on start, keeping one previous file. */
    private fun rotate(log: File) {
        log.parentFile?.mkdirs()
        if (log.length() > 1_000_000) log.renameTo(File(log.path + ".1"))
    }

    companion object {
        const val VAULT = "vaultwarden"
        @Volatile private var instance: AppManager? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: AppManager(context.applicationContext).also { instance = it }
        }
    }
}
