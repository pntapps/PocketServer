package com.pntapps.pocketserver.server

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class PowerMode {
    /** Apps run for a limited time after Start, then stop by themselves. */
    ON_DEMAND,
    /** Apps run until stopped (optionally only while on the charger). */
    HOME_SERVER,
}

data class PowerSettings(
    val mode: PowerMode = PowerMode.ON_DEMAND,
    /** ON_DEMAND: each app stops this long after it came up. */
    val onDemandMinutes: Int = 60,
    /** ON_DEMAND: stop everything after this long without network traffic (0 = off). */
    val idleStopMinutes: Int = 30,
    /** HOME_SERVER: pause apps while unplugged and resume them on the charger. */
    val homeOnlyWhileCharging: Boolean = true,
    /** Stop everything at or below this charge when not plugged in. */
    val lowBatteryPercent: Int = 15,
    /** Stop everything when the battery reaches this temperature (capped at [ThermalRules.MAX_LIMIT_C]). */
    val heatLimitC: Int = 40,
) {
    companion object {
        val ON_DEMAND_CHOICES = listOf(15, 30, 60, 120, 240)
        val IDLE_CHOICES = listOf(0, 15, 30, 60, 120)
        val LOW_BATTERY_CHOICES = listOf(10, 15, 20, 30)
        val HEAT_CHOICES = listOf(38, 40, 42)
    }
}

private val Context.powerStore by preferencesDataStore(name = "power")

/** Power policy settings, persisted with DataStore. Reading costs nothing until someone collects. */
class PowerSettingsStore(context: Context) {
    private val store = context.applicationContext.powerStore

    val settings: Flow<PowerSettings> = store.data.map(::read)

    /** When PocketServer last asked Jellyfin to scan its libraries (0 = never). */
    val lastLibraryScan: Flow<Long> = store.data.map { it[LAST_SCAN] ?: 0L }

    suspend fun update(transform: (PowerSettings) -> PowerSettings) {
        store.edit { prefs ->
            val s = transform(read(prefs))
            prefs[MODE] = s.mode.name
            prefs[ON_DEMAND_MIN] = s.onDemandMinutes
            prefs[IDLE_MIN] = s.idleStopMinutes
            prefs[HOME_CHARGING] = s.homeOnlyWhileCharging
            prefs[LOW_BATTERY] = s.lowBatteryPercent
            prefs[HEAT_LIMIT] = s.heatLimitC
        }
    }

    suspend fun setLastLibraryScan(ms: Long) {
        store.edit { it[LAST_SCAN] = ms }
    }

    private fun read(p: Preferences): PowerSettings {
        val d = PowerSettings()
        return PowerSettings(
            mode = p[MODE]?.let { runCatching { PowerMode.valueOf(it) }.getOrNull() } ?: d.mode,
            onDemandMinutes = p[ON_DEMAND_MIN]?.takeIf { it > 0 } ?: d.onDemandMinutes,
            idleStopMinutes = p[IDLE_MIN]?.takeIf { it >= 0 } ?: d.idleStopMinutes,
            homeOnlyWhileCharging = p[HOME_CHARGING] ?: d.homeOnlyWhileCharging,
            lowBatteryPercent = p[LOW_BATTERY]?.coerceIn(0, 50) ?: d.lowBatteryPercent,
            heatLimitC = p[HEAT_LIMIT]?.coerceIn(30, ThermalRules.MAX_LIMIT_C) ?: d.heatLimitC,
        )
    }

    private companion object {
        val MODE = stringPreferencesKey("mode")
        val ON_DEMAND_MIN = intPreferencesKey("on_demand_minutes")
        val IDLE_MIN = intPreferencesKey("idle_stop_minutes")
        val HOME_CHARGING = booleanPreferencesKey("home_only_while_charging")
        val LOW_BATTERY = intPreferencesKey("low_battery_percent")
        val HEAT_LIMIT = intPreferencesKey("heat_limit_c")
        val LAST_SCAN = longPreferencesKey("last_library_scan")
    }
}
