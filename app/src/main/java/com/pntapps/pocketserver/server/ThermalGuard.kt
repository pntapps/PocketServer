package com.pntapps.pocketserver.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** How warm the device is, from PocketServer's point of view. Ordered: compare with >=. */
enum class Heat { Cool, Warm, Hot, Critical }

data class ThermalReading(val batteryC: Float, val systemStatus: Int, val headroom: Float?)

/**
 * Keeps the device from overheating. Combines the battery thermometer with Android's own thermal
 * status (CPU/skin sensors, the same signal the system uses to throttle) and its throttling forecast.
 * Both arrive as events, so watching costs nothing between changes.
 *
 * - Warm: heavy maintenance (library scans) is skipped and the HUD stops animating.
 * - Hot: every server and install stops, nothing may start.
 * - Critical: terminal sessions are stopped too.
 * After a Hot stop nothing may start again until the device has cooled [COOL_MARGIN_C] below the limit.
 */
object ThermalRules {
    /** Highest battery temperature limit a user may choose: above this, Li-ion ageing and skin heat get uncomfortable. */
    const val MAX_LIMIT_C = 42
    const val COOL_MARGIN_C = 3
    const val CRITICAL_C = 45

    fun classify(r: ThermalReading, limitC: Int): Heat {
        val limit = limitC.coerceAtMost(MAX_LIMIT_C)
        return when {
            r.batteryC >= CRITICAL_C || r.systemStatus >= PowerManager.THERMAL_STATUS_SEVERE -> Heat.Critical
            r.batteryC >= limit || r.systemStatus >= PowerManager.THERMAL_STATUS_MODERATE -> Heat.Hot
            // The throttling forecast follows skin temperature, which a bright screen alone pushes up,
            // so it only ever means Warm (back off), never a hard stop the OS itself doesn't call for.
            r.batteryC >= limit - COOL_MARGIN_C || r.systemStatus >= PowerManager.THERMAL_STATUS_LIGHT || (r.headroom ?: 0f) >= 1f -> Heat.Warm
            else -> Heat.Cool
        }
    }

    /** Cooled enough to start things again after a heat stop (hysteresis, so it doesn't flap). */
    fun cooled(r: ThermalReading, limitC: Int) =
        r.batteryC < limitC.coerceAtMost(MAX_LIMIT_C) - COOL_MARGIN_C && r.systemStatus < PowerManager.THERMAL_STATUS_MODERATE
}

class ThermalGuard(private val context: Context, private val limitC: () -> Int) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val _heat = MutableStateFlow(Heat.Cool)
    val heat: StateFlow<Heat> = _heat.asStateFlow()

    /** Set by a Hot stop; cleared once the device has cooled. While set, starts and installs are refused. */
    @Volatile var coolingDown = false
        private set

    private var listeners = mutableListOf<(Heat) -> Unit>()
    private var lastBatteryC = 0f

    fun onChange(listener: (Heat) -> Unit) { listeners += listener }

    fun reading() = ThermalReading(
        batteryC = lastBatteryC,
        systemStatus = power.currentThermalStatus,
        headroom = if (Build.VERSION.SDK_INT >= 30) power.getThermalHeadroom(30).takeIf { !it.isNaN() } else null,
    )

    /** Registered once for the app process; both callbacks are pushed by the system, never polled. */
    fun start() {
        val sticky = context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                lastBatteryC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
                evaluate()
            }
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        lastBatteryC = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        power.addThermalStatusListener(context.mainExecutor) { evaluate() }
        evaluate()
    }

    // Called from the main thread (battery/thermal callbacks) and IO (settings, install checks).
    @Synchronized
    fun evaluate() {
        val r = reading()
        val level = ThermalRules.classify(r, limitC())
        if (level >= Heat.Hot) coolingDown = true
        else if (coolingDown && ThermalRules.cooled(r, limitC())) coolingDown = false
        if (level != _heat.value) {
            _heat.value = level
            listeners.forEach { it(level) }
        }
    }

    /** Why a start/install must wait, or null when it may go ahead. */
    fun blockReason(): String? {
        evaluate()
        val r = reading()
        val cause = if (r.batteryC >= limitC().coerceAtMost(ThermalRules.MAX_LIMIT_C)) "battery ${r.batteryC.toInt()} °C"
            else "Android reports ${statusName(r.systemStatus)} thermal load, battery ${r.batteryC.toInt()} °C"
        return when {
            heat.value >= Heat.Hot -> "Device is too hot ($cause). Let it cool down first."
            coolingDown -> "Cooling down after a heat stop. Try again when the battery is below ${limitC().coerceAtMost(ThermalRules.MAX_LIMIT_C) - ThermalRules.COOL_MARGIN_C} °C."
            else -> null
        }
    }

    private fun statusName(status: Int) = when (status) {
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "critical"
        else -> "normal"
    }

    companion object {
        /**
         * The device's efficiency cores (lowest max frequency), e.g. "0-3". Servers are pinned to them:
         * serving files or direct-play media doesn't need the big cores, which make most of the heat.
         * Null if the cores can't be read or are all the same.
         */
        val efficiencyCores: String? by lazy {
            val freqs = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
                ?.mapNotNull { dir ->
                    val max = runCatching { File(dir, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
                    max?.let { dir.name.removePrefix("cpu").toInt() to it }
                }.orEmpty()
            val lowest = freqs.minOfOrNull { it.second } ?: return@lazy null
            val little = freqs.filter { it.second == lowest }.map { it.first }.sorted()
            if (little.isEmpty() || little.size == freqs.size) null else little.joinToString(",")
        }
    }
}
