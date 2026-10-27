package com.pntapps.pocketserver.backup

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.pntapps.pocketserver.BuildConfig
import com.pntapps.pocketserver.catalog.Catalog
import com.pntapps.pocketserver.linux.Proot
import com.pntapps.pocketserver.server.AppManager
import com.pntapps.pocketserver.server.AppPhase
import com.pntapps.pocketserver.server.AppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.concurrent.thread

enum class BackupKind { Backup, Restore }

sealed interface BackupState {
    data object Idle : BackupState
    /** The picked file is an encrypted backup; waiting for the passphrase ([BackupManager.inspect] again). */
    data class NeedsPassphrase(val uri: Uri, val wrongPassphrase: Boolean = false) : BackupState
    /** A picked file was checked and is a valid backup; waiting for the user to confirm the restore. */
    data class Inspected(val uri: Uri, val manifest: BackupManifest, val sizeBytes: Long?, val encrypted: Boolean = false) : BackupState
    data class Working(
        val kind: BackupKind,
        val step: String,
        val bytes: Long = 0,
        /** Known for restores (size of the backup file); null when unknown. */
        val total: Long? = null,
        val cancelable: Boolean = false,
    ) : BackupState
    data class Done(val kind: BackupKind, val message: String) : BackupState
    data class Failed(val kind: BackupKind, val message: String) : BackupState
}

/** Message meant for the user as-is. */
private class UserError(message: String) : Exception(message)

/** The backup is encrypted and no passphrase was given. */
private class PassphraseRequired : Exception()

/**
 * Backs up and restores app data as a .tar.gz the user stores anywhere through the system file
 * picker, optionally encrypted with a passphrase ([BackupCrypto]). Data is streamed straight
 * between tar (inside Ubuntu) and the picked document, never staged in a temporary copy.
 * App-scoped singleton so an operation survives rotation and leaving the screen; observe [state].
 * Passphrases handed to this class are owned by it and wiped once no longer needed.
 */
class BackupManager private constructor(private val context: Context) {
    private val manager = AppManager.get(context)
    private val paths = manager.paths
    private val proot = Proot(paths)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolver get() = context.contentResolver

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private val _sharedSize = MutableStateFlow<Long?>(null)
    /** Total size of /shared in bytes, null until [refreshSharedSize] finished. */
    val sharedSize: StateFlow<Long?> = _sharedSize.asStateFlow()

    @Volatile private var job: Job? = null
    @Volatile private var process: Process? = null
    /** Passphrase that opened the inspected backup, kept until it is restored or dismissed. */
    @Volatile private var unlocked: Pair<Uri, CharArray>? = null

    val busy get() = _state.value is BackupState.Working

    fun refreshSharedSize() {
        // Includes folders moved to the SD card (the backup reads them through Ubuntu's /shared).
        scope.launch { _sharedSize.value = runCatching { paths.storage.sharedRoots().sumOf { dirSize(it) } }.getOrNull() }
    }

    /** Clears a finished result, a passphrase prompt or a pending restore confirmation. */
    fun dismiss() {
        if (!busy) {
            forgetPassphrase()
            _state.value = BackupState.Idle
        }
    }

    private fun forgetPassphrase() {
        unlocked?.second?.fill('\u0000')
        unlocked = null
    }

    /** Stops the running operation. Only offered while nothing has been deleted yet. */
    fun cancel() {
        val s = _state.value
        if (s is BackupState.Working && s.cancelable) {
            job?.cancel()
            process?.destroy()
        }
    }

    // ---------------------------------------------------------------- backup

    /**
     * Stops every app, then writes a backup of all installed apps (and /shared if asked) to [uri],
     * encrypted with [passphrase] when one is given (wiped afterwards).
     */
    fun backup(uri: Uri, includeShared: Boolean, passphrase: CharArray? = null) {
        val started = launch(BackupKind.Backup) {
            try {
                writeBackup(uri, includeShared, passphrase)
            } finally {
                passphrase?.fill('\u0000')
            }
        }
        if (!started) passphrase?.fill('\u0000')
    }

    private suspend fun CoroutineScope.writeBackup(uri: Uri, includeShared: Boolean, passphrase: CharArray?): BackupState {
        if (!paths.isInstalled) throw UserError("Nothing to back up yet: Ubuntu is not set up.")
        val hadRunning = manager.runningCount > 0
        stopAllApps(BackupKind.Backup)
        val ids = Catalog.apps.map { it.id }.filter { manager.apps.value[it]?.installed == true && appDir(it).isDirectory }
        if (ids.isEmpty() && !includeShared) throw UserError("No apps installed, so there is nothing to back up.")
        // The local CA travels with the apps, so phones and PCs that trust it keep working after a restore.
        val includeCa = ids.isNotEmpty() && File(paths.rootfs, "opt/pocketserver/ca/ca.key").isFile

        paths.shared.mkdirs()
        val manifestDir = File(paths.rootfs, MANIFEST_DIR_GUEST.removePrefix("/")).apply { deleteRecursively(); mkdirs() }
        File(manifestDir, BackupFormat.MANIFEST_NAME).writeText(
            BackupFormat.encode(
                BackupManifest(
                    createdAt = System.currentTimeMillis(),
                    appVersion = BuildConfig.VERSION_NAME,
                    apps = ids,
                    shared = includeShared,
                    ca = includeCa,
                )
            )
        )

        var ok = false
        var written = 0L
        var warning = false
        try {
            val sink = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(uri, "w")
                ?: throw UserError("Could not open the backup file for writing.")
            sink.use { file ->
                // Counts what reaches the file, so progress and size include the encryption overhead.
                val counted = CountingOutputStream(file)
                val out = if (passphrase != null) {
                    _state.value = BackupState.Working(BackupKind.Backup, "Preparing encryption", cancelable = true)
                    try {
                        BackupCrypto.encryptingStream(counted, passphrase)
                    } catch (e: IOException) {
                        throw UserError("Could not write the backup file: ${e.message ?: "I/O error"}")
                    }
                } else counted
                val step = if (passphrase != null) "Writing encrypted backup" else "Writing backup"
                val p = proot.processBuilder(BackupFormat.createArgs(MANIFEST_DIR_GUEST, ids, includeShared, includeCa))
                    .redirectErrorStream(false)
                    .start()
                process = p
                p.outputStream.close()
                val errors = Drain(p.errorStream)
                try {
                    pump(p.inputStream, out) {
                        _state.value = BackupState.Working(BackupKind.Backup, step, counted.count, null, cancelable = true)
                    }
                    // Closing writes the final encrypted chunk (and closes the file, which use{} tolerates).
                    out.close()
                    written = counted.count
                } catch (e: IOException) {
                    p.destroy()
                    ensureActive()
                    throw UserError("Could not write the backup file: ${e.message ?: "I/O error"}")
                }
                val code = p.waitFor()
                ensureActive()
                // GNU tar: 1 means a file changed while it was read; the archive is still complete.
                if (code != 0 && code != 1) throw UserError(tarError("Backup failed", code, errors.tail()))
                warning = code == 1
            }
            ok = true
        } finally {
            process = null
            manifestDir.deleteRecursively()
            // Do not leave a truncated file behind that looks like a valid backup.
            if (!ok) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        }

        val what = buildList {
            if (ids.isNotEmpty()) add(ids.joinToString { Catalog.byId(it).name })
            if (includeShared) add("shared folder")
        }.joinToString(" + ")
        return BackupState.Done(
            BackupKind.Backup,
            (if (passphrase != null) "Encrypted backup" else "Unencrypted backup") +
                " saved (${BackupFormat.formatBytes(written)}): $what." +
                (if (warning) " Some files changed while being read; their copy may be incomplete." else "") +
                (if (hadRunning) " Start your apps again from the Apps screen." else ""),
        )
    }

    // ---------------------------------------------------------------- restore

    /**
     * Checks that [uri] is a PocketServer backup and shows what it holds; nothing is changed. An
     * encrypted backup without (or with a wrong) [passphrase] ends in [BackupState.NeedsPassphrase];
     * call again with the passphrase. A passphrase that opens the backup is kept for [restore].
     */
    fun inspect(uri: Uri, passphrase: CharArray? = null) {
        val started = launch(BackupKind.Restore) {
            forgetPassphrase()
            _state.value = BackupState.Working(BackupKind.Restore, if (passphrase != null) "Unlocking backup" else "Checking backup")
            var keep = false
            try {
                val (manifest, encrypted) = readManifest(uri, passphrase)
                if (encrypted && passphrase != null) { unlocked = uri to passphrase; keep = true }
                BackupState.Inspected(uri, manifest, fileSize(uri), encrypted)
            } catch (e: PassphraseRequired) {
                BackupState.NeedsPassphrase(uri)
            } catch (e: WrongPassphraseException) {
                BackupState.NeedsPassphrase(uri, wrongPassphrase = true)
            } finally {
                if (!keep) passphrase?.fill('\u0000')
            }
        }
        if (!started) passphrase?.fill('\u0000')
    }

    /**
     * Replaces the data of every app in the backup (installing apps that are missing first) and the
     * shared folder if the backup has one. Current data is only deleted once the whole backup has
     * been unpacked successfully next to it. An encrypted backup must have been unlocked by
     * [inspect] first, otherwise this ends in [BackupState.NeedsPassphrase].
     */
    fun restore(uri: Uri) {
        launch(BackupKind.Restore) {
            val passphrase = unlocked?.takeIf { it.first == uri }?.second
            try {
                restoreFrom(uri, passphrase)
            } catch (e: PassphraseRequired) {
                BackupState.NeedsPassphrase(uri)
            } catch (e: WrongPassphraseException) {
                BackupState.NeedsPassphrase(uri, wrongPassphrase = true)
            } finally {
                forgetPassphrase()
            }
        }
    }

    private suspend fun CoroutineScope.restoreFrom(uri: Uri, passphrase: CharArray?): BackupState {
        _state.value = BackupState.Working(BackupKind.Restore, "Checking backup")
        val (manifest, _) = readManifest(uri, passphrase)
        if (!paths.isInstalled) throw UserError("Set up Ubuntu first (open the Apps screen), then restore.")
        val known = Catalog.apps.map { it.id }.toSet()
        val ids = manifest.apps.filter { it in known }
        val skipped = manifest.apps.filterNot { it in known }
        if (ids.isEmpty() && !manifest.shared) {
            throw UserError("This backup only has apps this version of PocketServer does not know (${skipped.joinToString()}).")
        }

        stopAllApps(BackupKind.Restore)

        // Apps need their programs before their data is useful: install what is missing.
        for (id in ids) {
            if (manager.apps.value.getValue(id).installed) continue
            val name = Catalog.byId(id).name
            _state.value = BackupState.Working(BackupKind.Restore, "Installing $name", cancelable = true)
            manager.install(id)
            val result = manager.apps.first { all ->
                val s = all.getValue(id)
                (s.phase as? AppPhase.Installing)?.let {
                    _state.value = BackupState.Working(BackupKind.Restore, "Installing $name: ${it.step}", cancelable = true)
                }
                s.installed || s.phase is AppPhase.Failed
            }.getValue(id)
            if (!result.installed) {
                val why = (result.phase as? AppPhase.Failed)?.message ?: "unknown error"
                throw UserError("Could not install $name, so nothing was restored. $why")
            }
        }
        stopAllApps(BackupKind.Restore) // In case something was started meanwhile.

        val total = fileSize(uri)
        _state.value = BackupState.Working(BackupKind.Restore, "Unpacking backup", 0, total, cancelable = true)
        runGuest(listOf("/bin/bash", "-c", "rm -rf \"\$1\" && mkdir -p \"\$1\"", "stage", STAGE_GUEST))
            .let { (code, out) -> if (code != 0) throw UserError("Could not prepare the restore: $out") }

        var swapped = false
        try {
            extract(uri, passphrase, ids, manifest.shared, manifest.ca, total)
            ensureActive()

            // Everything promised by the manifest must be there before anything is replaced.
            val stage = File(paths.rootfs, STAGE_GUEST.removePrefix("/"))
            ids.firstOrNull { !File(stage, "apps/$it").isDirectory }?.let {
                throw UserError("The backup is incomplete (${Catalog.byId(it).name} is missing). Your data was not changed.")
            }
            if (manifest.shared && !File(stage, "shared").isDirectory) {
                throw UserError("The backup is incomplete (shared folder is missing). Your data was not changed.")
            }

            _state.value = BackupState.Working(BackupKind.Restore, "Replacing app data", total ?: 0, total, cancelable = false)
            withContext(NonCancellable) {
                val argv = listOf("/bin/bash", "-c", SWAP_SCRIPT, "swap", if (manifest.shared) "1" else "0") + ids
                // Folders on the SD card are bind points: emptied and refilled in place, never replaced.
                val sdFolders = paths.storage.config.value.onSd.joinToString(" ")
                val (code, out) = runGuest(argv, mapOf("PS_SD_FOLDERS" to sdFolders))
                swapped = true
                if (code != 0) throw UserError("Restore failed while replacing data; some apps may be incomplete. Run the restore again. ($out)")
            }
        } finally {
            if (!swapped) withContext(NonCancellable) { runCatching { runGuest(listOf("rm", "-rf", STAGE_GUEST)) } }
        }

        manager.reload()
        val what = buildList {
            if (ids.isNotEmpty()) add(ids.joinToString { Catalog.byId(it).name })
            if (manifest.shared) add("shared folder")
        }.joinToString(" + ")
        return BackupState.Done(
            BackupKind.Restore,
            "Restored $what. Start your apps from the Apps screen." +
                (if (skipped.isNotEmpty()) " Skipped apps this version does not know: ${skipped.joinToString()}." else ""),
        )
    }

    private suspend fun CoroutineScope.extract(uri: Uri, passphrase: CharArray?, ids: List<String>, shared: Boolean, ca: Boolean, total: Long?) {
        val opened = open(uri, passphrase)
        opened.stream.use { src ->
            val p = proot.processBuilder(BackupFormat.extractArgs(STAGE_GUEST, ids, shared, ca)).start()
            process = p
            val output = Drain(p.inputStream)
            try {
                // Progress counts file bytes so it matches the file size, encrypted or not.
                pump(src, p.outputStream, closeOnWriteError = true) {
                    _state.value = BackupState.Working(BackupKind.Restore, "Unpacking backup", opened.raw.count, total, cancelable = true)
                }
            } catch (e: BackupCryptoException) {
                // Nothing unauthenticated reached tar, and nothing is swapped in after this.
                p.destroy()
                ensureActive()
                throw UserError("${e.message} Your data was not changed.")
            } catch (e: IOException) {
                p.destroy()
                ensureActive()
                throw UserError("Could not read the backup file: ${e.message ?: "I/O error"}. Your data was not changed.")
            } finally {
                runCatching { p.outputStream.close() }
            }
            val code = p.waitFor()
            process = null
            ensureActive()
            if (code != 0) {
                val log = output.tail()
                val msg = when {
                    "No space left" in log -> "Not enough free storage to unpack this backup."
                    "Not found in archive" in log -> "The backup is incomplete."
                    "unexpected end" in log.lowercase() || "invalid compressed data" in log -> "The backup file is truncated or damaged."
                    else -> tarError("Unpacking failed", code, log)
                }
                throw UserError("$msg Your data was not changed.")
            }
        }
    }

    /** Reads the manifest of [uri]; returns it and whether the backup is encrypted. */
    private fun readManifest(uri: Uri, passphrase: CharArray?): Pair<BackupManifest, Boolean> {
        val opened = open(uri, passphrase)
        return opened.stream.use { BackupFormat.readManifest(it) } to opened.encrypted
    }

    /** A backup file opened for reading: [stream] is the .tar.gz, [raw] counts file bytes read. */
    private class OpenedBackup(val stream: InputStream, val raw: CountingInputStream, val encrypted: Boolean)

    /**
     * Opens [uri], decrypting it when it starts with the encrypted-backup magic. Throws
     * [PassphraseRequired] when it is encrypted and [passphrase] is null, and
     * [WrongPassphraseException] / [DamagedBackupException] from [BackupCrypto].
     */
    private fun open(uri: Uri, passphrase: CharArray?): OpenedBackup {
        val input = try {
            resolver.openInputStream(uri)
        } catch (e: Exception) {
            throw UserError("Could not open the file: ${e.message}")
        } ?: throw UserError("Could not open the file.")
        try {
            val buffered = BufferedInputStream(input, 64 * 1024)
            buffered.mark(16)
            val head = ByteArray(BackupCrypto.MAGIC.length)
            var n = 0
            while (n < head.size) {
                val r = buffered.read(head, n, head.size - n)
                if (r < 0) break
                n += r
            }
            buffered.reset()
            val raw = CountingInputStream(buffered)
            if (!BackupCrypto.isEncrypted(head.copyOf(n))) return OpenedBackup(raw, raw, encrypted = false)
            if (passphrase == null) throw PassphraseRequired()
            return OpenedBackup(BackupCrypto.decryptingStream(raw, passphrase), raw, encrypted = true)
        } catch (e: Throwable) {
            runCatching { input.close() }
            throw e
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Runs [block] as the current operation; returns false (and does nothing) while one is running. */
    private fun launch(kind: BackupKind, block: suspend CoroutineScope.() -> BackupState): Boolean = synchronized(this) {
        if (busy) return@synchronized false
        _state.value = BackupState.Working(kind, "Starting")
        job = scope.launch {
            manager.maintenance = "A backup or restore is running. Try again when it has finished."
            _state.value = try {
                manager.keepAlive { block() }
            } catch (e: CancellationException) {
                _state.value = BackupState.Failed(kind, "Cancelled. Your data was not changed.")
                throw e
            } catch (e: Exception) {
                if (!isActive) BackupState.Failed(kind, "Cancelled. Your data was not changed.")
                else BackupState.Failed(kind, e.message ?: e.toString())
            } finally {
                process = null
                manager.maintenance = null
            }
        }
        true
    }

    private fun appDir(id: String) = File(paths.rootfs, "opt/pocketserver/apps/$id")

    private fun AppState.live() = phase is AppPhase.Starting || phase is AppPhase.Running || phase is AppPhase.Stopping

    /** Stops every app and waits until all have exited; refuses while an app is installing. */
    private suspend fun stopAllApps(kind: BackupKind) {
        manager.apps.value.filter { it.value.phase is AppPhase.Installing }.keys.takeIf { it.isNotEmpty() }?.let { ids ->
            throw UserError("Wait for ${ids.joinToString { Catalog.byId(it).name }} to finish installing, then try again.")
        }
        if (manager.apps.value.values.none { it.live() }) return
        _state.value = BackupState.Working(kind, "Stopping apps")
        val done = withTimeoutOrNull(90_000) {
            while (manager.apps.value.values.any { it.live() }) {
                // Re-issued because an app that was still starting may only now be stoppable.
                manager.apps.value.filter { it.value.phase is AppPhase.Starting || it.value.phase is AppPhase.Running }
                    .keys.forEach(manager::stop)
                delay(1000)
            }
        }
        if (done == null) throw UserError("Could not stop all apps. Stop them on the Apps screen and try again.")
    }

    /** Runs a command inside Ubuntu and returns its exit code and last output lines. */
    private fun runGuest(argv: List<String>, env: Map<String, String> = emptyMap()): Pair<Int, String> {
        val p = proot.processBuilder(argv, extraEnv = env).start()
        p.outputStream.close()
        val out = Drain(p.inputStream)
        val code = p.waitFor()
        return code to out.tail()
    }

    /**
     * Copies [input] to [output], reporting the byte count at most four times a second. With
     * [closeOnWriteError] a failing write (the reader exited) ends the copy quietly so the caller
     * can report the reader's own error instead.
     */
    private fun CoroutineScope.pump(
        input: InputStream,
        output: OutputStream,
        closeOnWriteError: Boolean = false,
        onProgress: (Long) -> Unit,
    ): Long {
        val buf = ByteArray(256 * 1024)
        var total = 0L
        var last = 0L
        while (true) {
            ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            try {
                output.write(buf, 0, n)
            } catch (e: IOException) {
                if (closeOnWriteError) break else throw e
            }
            total += n
            val now = SystemClock.uptimeMillis()
            if (now - last > 250) { onProgress(total); last = now }
        }
        onProgress(total)
        return total
    }

    private fun fileSize(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()?.takeIf { it > 0 }

    private fun tarError(what: String, code: Int, log: String) =
        "$what (tar exit code $code)" + if (log.isNotBlank()) ":\n$log" else "."

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }

        override fun markSupported() = false
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        @Volatile var count = 0L
            private set

        override fun write(b: Int) { out.write(b); count++ }

        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    /** Reads a process stream on a background thread, keeping the last lines for error messages. */
    private class Drain(stream: InputStream) {
        private val lines = ArrayDeque<String>()
        private val reader = thread(isDaemon = true, name = "backup-drain") {
            runCatching {
                stream.bufferedReader().forEachLine { line ->
                    synchronized(lines) { lines.addLast(line); if (lines.size > 50) lines.removeFirst() }
                }
            }
        }

        /** Last meaningful lines; prefers tar/gzip messages over proot chatter. */
        fun tail(n: Int = 3): String {
            reader.join(5000)
            val all = synchronized(lines) { lines.filter { it.isNotBlank() } }
            val relevant = all.filter { it.startsWith("tar:") || it.startsWith("gzip:") || it.startsWith("mv:") || it.startsWith("rm:") || it.startsWith("find:") }
            return (relevant.ifEmpty { all }).takeLast(n).joinToString("\n")
        }
    }

    companion object {
        private const val MANIFEST_DIR_GUEST = "/tmp/pocketserver-backup"
        private const val STAGE_GUEST = "/opt/pocketserver/.restore"

        /**
         * Moves unpacked data into place. Args: <1 if shared is restored> <app id>...
         * Same filesystem, so every mv is a cheap rename. LF line endings only (bash runs this).
         */
        private val SWAP_SCRIPT = """
            set -eu
            PS=/opt/pocketserver
            STAGE="${'$'}PS/.restore"
            SHARED="${'$'}1"; shift
            mkdir -p "${'$'}PS/apps"
            for id in "${'$'}@"; do
                rm -rf "${'$'}PS/apps/${'$'}id"
                mv "${'$'}STAGE/apps/${'$'}id" "${'$'}PS/apps/${'$'}id"
            done
            if [ "${'$'}SHARED" = 1 ]; then
                SD=" ${'$'}{PS_SD_FOLDERS:-} "
                find /shared -mindepth 1 -maxdepth 1 -print0 | while IFS= read -r -d '' e; do
                    case "${'$'}SD" in
                        *" ${'$'}(basename "${'$'}e") "*) find "${'$'}e" -mindepth 1 -delete ;;
                        *) rm -rf "${'$'}e" ;;
                    esac
                done
                find "${'$'}STAGE/shared" -mindepth 1 -maxdepth 1 -print0 | while IFS= read -r -d '' s; do
                    n=${'$'}(basename "${'$'}s")
                    case "${'$'}SD" in
                        # Different filesystem (the card): copy the content, no permissions to keep.
                        *" ${'$'}n "*) cp -r "${'$'}s/." "/shared/${'$'}n/" && rm -rf "${'$'}s" ;;
                        *) mv -t /shared "${'$'}s" ;;
                    esac
                done
            fi
            # The local CA (if the backup has it) replaces this device's, and /shared gets its
            # certificate so devices that trusted it before keep working. Server certificates are
            # re-issued from it on the next start (issue_server_cert checks the CA fingerprint).
            if [ -d "${'$'}STAGE/ca" ]; then
                rm -rf "${'$'}PS/ca"
                mv "${'$'}STAGE/ca" "${'$'}PS/ca"
                cp "${'$'}PS/ca/ca.crt" /shared/PocketServer-CA.crt
            fi
            rm -rf "${'$'}STAGE"
        """.trimIndent().replace("\r", "")

        private fun dirSize(dir: File): Long {
            if (!dir.exists()) return 0
            var total = 0L
            Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
            })
            return total
        }

        @Volatile private var instance: BackupManager? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: BackupManager(context.applicationContext).also { instance = it }
        }
    }
}
