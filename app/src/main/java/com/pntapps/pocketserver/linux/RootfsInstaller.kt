package com.pntapps.pocketserver.linux

import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/** Downloads Ubuntu Base (arm64), verifies it, unpacks it into private storage and prepares it for proot. */
class RootfsInstaller(private val paths: LinuxPaths) {

    sealed interface Progress {
        data class Downloading(val bytes: Long, val total: Long) : Progress
        data class Extracting(val entries: Int) : Progress
        data class Configuring(val step: String) : Progress
    }

    suspend fun install(onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val archive = File(paths.downloads, UBUNTU_FILE)
        download(archive, onProgress)
        require(sha256(archive) == UBUNTU_SHA256) {
            archive.delete()
            "Ubuntu download is corrupted (checksum mismatch). Please try again."
        }
        paths.rootfs.deleteRecursively()
        paths.rootfs.mkdirs()
        extract(archive, onProgress)
        archive.delete()
        configure(onProgress)
        paths.installedMarker.writeText(UBUNTU_VERSION)
    }

    private suspend fun download(target: File, onProgress: (Progress) -> Unit) {
        target.parentFile?.mkdirs()
        // Resume a partial download instead of starting over (mobile networks drop).
        val existing = if (target.exists()) target.length() else 0L
        val conn = (URL(UBUNTU_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        // 416: the archive is already complete (setup was interrupted after the download); the
        // checksum check that follows decides whether it is usable or must be fetched again.
        if (existing > 0 && conn.responseCode == 416) { conn.disconnect(); return }
        val resumed = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (!resumed && conn.responseCode != HttpURLConnection.HTTP_OK) {
            error("Download failed (HTTP ${conn.responseCode})")
        }
        val start = if (resumed) existing else 0L
        val total = start + conn.contentLengthLong.coerceAtLeast(0)
        var done = start
        conn.inputStream.use { input ->
            java.io.FileOutputStream(target, resumed).use { out ->
                val buf = ByteArray(64 * 1024)
                var lastReport = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 256 * 1024) {
                        lastReport = done
                        onProgress(Progress.Downloading(done, total))
                    }
                }
            }
        }
        onProgress(Progress.Downloading(done, total))
    }

    private suspend fun extract(archive: File, onProgress: (Progress) -> Unit) {
        val root = paths.rootfs.canonicalFile
        // Hard links are copied: link() into app storage is not reliable across Android versions.
        val hardLinks = mutableListOf<Pair<File, File>>()
        var count = 0
        TarArchiveInputStream(GZIPInputStream(FileInputStream(archive).buffered())).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = tar.nextEntry ?: break
                val out = File(root, entry.name).canonicalFile
                if (!out.path.startsWith(root.path)) continue // path traversal guard
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        Os.symlink(entry.linkName, out.path)
                    }
                    entry.isLink -> hardLinks += out to File(root, entry.linkName)
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it) }
                    }
                }
                if (!entry.isSymbolicLink && (entry.isFile || entry.isDirectory)) {
                    runCatching { Os.chmod(out.path, entry.mode and 0x1FF or 0x180) } // keep owner rw
                }
                if (++count % 200 == 0) onProgress(Progress.Extracting(count))
            }
        }
        for ((link, target) in hardLinks) {
            link.parentFile?.mkdirs()
            target.copyTo(link, overwrite = true)
            runCatching { Os.chmod(link.path, Os.stat(target.path).st_mode and 0x1FF) }
        }
        onProgress(Progress.Extracting(count))
    }

    private fun configure(onProgress: (Progress) -> Unit) {
        onProgress(Progress.Configuring("Network"))
        val etc = File(paths.rootfs, "etc")
        File(etc, "resolv.conf").apply { delete() }.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        File(etc, "hosts").writeText("127.0.0.1 localhost pocketserver\n::1 localhost ip6-localhost ip6-loopback\n")
        File(etc, "hostname").writeText("pocketserver\n")

        onProgress(Progress.Configuring("Package manager"))
        // apt drops privileges to _apt, which proot's fake root cannot do.
        File(etc, "apt/apt.conf.d/01-pocketserver").writeText(
            "APT::Sandbox::User \"root\";\nAPT::Install-Recommends \"false\";\nAcquire::Retries \"3\";\n"
        )
        // Ubuntu Base ships without man pages/docs filters; keep the system small.
        File(etc, "dpkg/dpkg.cfg.d/01-pocketserver").apply { parentFile?.mkdirs() }.writeText(
            "path-exclude=/usr/share/doc/*\npath-include=/usr/share/doc/*/copyright\n" +
                "path-exclude=/usr/share/man/*\npath-exclude=/usr/share/info/*\n"
        )

        // Never let package scripts start daemons behind our back: PocketServer starts/stops every
        // service itself so nothing runs (or drains battery) unless the user pressed Start.
        File(paths.rootfs, "usr/sbin/policy-rc.d").apply {
            writeText("#!/bin/sh\nexit 101\n")
            Os.chmod(path, 0x1ED)
        }

        onProgress(Progress.Configuring("Android groups"))
        // Give names to the Android group ids our process carries so tools stop printing warnings.
        val groupFile = File(etc, "group")
        val known = groupFile.readLines().mapNotNull { it.split(':').getOrNull(2) }.toSet()
        val ourGroups = File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("Groups:") }?.substringAfter(':')?.trim()?.split(Regex("\\s+")).orEmpty()
        val extra = ourGroups.filter { it.isNotBlank() && it !in known }
        if (extra.isNotEmpty()) groupFile.appendText(extra.joinToString("") { "aid_$it:x:$it:root\n" })

        File(paths.rootfs, "tmp").apply { mkdirs(); runCatching { Os.chmod(path, 0x3FF) } }
        File(paths.rootfs, ".l2s").mkdirs()
        paths.shared.mkdirs()
        paths.prootTmp.mkdirs()
        FakeProc.write(paths.fakeProc)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val UBUNTU_VERSION = "24.04.5"
        const val UBUNTU_FILE = "ubuntu-base-24.04.5-base-arm64.tar.gz"
        const val UBUNTU_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/$UBUNTU_FILE"
        const val UBUNTU_SHA256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
    }
}
