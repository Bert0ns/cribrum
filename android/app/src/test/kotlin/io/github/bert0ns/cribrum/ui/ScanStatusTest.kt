package io.github.bert0ns.cribrum.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.bert0ns.cribrum.pipeline.ScanProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Compose tests reach InputManager.getInstance, which SDK 37 removed
class ScanStatusTest {
    @get:Rule
    val compose = createComposeRule()

    private val empty = "No images found yet. Tap Scan now to look for them. The first time takes about 15 to 20 minutes for 10,000 images, and you can leave the app. Then Cribrum scans again at night while the phone charges."
    private val hot = "The phone is hot, so the scan is slower"

    @Test
    fun `an empty index points to Scan now, which starts a scan`() {
        var scans = 0
        compose.setContent { ScanStatus(progress = null, onScan = { scans++ }, indexEmpty = true) }

        compose.onNodeWithText(empty).assertExists()
        compose.onNodeWithText("Scan now").performClick()
        assertTrue(scans == 1)
    }

    @Test
    fun `Scan now waits while a compression runs`() {
        compose.setContent { ScanStatus(progress = null, onScan = {}, compressing = true) }

        compose.onNodeWithText("Scan now").assertDoesNotExist()
        compose.onNodeWithText("A compression is running. Scan now comes back when it ends.").assertExists()
    }

    @Test
    fun `an index with images says nothing about being empty`() {
        compose.setContent { ScanStatus(progress = null, onScan = {}, indexEmpty = false) }

        compose.onNodeWithText(empty).assertDoesNotExist()
    }

    @Test
    fun `a running scan shows its progress and not the empty message`() {
        compose.setContent { ScanStatus(ScanProgress("detectors", 12, 100), onScan = {}, indexEmpty = true) }

        compose.onNodeWithText("Analyzing the images: 12 of 100").assertExists()
        compose.onNodeWithText(empty).assertDoesNotExist()
    }

    @Test
    fun `a running scan can be stopped from the app, in every phase`() {
        var stops = 0
        var progress by mutableStateOf(ScanProgress("scan", 300, 0))
        compose.setContent { ScanStatus(progress, onScan = {}, onStop = { stops++ }) }

        compose.onNodeWithText("Stop scanning").performClick()
        progress = ScanProgress("detectors", 12, 100)
        compose.onNodeWithText("Stop scanning").performClick()

        assertEquals(2, stops)
    }

    @Test
    fun `no stop shows while no scan runs`() {
        compose.setContent { ScanStatus(progress = null, onScan = {}) }

        compose.onNodeWithText("Stop scanning").assertDoesNotExist()
    }

    @Test
    fun `a running scan says which phase it is in out of the total`() {
        compose.setContent { ScanStatus(ScanProgress("detectors", 12, 100), onScan = {}) }

        compose.onNodeWithText("Phase 3 of 5").assertExists()
    }

    @Test
    fun `the phase number shows while the scan looks for images too`() {
        compose.setContent { ScanStatus(ScanProgress("scan", 300, 0), onScan = {}) }

        compose.onNodeWithText("Phase 1 of 5").assertExists()
    }

    @Test
    fun `a scan that has reported nothing yet is in the first phase`() {
        compose.setContent { ScanStatus(ScanProgress("", 0, 0), onScan = {}) }

        compose.onNodeWithText("Phase 1 of 5").assertExists()
    }

    @Test
    fun `the phase number keeps its own line beside the time left`() {
        compose.setContent { ScanStatus(ScanProgress("detectors", 12, 100, leftMs = 12 * 60_000L), onScan = {}) }

        compose.onNodeWithText("Phase 3 of 5").assertExists()
    }

    @Test
    fun `the time left shows rounded to the minute`() {
        compose.setContent { ScanStatus(ScanProgress("detectors", 12, 100, leftMs = 12 * 60_000L + 29_000), onScan = {}) }

        compose.onNodeWithText("About 12 min left in this step").assertExists()
    }

    @Test
    fun `a time left over an hour shows in hours and minutes`() {
        compose.setContent { ScanStatus(ScanProgress("embedded_detectors", 12, 100, leftMs = 95 * 60_000L), onScan = {}) }

        compose.onNodeWithText("About 1 h 35 min left in this step").assertExists()
    }

    @Test
    fun `under a minute it says less than a minute`() {
        compose.setContent { ScanStatus(ScanProgress("detectors", 99, 100, leftMs = 59_000), onScan = {}) }

        compose.onNodeWithText("Less than a minute left in this step").assertExists()
    }

    @Test
    fun `with no estimate yet no time left shows`() {
        compose.setContent { ScanStatus(ScanProgress("detectors", 1, 100), onScan = {}) }

        compose.onNodeWithText("left", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a hot phone says the scan is slower, with no number`() {
        compose.setContent { ScanStatus(ScanProgress("scan", 300, 0), onScan = {}, heat = Heat(HeatLevel.MODERATE, 12)) }

        compose.onNodeWithText(hot).assertExists()
        compose.onNodeWithText("12", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Phase 1 of 5").assertExists()
    }

    @Test
    fun `a severe heat says the same`() {
        compose.setContent { ScanStatus(ScanProgress("detectors", 12, 100), onScan = {}, heat = Heat(HeatLevel.SEVERE, null)) }

        compose.onNodeWithText(hot).assertExists()
    }

    @Test
    fun `a light heat that does not slow the scan shows nothing`() {
        compose.setContent { ScanStatus(ScanProgress("detectors", 12, 100), onScan = {}, heat = Heat(HeatLevel.LIGHT, 20)) }

        compose.onNodeWithText(hot).assertDoesNotExist()
    }

    @Test
    fun `with no reading no heat shows`() {
        compose.setContent { ScanStatus(ScanProgress("detectors", 12, 100), onScan = {}, heat = null) }

        compose.onNodeWithText(hot).assertDoesNotExist()
    }

    @Test
    fun `the heat line does not push the rest down`() {
        var heat by mutableStateOf<Heat?>(null)
        compose.setContent { ScanStatus(ScanProgress("scan", 300, 0), onScan = {}, heat = heat) }
        val before = compose.onNodeWithText("Finding images", substring = true).fetchSemanticsNode().boundsInRoot.top

        heat = Heat(HeatLevel.SEVERE, 0)
        compose.waitForIdle()

        compose.onNodeWithText(hot).assertExists()
        assertEquals(before, compose.onNodeWithText("Finding images", substring = true).fetchSemanticsNode().boundsInRoot.top)
    }

    @Test
    @Config(qualifiers = "it")
    fun `the heat shows in Italian`() {
        compose.setContent { ScanStatus(ScanProgress("scan", 300, 0), onScan = {}, heat = Heat(HeatLevel.MODERATE, 12)) }

        compose.onNodeWithText("Il telefono è caldo, quindi la scansione è più lenta").assertExists()
        compose.onNodeWithText("Fase 1 di 5").assertExists()
    }

    @Test
    fun `the time of the last scan shows beside Scan now`() {
        val at = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.NOVEMBER, 3, 3, 12) }.timeInMillis
        compose.setContent { ScanStatus(progress = null, onScan = {}, lastScanAt = at) }

        compose.onNodeWithText("Last scan: Nov 3", substring = true).assertExists()
        compose.onNodeWithText("Scan now").assertExists()
    }

    @Test
    fun `no time shows before the first scan`() {
        compose.setContent { ScanStatus(progress = null, onScan = {}, indexEmpty = true) }

        compose.onNodeWithText("Last scan", substring = true).assertDoesNotExist()
    }
}
