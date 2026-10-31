package io.github.bert0ns.cribrum.ui

import android.os.PowerManager.THERMAL_STATUS_CRITICAL
import android.os.PowerManager.THERMAL_STATUS_EMERGENCY
import android.os.PowerManager.THERMAL_STATUS_LIGHT
import android.os.PowerManager.THERMAL_STATUS_MODERATE
import android.os.PowerManager.THERMAL_STATUS_NONE
import android.os.PowerManager.THERMAL_STATUS_SEVERE
import android.os.PowerManager.THERMAL_STATUS_SHUTDOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeatTest {
    @Test
    fun `each status maps to its level when there is no headroom`() {
        assertEquals(Heat(HeatLevel.LIGHT, null), heatOf(THERMAL_STATUS_LIGHT, null))
        assertEquals(Heat(HeatLevel.MODERATE, null), heatOf(THERMAL_STATUS_MODERATE, null))
        assertEquals(Heat(HeatLevel.SEVERE, null), heatOf(THERMAL_STATUS_SEVERE, null))
    }

    @Test
    fun `critical, emergency and shutdown all show as severe`() {
        for (status in listOf(THERMAL_STATUS_CRITICAL, THERMAL_STATUS_EMERGENCY, THERMAL_STATUS_SHUTDOWN)) {
            assertEquals(HeatLevel.SEVERE, heatOf(status, null)?.level)
        }
    }

    @Test
    fun `headroom bands map to the levels of the Google scale`() {
        assertEquals(HeatLevel.NORMAL, heatOf(THERMAL_STATUS_NONE, 0.84f)?.level)
        assertEquals(HeatLevel.LIGHT, heatOf(THERMAL_STATUS_NONE, 0.85f)?.level)
        assertEquals(HeatLevel.LIGHT, heatOf(THERMAL_STATUS_NONE, 0.94f)?.level)
        assertEquals(HeatLevel.MODERATE, heatOf(THERMAL_STATUS_NONE, 0.95f)?.level)
        assertEquals(HeatLevel.MODERATE, heatOf(THERMAL_STATUS_NONE, 1.0f)?.level)
        assertEquals(HeatLevel.SEVERE, heatOf(THERMAL_STATUS_NONE, 1.01f)?.level)
    }

    @Test
    fun `the percentage is the distance to 1`() {
        assertEquals(12, heatOf(THERMAL_STATUS_NONE, 0.88f)?.headroomPercent)
        assertEquals(50, heatOf(THERMAL_STATUS_NONE, 0.5f)?.headroomPercent)
        assertEquals(100, heatOf(THERMAL_STATUS_NONE, 0.001f)?.headroomPercent)
    }

    @Test
    fun `a headroom above 1 shows 0 percent`() {
        assertEquals(Heat(HeatLevel.SEVERE, 0), heatOf(THERMAL_STATUS_NONE, 1.3f))
    }

    @Test
    fun `the worse of the status and the headroom wins`() {
        assertEquals(HeatLevel.MODERATE, heatOf(THERMAL_STATUS_MODERATE, 0.5f)?.level)
        assertEquals(HeatLevel.MODERATE, heatOf(THERMAL_STATUS_LIGHT, 0.96f)?.level)
        assertEquals(HeatLevel.SEVERE, heatOf(THERMAL_STATUS_SEVERE, 0.9f)?.level)
    }

    @Test
    fun `the percentage stays when the status is the worse one`() {
        assertEquals(Heat(HeatLevel.MODERATE, 50), heatOf(THERMAL_STATUS_MODERATE, 0.5f))
    }

    @Test
    fun `a headroom of 0 or NaN counts as no reading`() {
        assertEquals(Heat(HeatLevel.LIGHT, null), heatOf(THERMAL_STATUS_LIGHT, 0f))
        assertEquals(Heat(HeatLevel.LIGHT, null), heatOf(THERMAL_STATUS_LIGHT, Float.NaN))
    }

    @Test
    fun `no headroom and a status of none shows nothing`() {
        assertNull(heatOf(THERMAL_STATUS_NONE, null))
        assertNull(heatOf(THERMAL_STATUS_NONE, 0f))
        assertNull(heatOf(THERMAL_STATUS_NONE, Float.NaN))
    }
}
