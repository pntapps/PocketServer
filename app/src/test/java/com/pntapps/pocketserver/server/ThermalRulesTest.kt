package com.pntapps.pocketserver.server

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalRulesTest {
    private fun r(batteryC: Float, status: Int = PowerManager.THERMAL_STATUS_NONE, headroom: Float? = null) =
        ThermalReading(batteryC, status, headroom)

    @Test fun batteryTemperatureStages() {
        assertEquals(Heat.Cool, ThermalRules.classify(r(36.9f), 40))
        assertEquals(Heat.Warm, ThermalRules.classify(r(37f), 40))
        assertEquals(Heat.Hot, ThermalRules.classify(r(40f), 40))
        assertEquals(Heat.Critical, ThermalRules.classify(r(45f), 40))
    }

    @Test fun limitIsCappedAtSafeMaximum() {
        assertEquals(Heat.Hot, ThermalRules.classify(r(42f), 60))
    }

    @Test fun systemThermalStatusWinsOverCoolBattery() {
        assertEquals(Heat.Warm, ThermalRules.classify(r(30f, PowerManager.THERMAL_STATUS_LIGHT), 40))
        assertEquals(Heat.Hot, ThermalRules.classify(r(30f, PowerManager.THERMAL_STATUS_MODERATE), 40))
        assertEquals(Heat.Critical, ThermalRules.classify(r(30f, PowerManager.THERMAL_STATUS_SEVERE), 40))
    }

    @Test fun throttlingForecastOnlyMeansWarm() {
        assertEquals(Heat.Cool, ThermalRules.classify(r(30f, headroom = 0.9f), 40))
        assertEquals(Heat.Warm, ThermalRules.classify(r(34f, headroom = 1.2f), 40))
    }

    @Test fun mustCoolBelowMarginBeforeRestart() {
        assertFalse(ThermalRules.cooled(r(38f), 40))
        assertTrue(ThermalRules.cooled(r(36.9f), 40))
        assertFalse(ThermalRules.cooled(r(30f, PowerManager.THERMAL_STATUS_MODERATE), 40))
    }
}
