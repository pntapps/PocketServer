package com.pntapps.pocketserver.linux

import java.io.File

/**
 * Builds proot invocations for the Ubuntu rootfs.
 *
 * Android (targetSdk 29+) forbids execve() of files in app-writable storage. proot itself and its
 * loader live in nativeLibraryDir (exec allowed); every Ubuntu binary is then mapped into memory by
 * the loader instead of being exec()'d directly, so the guest system works without root.
 * This is the only class that knows how a Linux program is started - keep it that way so the
 * mechanism can be swapped if a future Android release changes the rules.
 */
class Proot(private val paths: LinuxPaths) {

    /** Full argv (proot + guest command) for [ProcessBuilder] or a terminal session. */
    fun command(guestArgv: List<String>, cwd: String = "/root", extraEnv: Map<String, String> = emptyMap()): List<String> {
        val args = mutableListOf(
            paths.proot.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-L",
            "--sysvipc",
            "-r", paths.rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/proc/self/fd/0:/dev/stdin",
            "-b", "/proc/self/fd/1:/dev/stdout",
            "-b", "/proc/self/fd/2:/dev/stderr",
            "-b", "${File(paths.rootfs, "tmp").absolutePath}:/dev/shm",
            "-b", "${paths.shared.absolutePath}:/shared",
        )
        // Media folders moved to the SD card, bound over their place in /shared (after /shared itself).
        for ((host, guest) in paths.storage.binds()) args += listOf("-b", "$host:$guest")
        // Android hides several /proc files from apps; programs like ps/top/apt expect them.
        for ((procPath, fakeName) in FakeProc.files) {
            if (!File(procPath).canRead()) {
                args += listOf("-b", "${File(paths.fakeProc, fakeName).absolutePath}:$procPath")
            }
        }
        args += listOf("-w", cwd)

        val env = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "TMPDIR" to "/tmp",
            // Android's zone: logs and schedules in local time, not UTC.
            "TZ" to guestTimeZone(),
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
        env.putAll(extraEnv)
        args += "/usr/bin/env"
        args += "-i"
        args += env.map { (k, v) -> "$k=$v" }
        args += guestArgv
        return args
    }

    /**
     * A TZ value Ubuntu understands. Android still reports some legacy zone names (Asia/Calcutta)
     * that Ubuntu 24.04 only ships in tzdata-legacy: use the current IANA name when Android knows
     * it, else a fixed-offset POSIX zone ("<+0530>-05:30"; POSIX inverts the sign).
     */
    private fun guestTimeZone(): String {
        val tz = java.util.TimeZone.getDefault()
        val id = if (android.os.Build.VERSION.SDK_INT >= 34) android.icu.util.TimeZone.getIanaID(tz.id) ?: tz.id else tz.id
        if (File(paths.rootfs, "usr/share/zoneinfo/$id").isFile) return id
        val minutes = tz.getOffset(System.currentTimeMillis()) / 60_000
        val h = kotlin.math.abs(minutes) / 60
        val m = kotlin.math.abs(minutes) % 60
        return "<%s%02d%02d>%s%02d:%02d".format(if (minutes >= 0) "+" else "-", h, m, if (minutes >= 0) "-" else "+", h, m)
    }

    /** Host-side environment proot itself needs. */
    fun hostEnv(): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to paths.nativeLibDir.absolutePath,
        "PROOT_LOADER" to paths.prootLoader.absolutePath,
        "PROOT_TMP_DIR" to paths.prootTmp.absolutePath,
        "PROOT_L2S_DIR" to File(paths.rootfs, ".l2s").absolutePath,
    )

    fun processBuilder(guestArgv: List<String>, cwd: String = "/root", extraEnv: Map<String, String> = emptyMap()): ProcessBuilder {
        paths.prootTmp.mkdirs()
        return ProcessBuilder(command(guestArgv, cwd, extraEnv)).apply {
            environment().clear()
            environment().putAll(hostEnv())
            redirectErrorStream(true)
        }
    }

    /** Runs a shell snippet inside Ubuntu, streaming each output line to [onLine]. Returns the exit code. */
    fun runScript(script: String, onLine: (String) -> Unit = {}): Int {
        val process = processBuilder(listOf("/bin/bash", "-c", script)).start()
        process.outputStream.close()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        return process.waitFor()
    }
}
