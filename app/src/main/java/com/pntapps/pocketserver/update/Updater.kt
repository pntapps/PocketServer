package com.pntapps.pocketserver.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.pntapps.pocketserver.BuildConfig
import com.pntapps.pocketserver.server.AppManager
import com.pntapps.pocketserver.server.AppPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-update from GitHub Releases. Strictly user-initiated: nothing here runs unless the user taps
 * "Check for updates", so there is no background polling to cost battery.
 *
 * Installing over the existing app keeps all data (Ubuntu rootfs, app configs), but Android only
 * accepts the APK if it is signed with the same key as the installed one.
 */
object Updater {
    val repo: String get() = BuildConfig.UPDATE_REPO
    val enabled: Boolean get() = repo.isNotBlank()
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    data class Release(
        val tag: String,
        val name: String,
        val notes: String,
        val pageUrl: String,
        val apkName: String,
        val apkUrl: String,
        val apkSize: Long,
        val sha256Url: String?,
    ) {
        val newer: Boolean get() = Version.isNewer(tag, currentVersion)
    }

    sealed interface InstallStatus {
        data object Idle : InstallStatus
        data object Installing : InstallStatus
        data object Success : InstallStatus
        data class Failed(val message: String) : InstallStatus
    }

    private val _installStatus = MutableStateFlow<InstallStatus>(InstallStatus.Idle)
    val installStatus: StateFlow<InstallStatus> = _installStatus.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private class GhAsset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

    @Serializable
    private class GhRelease(
        @SerialName("tag_name") val tag: String,
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<GhAsset> = emptyList(),
    )

    /** Latest non-prerelease, non-draft release of [repo]. Throws with a user-readable message. */
    suspend fun checkLatest(): Release = withContext(Dispatchers.IO) {
        val conn = open("https://api.github.com/repos/$repo/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            when (conn.responseCode) {
                200 -> {}
                404 -> throw IOException("No published release found for $repo")
                403, 429 -> throw IOException("GitHub rate limit reached, try again later")
                else -> throw IOException("GitHub answered HTTP ${conn.responseCode}")
            }
            val gh = json.decodeFromString<GhRelease>(conn.inputStream.bufferedReader().use { it.readText() })
            val apk = gh.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: throw IOException("Release ${gh.tag} has no APK attached")
            // Accept "app.apk.sha256" or "app.sha256" next to the APK.
            val shaNames = setOf("${apk.name}.sha256", apk.name.removeSuffix(".apk") + ".sha256")
            Release(
                tag = gh.tag,
                name = gh.name?.takeIf { it.isNotBlank() } ?: gh.tag,
                notes = gh.body.orEmpty().trim(),
                pageUrl = gh.htmlUrl,
                apkName = apk.name,
                apkUrl = apk.url,
                apkSize = apk.size,
                sha256Url = gh.assets.firstOrNull { it.name in shaNames }?.url,
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK into cacheDir, reporting 0..1 progress, and verifies its SHA-256 when published. */
    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = open(release.apkUrl)
        try {
            if (conn.responseCode != 200) throw IOException("Download failed: HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            var done = 0L
            var lastReported = -1
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        // Report whole percents only, so the UI recomposes ~100 times, not per chunk.
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != lastReported) { lastReported = pct; onProgress(pct / 100f) }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Download incomplete ($done of $total bytes)")
        } catch (e: Throwable) {
            file.delete()
            throw e
        } finally {
            conn.disconnect()
        }

        release.sha256Url?.let { url ->
            val expected = fetchText(url).trim().split(Regex("\\s+")).first().lowercase()
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expected != actual) {
                file.delete()
                throw IOException("Checksum mismatch, download discarded")
            }
        }
        file
    }

    /**
     * Streams [apk] into a PackageInstaller session and commits it. The outcome arrives in
     * [InstallStatusReceiver]: Android asks the user to confirm (and, the first time, to allow
     * installs from PocketServer), then replaces the app in place.
     */
    suspend fun install(context: Context, apk: File): Unit = withContext(Dispatchers.IO) {
        _installStatus.value = InstallStatus.Installing
        // Replacing the app kills our process, and proot with it: stop servers cleanly first so
        // databases are never SIGKILLed mid-write.
        val manager = AppManager.get(context)
        if (manager.apps.value.values.any { it.active }) {
            manager.stopAll()
            withTimeoutOrNull(60_000) {
                manager.apps.first { all -> all.values.none { it.active || it.phase is AppPhase.Stopping } }
            }
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallStatusReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
                // Mutable: the installer fills in the status extras.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        } catch (e: Throwable) {
            runCatching { installer.abandonSession(id) }
            _installStatus.value = InstallStatus.Failed(e.message ?: "Could not start install")
            throw e
        }
    }

    fun resetInstallStatus() { _installStatus.value = InstallStatus.Idle }

    internal fun onInstallStatus(status: InstallStatus) { _installStatus.value = status }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true // asset downloads redirect to GitHub's CDN
        setRequestProperty("User-Agent", "PocketServer/${BuildConfig.VERSION_NAME}")
    }

    private fun fetchText(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw IOException("Checksum download failed: HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    internal const val ACTION_INSTALL_STATUS = "com.pntapps.pocketserver.update.INSTALL_STATUS"
}

/** Receives PackageInstaller session results; registered (not exported) in the manifest. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android's confirm screen. The app is in the foreground (the user just tapped install), so this may start.
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Updater.onInstallStatus(Updater.InstallStatus.Failed("Installer gave no confirmation screen"))
                } else {
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { Updater.onInstallStatus(Updater.InstallStatus.Failed("Could not open the install prompt: ${it.message}")) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.onInstallStatus(Updater.InstallStatus.Success)
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.onInstallStatus(Updater.InstallStatus.Failed("Install cancelled"))
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Updater.onInstallStatus(Updater.InstallStatus.Failed("Rejected by Android: the update must be signed with the same key as this app. ${message.orEmpty()}".trim()))
            else -> Updater.onInstallStatus(Updater.InstallStatus.Failed(message ?: "Install failed (status $status)"))
        }
    }
}
