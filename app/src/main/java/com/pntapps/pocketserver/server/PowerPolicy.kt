package com.pntapps.pocketserver.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.pntapps.pocketserver.MainActivity
import com.pntapps.pocketserver.catalog.Catalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class BatteryInfo(val percent: Int, val plugged: Boolean, val tempC: Float)

enum class BatteryStop { LOW, HOT }

/** The decisions, kept free of Android so they can be unit-tested on the JVM. */
object PowerRules {
    /** Less traffic than this over the idle window counts as "nobody is using it". */
    const val IDLE_THRESHOLD_BYTES = 512L * 1024
    const val SCAN_INTERVAL_MS = 24 * 3_600_000L

    /** Apps (id -> Running since) whose on-demand time is up. */
    fun onDemandExpired(s: PowerSettings, runningSince: Map<String, Long>, now: Long): List<String> =
        if (s.mode != PowerMode.ON_DEMAND) emptyList()
        else runningSince.filterValues { now - it >= s.onDemandMinutes * 60_000L }.keys.toList()

    /** Minutes until the next on-demand stop, rounded up; null when nothing is counting down. */
    fun minutesLeft(s: PowerSettings, runningSince: Map<String, Long>, now: Long): Int? {
        if (s.mode != PowerMode.ON_DEMAND) return null
        val first = runningSince.values.minOrNull() ?: return null
        val left = first + s.onDemandMinutes * 60_000L - now
        return ((left + 59_999) / 60_000).toInt().coerceAtLeast(0)
    }

    /** Why everything must stop right now, if at all. Heat applies even on the charger. */
    fun batteryStop(s: PowerSettings, b: BatteryInfo): BatteryStop? = when {
        b.tempC >= s.heatLimitC -> BatteryStop.HOT
        !b.plugged && b.percent <= s.lowBatteryPercent -> BatteryStop.LOW
        else -> null
    }

    /** Pause only on the unplug itself, so a user who starts an app on battery is not overruled. */
    fun shouldPause(s: PowerSettings, wasPlugged: Boolean?, b: BatteryInfo) =
        s.mode == PowerMode.HOME_SERVER && s.homeOnlyWhileCharging && wasPlugged == true && !b.plugged

    fun canResume(s: PowerSettings, b: BatteryInfo) = b.plugged && b.tempC < s.heatLimitC

    /** Waiting for the charger makes sense only while the settings still ask for it. */
    fun pauseStillWanted(s: PowerSettings) = s.mode == PowerMode.HOME_SERVER && s.homeOnlyWhileCharging

    fun scanDue(plugged: Boolean, jellyfinRunning: Boolean, lastScan: Long, now: Long) =
        plugged && jellyfinRunning && now - lastScan >= SCAN_INTERVAL_MS

    fun status(
        s: PowerSettings,
        b: BatteryInfo?,
        runningSince: Map<String, Long>,
        waiting: Boolean,
        lastStop: String?,
        now: Long,
    ): String? = when {
        runningSince.isEmpty() && waiting ->
            if (b != null && b.plugged && b.tempC >= s.heatLimitC) "Paused: waiting to cool down"
            else "Paused: waiting for charger"
        runningSince.isEmpty() -> lastStop
        s.mode == PowerMode.ON_DEMAND -> "Auto-stop in ${duration(minutesLeft(s, runningSince, now) ?: 0)}"
        !s.homeOnlyWhileCharging -> "Home server: always on"
        b?.plugged == false -> "Home server: on battery"
        else -> "Home server: on charger"
    }

    fun duration(minutes: Int) = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

/**
 * Sliding-window traffic check. Each sample is a cumulative byte counter; the app is idle once a
 * whole window has been observed and the counter grew less than the threshold across it.
 */
class IdleDetector(private val thresholdBytes: Long = PowerRules.IDLE_THRESHOLD_BYTES) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun reset() = samples.clear()

    fun sample(now: Long, bytes: Long, windowMs: Long): Boolean {
        // Counters only go down if they were reset (reboot, network stack restart): start over.
        if (samples.isNotEmpty() && bytes < samples.last().second) samples.clear()
        samples.addLast(now to bytes)
        val cutoff = now - windowMs
        // Keep one sample at or before the window start as the anchor; drop everything older.
        while (samples.size >= 2 && samples[1].first <= cutoff) samples.removeFirst()
        val anchor = samples.first()
        return anchor.first <= cutoff && bytes - anchor.second < thresholdBytes
    }
}

/**
 * Enforces [PowerSettings]. Active only between [attach] and [detach], which [ServerService]
 * calls from onCreate/onDestroy: with no service there is no receiver, no timer, nothing.
 * While attached it uses one dynamic battery receiver and, only while an app runs, one tick a minute.
 * All state is touched on the main thread.
 */
class PowerPolicy internal constructor(private val context: Context, private val manager: AppManager) {
    val store = PowerSettingsStore(context)

    private val _status = MutableStateFlow<String?>(null)
    /** One line for the dashboard, e.g. "Auto-stop in 42 min" or "Paused: waiting for charger". */
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Apps stopped because the charger was unplugged; non-empty means "waiting for charger". */
    @Volatile private var paused: Set<String> = emptySet()
    val waitingForCharger get() = paused.isNotEmpty()
    val pausedCount get() = paused.size

    /** Why apps were last stopped automatically; shown until the user starts something again. */
    @Volatile private var lastStop: String? = null

    private var scope: CoroutineScope? = null
    private var receiver: BroadcastReceiver? = null
    private var settings = PowerSettings()
    private var battery: BatteryInfo? = null
    private var wasPlugged: Boolean? = null
    private val idle = IdleDetector()
    private var idleKey: Map<String, Long> = emptyMap()
    private var lastScan = 0L
    private var lastScanAttempt = 0L
    private var resuming = false

    fun attach() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        wasPlugged = null
        idleKey = emptyMap()
        s.launch {
            // Real settings first: the sticky battery broadcast arrives as soon as we register.
            settings = store.settings.first()
            lastScan = store.lastLibraryScan.first()
            registerBattery()
            launch { store.settings.collect { settings = it; onSettingsChanged() } }
            launch { manager.apps.collect { onAppsChanged() } }
            launch {
                manager.apps.map { apps -> apps.values.any { it.active } }.distinctUntilChanged().collectLatest { active ->
                    while (active) { delay(TICK_MS); tick() }
                }
            }
        }
    }

    fun detach() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        scope?.cancel()
        scope = null
        paused = emptySet()
        resuming = false
        _status.value = lastStop
    }

    /** A Start pressed by the user: forget the last auto-stop reason and any pause for that app. */
    internal fun onUserStart(id: String) {
        lastStop = null
        paused = paused - id
        if (scope == null) _status.value = null
    }

    /** The user stopped everything: drop the "waiting for charger" state as well. */
    internal fun cancelWaiting() {
        if (paused.isEmpty()) return
        paused = emptySet()
        refreshStatus()
        ServerService.sync(context, manager.runningCount)
    }

    private fun registerBattery() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = onBattery(intent)
        }
        receiver = r
        ContextCompat.registerReceiver(context, r, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val b = BatteryInfo(
            percent = level * 100 / scale,
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
            tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
        )
        battery = b
        checkBattery(b)
        wasPlugged = b.plugged
        syncTorrentSpeed()
        maybeScan()
        refreshStatus()
    }

    private fun checkBattery(b: BatteryInfo) {
        val active = activeIds()
        val stop = if (active.isNotEmpty()) PowerRules.batteryStop(settings, b) else null
        when {
            stop == BatteryStop.LOW -> stopEverything(
                "Stopped: battery low (${b.percent}%)", "Servers stopped: battery low",
                "Battery at ${b.percent}%. Apps stop at ${settings.lowBatteryPercent}% unless charging.", alert = true,
            )
            stop == BatteryStop.HOT -> Unit // ThermalGuard stops everything (it also watches the system thermal sensors).
            active.isNotEmpty() && PowerRules.shouldPause(settings, wasPlugged, b) -> pause(active)
            waitingForCharger && PowerRules.canResume(settings, b) && manager.thermal.blockReason() == null -> resume()
        }
    }

    private fun onSettingsChanged() {
        if (waitingForCharger && !PowerRules.pauseStillWanted(settings)) cancelWaiting()
        refreshStatus()
    }

    private fun onAppsChanged() {
        val since = runningSince()
        if (since != idleKey) {
            // A new app came up (or one stopped): measure idleness from now.
            idleKey = since
            idle.reset()
            traffic()?.let { idle.sample(System.currentTimeMillis(), it, idleWindowMs()) }
        }
        refreshStatus()
    }

    private fun tick() {
        battery?.let { if (activeIds().isNotEmpty()) checkBattery(it) }
        syncTorrentSpeed()
        // Some servers (code-server's Node) reset their CPU affinity; pull them back onto the cool cores.
        scope?.launch(Dispatchers.IO) { manager.repinCores(); manager.capLogs() }
        // Refreshes the notification's battery, temperature and auto-stop line (no extra wake-up: same tick).
        ServerService.sync(context, manager.runningCount)
        val now = System.currentTimeMillis()
        if (settings.mode == PowerMode.ON_DEMAND) {
            val expired = PowerRules.onDemandExpired(settings, runningSince(), now)
            if (expired.isNotEmpty()) {
                expired.forEach(manager::stop)
                val names = expired.joinToString { Catalog.byId(it).name }
                val limit = PowerRules.duration(settings.onDemandMinutes)
                lastStop = "Stopped after $limit (on-demand)"
                notify("$names stopped", "On-demand mode stops apps $limit after they start.", alert = false)
            }
            if (settings.idleStopMinutes > 0 && runningSince().isNotEmpty()) {
                val bytes = traffic()
                if (bytes != null && idle.sample(now, bytes, idleWindowMs())) {
                    val window = PowerRules.duration(settings.idleStopMinutes)
                    stopEverything(
                        "Stopped: idle for $window", "Servers stopped: not in use",
                        "No network activity for $window, so PocketServer stopped to save battery.", alert = false,
                    )
                }
            }
        }
        maybeScan()
        refreshStatus()
    }

    private fun pause(active: List<String>) {
        // Set before stopping, so the service sees "waiting" when the last app goes down.
        paused = paused + active
        active.forEach(manager::stop)
    }

    private fun resume() {
        if (resuming) return
        resuming = true
        scope?.launch {
            val ids = paused
            // Apps paused a moment ago may still be shutting down; Start only works once they are Stopped.
            withTimeoutOrNull(20_000) {
                manager.apps.first { apps -> ids.none { apps[it]?.phase is AppPhase.Stopping } }
            }
            ids.forEach(manager::resume)
            paused = emptySet()
            resuming = false
            refreshStatus()
            ServerService.sync(context, manager.runningCount)
        }
    }

    internal fun stopEverything(status: String, title: String, text: String, alert: Boolean) {
        paused = emptySet()
        lastStop = status
        activeIds().forEach(manager::stop)
        notify(title, text, alert)
    }

    /** Last speed mode sent to qBittorrent (null: unknown, e.g. it was just started). */
    @Volatile private var torrentSlow: Boolean? = null

    /**
     * qBittorrent runs at its slow "alternative" speed limits on battery and at full speed on the
     * charger: downloading flat out keeps Wi-Fi, storage and CPU busy, which is what drains and heats.
     */
    private fun syncTorrentSpeed() {
        val running = manager.apps.value[TORRENT]?.phase is AppPhase.Running
        if (!running) { torrentSlow = null; return }
        val slow = battery?.plugged == false
        if (torrentSlow == slow) return
        torrentSlow = slow
        scope?.launch(Dispatchers.IO) {
            val code = manager.runInGuest("bash /opt/pocketserver/recipes/$TORRENT/throttle.sh ${if (slow) "on" else "off"}")
            if (code != 0) torrentSlow = null // try again on the next tick
        }
    }

    /** Charging-only Jellyfin library scan, at most once a day. */
    private fun maybeScan() {
        val b = battery ?: return
        val now = System.currentTimeMillis()
        val jellyfin = manager.apps.value[JELLYFIN]?.phase is AppPhase.Running
        if (!PowerRules.scanDue(b.plugged, jellyfin, lastScan, now) || now - lastScanAttempt < SCAN_RETRY_MS) return
        // A full library scan is the heaviest thing Jellyfin does here; never while the device is warm.
        if (manager.thermal.heat.value >= Heat.Warm) return
        lastScanAttempt = now
        scope?.launch {
            if (withContext(Dispatchers.IO) { requestLibraryScan() }) {
                lastScan = now
                store.setLastLibraryScan(now)
            }
        }
    }

    private fun requestLibraryScan(): Boolean = runCatching {
        val key = File(manager.paths.rootfs, "opt/pocketserver/apps/$JELLYFIN/apikey").readText().trim()
        if (key.isEmpty()) return false
        val conn = URL("http://127.0.0.1:8096/Library/Refresh").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 3000; conn.readTimeout = 10_000
        conn.setRequestProperty("Authorization", "MediaBrowser Token=\"$key\"")
        conn.doOutput = true
        conn.outputStream.close()
        try { conn.responseCode in 200..299 } finally { conn.disconnect() }
    }.getOrDefault(false)

    private fun refreshStatus() {
        _status.value = PowerRules.status(settings, battery, runningSince(), waitingForCharger, lastStop, System.currentTimeMillis())
    }

    private fun activeIds() = manager.apps.value.filterValues { it.active }.keys.toList()

    private fun runningSince() = manager.apps.value.mapNotNull { (id, s) -> (s.phase as? AppPhase.Running)?.let { id to it.since } }.toMap()

    private fun idleWindowMs() = settings.idleStopMinutes * 60_000L

    /** Everything we and our proot guests sent or received (they all run under our uid). */
    private fun traffic(): Long? {
        val uid = android.os.Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        return if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) null else rx + tx
    }

    private fun notify(title: String, text: String, alert: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = if (alert) ALERT_CHANNEL else INFO_CHANNEL
        nm.createNotificationChannel(
            if (alert) NotificationChannel(ALERT_CHANNEL, "Battery and heat stops", NotificationManager.IMPORTANCE_DEFAULT)
            else NotificationChannel(INFO_CHANNEL, "Automatic stops", NotificationManager.IMPORTANCE_LOW)
        )
        // Same intent and flags as the server notification's (they share one PendingIntent).
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, channel)
            .setSmallIcon(if (alert) android.R.drawable.stat_sys_warning else com.pntapps.pocketserver.R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSubText(if (alert) "Protection" else "Battery saver")
            // HUD accents: red for heat/battery protection, gold for routine auto-stops.
            .setColor(if (alert) 0xFFFF4D5E.toInt() else 0xFFFFB547.toInt())
            .setCategory(if (alert) Notification.CATEGORY_STATUS else Notification.CATEGORY_RECOMMENDATION)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Open PocketServer", open).build())
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(STOP_NOTIFICATION_ID, n) }
    }

    private companion object {
        const val TICK_MS = 60_000L
        const val SCAN_RETRY_MS = 30 * 60_000L
        const val TORRENT = "qbittorrent"
        const val JELLYFIN = "jellyfin"
        const val ALERT_CHANNEL = "power_alerts"
        const val INFO_CHANNEL = "power_info"
        const val STOP_NOTIFICATION_ID = 2
    }
}
