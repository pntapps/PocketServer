package com.pntapps.pocketserver.linux

import java.io.File

/** Static stand-ins for /proc files Android does not let apps read (same approach as proot-distro). */
object FakeProc {
    /** guest /proc path -> file name under [LinuxPaths.fakeProc]. */
    val files = linkedMapOf(
        "/proc/loadavg" to "loadavg",
        "/proc/stat" to "stat",
        "/proc/uptime" to "uptime",
        "/proc/version" to "version",
        "/proc/vmstat" to "vmstat",
        "/proc/sys/kernel/cap_last_cap" to "cap_last_cap",
        "/proc/sys/fs/inotify/max_user_watches" to "max_user_watches",
    )

    fun write(dir: File) {
        dir.mkdirs()
        val cpus = Runtime.getRuntime().availableProcessors()
        val stat = buildString {
            appendLine("cpu  1957 0 2877 93280 262 342 254 87 0 0")
            repeat(cpus) { appendLine("cpu$it 31 0 226 12027 82 10 4 9 0 0") }
            appendLine("intr 63361 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0")
            appendLine("ctxt 38014093")
            appendLine("btime 1694292441")
            appendLine("processes 26442")
            appendLine("procs_running 1")
            appendLine("procs_blocked 0")
            appendLine("softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677")
        }
        File(dir, "stat").writeText(stat)
        File(dir, "loadavg").writeText("0.12 0.07 0.02 2/165 765\n")
        File(dir, "uptime").writeText("124.08 932.80\n")
        File(dir, "version").writeText(
            "Linux version 6.2.1-PocketServer (root@pocketserver) (gcc) #1 SMP PREEMPT_DYNAMIC\n"
        )
        File(dir, "vmstat").writeText(
            "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\n" +
                "nr_zone_inactive_file 22858\nnr_zone_active_file 51328\nnr_zone_unevictable 642\n" +
                "nr_zone_write_pending 0\nnr_mlock 0\nnr_bounce 0\nnr_free_cma 0\n"
        )
        File(dir, "cap_last_cap").writeText("40\n")
        File(dir, "max_user_watches").writeText("4096\n")
    }
}
