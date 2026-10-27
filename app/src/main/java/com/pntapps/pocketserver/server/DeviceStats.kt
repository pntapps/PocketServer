package com.pntapps.pocketserver.server

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.StatFs
import java.io.File

data class StatsSnapshot(
    val batteryPercent: Int,
    val charging: Boolean,
    val batteryTempC: Float,
    val ramTotalMb: Long,
    val ramAvailMb: Long,
    val storageTotalGb: Float,
    val storageFreeGb: Float,
    /** Resident memory of all server processes (everything we run except the app itself). */
    val serversRamMb: Long,
    /** CPU used by server processes since the previous sample, as % of all cores. */
    val serversCpuPercent: Float,
)

/** Sampled on demand by the dashboard only while it is visible; never runs in the background. */
class DeviceStats(private val context: Context) {
    private var lastCpuTicks = 0L
    private var lastSampleMs = 0L
    private val cores = Runtime.getRuntime().availableProcessors()
    private val myPid = android.os.Process.myPid()
    private val myUid = android.os.Process.myUid()

    fun sample(): StatsSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

        val mem = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val fs = StatFs(context.filesDir.path)

        var rssKb = 0L
        var ticks = 0L
        File("/proc").listFiles { f -> f.name.all(Char::isDigit) }?.forEach { dir ->
            val pid = dir.name.toInt()
            if (pid == myPid) return@forEach
            val status = runCatching { File(dir, "status").readLines() }.getOrNull() ?: return@forEach
            val uid = status.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
            if (uid != myUid) return@forEach
            rssKb += status.firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0
            // Fields 14/15 of /proc/pid/stat (utime, stime), counted after the ")" that ends the name.
            runCatching {
                val f = File(dir, "stat").readText().substringAfterLast(')').trim().split(' ')
                ticks += f[11].toLong() + f[12].toLong()
            }
        }
        val now = System.currentTimeMillis()
        val cpu = if (lastSampleMs > 0 && ticks >= lastCpuTicks) {
            (ticks - lastCpuTicks) * 10f /* 100 Hz ticks -> ms */ / ((now - lastSampleMs) * cores) * 100f
        } else 0f
        lastCpuTicks = ticks
        lastSampleMs = now

        return StatsSnapshot(
            batteryPercent = level * 100 / scale.coerceAtLeast(1),
            charging = plugged,
            batteryTempC = temp,
            ramTotalMb = mem.totalMem / 1_048_576,
            ramAvailMb = mem.availMem / 1_048_576,
            storageTotalGb = fs.totalBytes / 1e9f,
            storageFreeGb = fs.availableBytes / 1e9f,
            serversRamMb = rssKb / 1024,
            serversCpuPercent = cpu.coerceIn(0f, 100f),
        )
    }
}
