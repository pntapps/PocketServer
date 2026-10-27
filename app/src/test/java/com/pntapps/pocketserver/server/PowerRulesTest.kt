package com.pntapps.pocketserver.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerRulesTest {
    private val min = 60_000L
    private val onDemand = PowerSettings()
    private val home = PowerSettings(mode = PowerMode.HOME_SERVER)
    private fun battery(percent: Int = 80, plugged: Boolean = false, temp: Float = 30f) = BatteryInfo(percent, plugged, temp)

    @Test fun onDemandStopsOnlyExpiredApps() {
        val running = mapOf("a" to 0L, "b" to 30 * min)
        assertEquals(listOf("a"), PowerRules.onDemandExpired(onDemand, running, 60 * min))
        assertEquals(emptyList<String>(), PowerRules.onDemandExpired(onDemand, running, 59 * min))
        assertEquals(emptyList<String>(), PowerRules.onDemandExpired(home, running, 999 * min))
    }

    @Test fun minutesLeftRoundsUpFromOldestApp() {
        val running = mapOf("a" to 0L, "b" to 30 * min)
        assertEquals(42, PowerRules.minutesLeft(onDemand, running, 18 * min))
        assertEquals(1, PowerRules.minutesLeft(onDemand, running, 59 * min + 1))
        assertNull(PowerRules.minutesLeft(onDemand, emptyMap(), 0))
        assertNull(PowerRules.minutesLeft(home, running, 0))
    }

    @Test fun lowBatteryOnlyWhenUnplugged() {
        assertEquals(BatteryStop.LOW, PowerRules.batteryStop(onDemand, battery(percent = 15)))
        assertNull(PowerRules.batteryStop(onDemand, battery(percent = 16)))
        assertNull(PowerRules.batteryStop(onDemand, battery(percent = 5, plugged = true)))
    }

    @Test fun heatStopsEvenWhileCharging() {
        assertEquals(BatteryStop.HOT, PowerRules.batteryStop(onDemand, battery(plugged = true, temp = 40f)))
        assertNull(PowerRules.batteryStop(onDemand, battery(plugged = true, temp = 39.9f)))
    }

    @Test fun pauseOnlyOnUnplugInHomeChargingMode() {
        val unplugged = battery(plugged = false)
        assertTrue(PowerRules.shouldPause(home, true, unplugged))
        assertFalse(PowerRules.shouldPause(home, false, unplugged)) // started on battery by the user
        assertFalse(PowerRules.shouldPause(home, null, unplugged)) // first (sticky) reading
        assertFalse(PowerRules.shouldPause(home.copy(homeOnlyWhileCharging = false), true, unplugged))
        assertFalse(PowerRules.shouldPause(onDemand, true, unplugged))
    }

    @Test fun resumeNeedsChargerAndCoolBattery() {
        assertTrue(PowerRules.canResume(home, battery(plugged = true)))
        assertFalse(PowerRules.canResume(home, battery(plugged = false)))
        assertFalse(PowerRules.canResume(home, battery(plugged = true, temp = 45f)))
    }

    @Test fun scanOncePerDayWhileChargingAndRunning() {
        val day = PowerRules.SCAN_INTERVAL_MS
        assertTrue(PowerRules.scanDue(plugged = true, jellyfinRunning = true, lastScan = 0, now = day))
        assertFalse(PowerRules.scanDue(plugged = true, jellyfinRunning = true, lastScan = 1, now = day))
        assertFalse(PowerRules.scanDue(plugged = false, jellyfinRunning = true, lastScan = 0, now = day))
        assertFalse(PowerRules.scanDue(plugged = true, jellyfinRunning = false, lastScan = 0, now = day))
    }

    @Test fun statusLines() {
        val running = mapOf("a" to 0L)
        assertEquals("Auto-stop in 42 min", PowerRules.status(onDemand, null, running, false, null, 18 * min))
        assertEquals("Auto-stop in 1 h 30 min", PowerRules.status(onDemand.copy(onDemandMinutes = 120), null, running, false, null, 30 * min))
        assertEquals("Paused: waiting for charger", PowerRules.status(home, battery(), emptyMap(), true, null, 0))
        assertEquals("Paused: waiting to cool down", PowerRules.status(home, battery(plugged = true, temp = 50f), emptyMap(), true, null, 0))
        assertEquals("Home server: on battery", PowerRules.status(home, battery(), running, false, null, 0))
        assertEquals("Home server: on charger", PowerRules.status(home, battery(plugged = true), running, false, null, 0))
        assertEquals("Stopped: battery low (14%)", PowerRules.status(onDemand, null, emptyMap(), false, "Stopped: battery low (14%)", 0))
        assertNull(PowerRules.status(onDemand, null, emptyMap(), false, null, 0))
    }

    @Test fun idleNeedsAFullQuietWindow() {
        val idle = IdleDetector(thresholdBytes = 1000)
        val window = 30 * min
        assertFalse(idle.sample(0, 0, window))
        for (t in 1..29) assertFalse(idle.sample(t * min, t * 10L, window))
        assertTrue(idle.sample(30 * min, 300, window))
    }

    @Test fun trafficKeepsItAwake() {
        val idle = IdleDetector(thresholdBytes = 1000)
        val window = 30 * min
        idle.sample(0, 0, window)
        idle.sample(10 * min, 5_000, window) // someone used it at minute 10
        assertFalse(idle.sample(30 * min, 5_000, window))
        assertFalse(idle.sample(39 * min, 5_000, window))
        assertTrue(idle.sample(40 * min, 5_000, window)) // 30 quiet minutes after the burst
    }

    @Test fun counterResetStartsOver() {
        val idle = IdleDetector(thresholdBytes = 1000)
        val window = 30 * min
        idle.sample(0, 1_000_000, window)
        assertFalse(idle.sample(30 * min, 10, window))
        assertTrue(idle.sample(60 * min, 10, window))
    }
}
