package com.pntapps.pocketserver.linux

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException

/**
 * Optional SD card storage for the media folders in /shared (Movies, Music, Downloads...).
 *
 * Only the app's own folder on the card is used (Android/data/<package>/files, from
 * getExternalFilesDirs): no storage permission, nothing outside our folder is touched, and Android
 * deletes it on uninstall, so the clean-uninstall rule still holds. Inside Ubuntu nothing changes:
 * proot binds `<card>/shared/<Folder>` over `/shared/<Folder>`, so every app keeps its usual paths.
 *
 * Only plain files belong there. SD cards are FAT/exFAT (no Unix permissions, symlinks or hard
 * links), so Ubuntu, app data and databases always stay in internal storage.
 */
class SharedStorage private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val internalShared = File(appContext.filesDir.canonicalFile, "shared")
    private val configFile = File(appContext.filesDir.canonicalFile, "storage.conf")

    /** A mounted, removable card and our folder on it. */
    data class Card(val uuid: String, val label: String, val root: File) {
        val freeBytes get() = root.usableSpace
        val totalBytes get() = root.totalSpace
    }

    /** Which folders live on which card (by volume UUID, so a different card is never used by mistake). */
    data class Config(val uuid: String? = null, val onSd: Set<String> = emptySet())

    sealed interface Move {
        data object Idle : Move
        data class Working(val folder: String, val toSd: Boolean, val doneBytes: Long, val totalBytes: Long) : Move
        data class Failed(val message: String) : Move
        data class Done(val message: String) : Move
    }

    private val _config = MutableStateFlow(load())
    val config: StateFlow<Config> = _config.asStateFlow()

    private val _move = MutableStateFlow<Move>(Move.Idle)
    val move: StateFlow<Move> = _move.asStateFlow()

    /** The first mounted removable volume with our app folder on it, or null (no card, or not readable). */
    fun card(): Card? {
        val sm = appContext.getSystemService(StorageManager::class.java)
        // Index 0 is the primary (internal) volume; entries are null while a volume is unmounted.
        return appContext.getExternalFilesDirs(null).drop(1).filterNotNull().firstNotNullOfOrNull { dir ->
            val volume = sm.getStorageVolume(dir) ?: return@firstNotNullOfOrNull null
            val uuid = volume.uuid ?: return@firstNotNullOfOrNull null
            if (!volume.isRemovable || Environment.getExternalStorageState(dir) != Environment.MEDIA_MOUNTED) return@firstNotNullOfOrNull null
            Card(uuid, volume.getDescription(appContext), dir.canonicalFile)
        }
    }

    /** Why the SD folders can't be used right now (null: fine, or nothing is on the card). */
    fun problem(): String? {
        val c = _config.value
        if (c.onSd.isEmpty()) return null
        val card = card()
        return when {
            card == null -> "The SD card with ${c.onSd.sorted().joinToString()} is not inserted. Insert it, or move those folders back in System › Storage."
            card.uuid != c.uuid -> "A different SD card is inserted. Insert the card that holds ${c.onSd.sorted().joinToString()}, or move them back in System › Storage."
            else -> null
        }
    }

    /** Host folder for [folder] on the card. */
    private fun sdDir(card: Card, folder: String) = File(card.root, "shared/$folder")

    /**
     * Extra proot binds (host path to guest path) for folders on the card. None when the card is
     * missing; starts are refused then (see [problem]), so nothing writes into the empty internal copy.
     */
    fun binds(): List<Pair<String, String>> {
        val c = _config.value
        if (c.onSd.isEmpty()) return emptyList()
        val card = card()?.takeIf { it.uuid == c.uuid } ?: return emptyList()
        return c.onSd.sorted().mapNotNull { folder ->
            val dir = sdDir(card, folder)
            if (!dir.isDirectory && !dir.mkdirs()) return@mapNotNull null
            File(internalShared, folder).mkdirs() // the guest path must exist under /shared
            dir.path to "/shared/$folder"
        }
    }

    /** Host folders holding /shared's content, for size estimates (internal + folders on the card). */
    fun sharedRoots(): List<File> = listOf(internalShared) + binds().map { File(it.first) }

    fun sizeOf(folder: String): Long? {
        val dir = if (folder in _config.value.onSd) card()?.let { sdDir(it, folder) } else File(internalShared, folder)
        return dir?.takeIf { it.exists() }?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() }
    }

    fun fail(message: String) { if (_move.value !is Move.Working) _move.value = Move.Failed(message) }

    fun dismissMove() { if (_move.value !is Move.Working) _move.value = Move.Idle }

    /**
     * Moves one folder's files to the card or back. The caller makes sure no app runs and keeps the
     * device awake. Copy first, switch the folder over, then delete the old copy: an interruption
     * (heat, card pulled, app killed) never loses files, at worst leaves a partial copy behind.
     * [abort] is polled between files (e.g. the device got hot).
     */
    fun moveFolder(folder: String, toSd: Boolean, abort: () -> String? = { null }) {
        require(folder in FOLDERS)
        val card = card() ?: run { _move.value = Move.Failed("No SD card found."); return }
        val c = _config.value
        if (toSd && c.onSd.isNotEmpty() && c.uuid != card.uuid) {
            _move.value = Move.Failed("Other folders are on a different SD card. Insert that card and move them back first.")
            return
        }
        if (!toSd && c.uuid != card.uuid) {
            _move.value = Move.Failed("This folder is on a different SD card. Insert that card first.")
            return
        }
        val from = if (toSd) File(internalShared, folder) else sdDir(card, folder)
        val to = if (toSd) sdDir(card, folder) else File(internalShared, folder)
        val files = from.takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile }?.toList().orEmpty()
        val total = files.sumOf { it.length() }
        val room = if (toSd) card.freeBytes else internalShared.usableSpace
        if (total + RESERVE_BYTES > room) {
            _move.value = Move.Failed("Not enough space: $folder needs ${gb(total)}, ${gb(room)} free.")
            return
        }
        _move.value = Move.Working(folder, toSd, 0, total)
        var done = 0L
        try {
            to.mkdirs() || to.isDirectory || throw IOException("Cannot create ${to.path}")
            val buffer = ByteArray(1 shl 20)
            for (file in files) {
                abort()?.let { throw IOException(it) }
                val target = File(to, file.relativeTo(from).path)
                target.parentFile?.mkdirs()
                file.inputStream().use { input ->
                    target.outputStream().use { output ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            _move.update { (it as? Move.Working)?.copy(doneBytes = done) ?: it }
                        }
                    }
                }
                target.setLastModified(file.lastModified())
            }
        } catch (e: Exception) {
            // The folder stays where it was; the copied part is removed again.
            files.forEach { File(to, it.relativeTo(from).path).delete() }
            _move.value = Move.Failed("Move stopped: ${e.message ?: e}. Nothing was lost; $folder stays where it was.")
            return
        }
        save(Config(card.uuid, if (toSd) c.onSd + folder else c.onSd - folder))
        // The old copy goes only after the switch, so the files always exist in one complete place.
        from.listFiles()?.forEach { it.deleteRecursively() }
        _move.value = Move.Done("$folder is now on ${if (toSd) "the SD card" else "this device"} (${gb(total)}).")
    }

    private fun load(): Config = runCatching {
        val lines = configFile.readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        Config(lines["uuid"]?.ifBlank { null }, lines["sd"].orEmpty().split(',').filter { it in FOLDERS }.toSet())
    }.getOrDefault(Config())

    private fun save(c: Config) {
        val tmp = File(configFile.path + ".tmp")
        tmp.writeText("uuid=${if (c.onSd.isEmpty()) "" else c.uuid.orEmpty()}\nsd=${c.onSd.sorted().joinToString(",")}\n")
        if (!tmp.renameTo(configFile)) throw IOException("Cannot save storage settings")
        _config.value = load()
    }

    companion object {
        /** Media folders that may live on the card; everything else in /shared stays internal. */
        val FOLDERS = listOf("Movies", "Shows", "Music", "Books", "Audiobooks", "Podcasts", "Downloads")

        /** Kept free on the target so a move can never fill a disk completely. */
        private const val RESERVE_BYTES = 200L * 1024 * 1024

        fun gb(bytes: Long) = when {
            bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
            bytes >= 1L shl 20 -> "${bytes shr 20} MB"
            bytes > 0 -> "${maxOf(1, bytes shr 10)} KB"
            else -> "empty"
        }

        @Volatile private var instance: SharedStorage? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: SharedStorage(context).also { instance = it }
        }
    }
}
