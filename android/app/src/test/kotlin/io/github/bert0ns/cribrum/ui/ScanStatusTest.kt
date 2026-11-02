package io.github.bert0ns.cribrum.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import io.github.bert0ns.cribrum.pipeline.ScanProgress
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Compose tests reach InputManager.getInstance, which SDK 37 removed
class ScanStatusTest {
    @get:Rule
    val compose = createComposeRule()

    private val empty = "No images indexed yet. Tap Scan now to start the first scan."

    @Test
    fun `an empty index points to Scan now, which starts a scan`() {
        var scans = 0
        compose.setContent { ScanStatus(progress = null, onScan = { scans++ }, indexEmpty = true) }

        compose.onNodeWithText(empty).assertExists()
        compose.onNodeWithText("Scan now").performClick()
        assertTrue(scans == 1)
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
    fun `a running scan says which phase it is in out of the total`() {
        compose.setContent { ScanStatus(ScanProgress("detectors", 12, 100), onScan = {}) }

        compose.onNodeWithText("Phase 3 of 6").assertExists()
    }

    @Test
    fun `the phase number shows while the scan looks for images too`() {
        compose.setContent { ScanStatus(ScanProgress("scan", 300, 0), onScan = {}) }

        compose.onNodeWithText("Phase 1 of 6").assertExists()
    }

    @Test
    fun `a scan that has reported nothing yet is in the first phase`() {
        compose.setContent { ScanStatus(ScanProgress("", 0, 0), onScan = {}) }

        compose.onNodeWithText("Phase 1 of 6").assertExists()
    }

    @Test
    fun `the phase number keeps its own line beside the time left`() {
        compose.setContent { ScanStatus(ScanProgress("detectors", 12, 100, leftMs = 12 * 60_000L), onScan = {}) }

        compose.onNodeWithText("Phase 3 of 6").assertExists()
    }

    @Test
    fun `the time left shows rounded to the minute`() {
        compose.setContent { ScanStatus(ScanProgress("detectors", 12, 100, leftMs = 12 * 60_000L + 29_000), onScan = {}) }

        compose.onNodeWithText("About 12 min left in this step").assertExists()
    }

    @Test
    fun `a time left over an hour shows in hours and minutes`() {
        compose.setContent { ScanStatus(ScanProgress("embeddings", 12, 100, leftMs = 95 * 60_000L), onScan = {}) }

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
    fun `the heat shows at the end of the phase line while the scan walks`() {
        compose.setContent { ScanStatus(ScanProgress("scan", 300, 0), onScan = {}, heat = Heat(HeatLevel.MODERATE, 12)) }

        compose.onNodeWithText("moderate · 12%").assertExists()
        compose.onNodeWithContentDescription("Heat").assertExists()
        compose.onNodeWithText("Phase 1 of 6").assertExists()
    }

    @Test
    fun `the heat shows at the end of the phase line in a normal scan`() {
        compose.setContent { ScanStatus(ScanProgress("detectors", 12, 100), onScan = {}, heat = Heat(HeatLevel.LIGHT, 20)) }

        compose.onNodeWithText("light · 20%").assertExists()
        compose.onNodeWithContentDescription("Heat").assertExists()
        compose.onNodeWithText("Phase 3 of 6").assertExists()
    }

    @Test
    fun `with no headroom the heat shows the level alone`() {
        compose.setContent { ScanStatus(ScanProgress("detectors", 12, 100), onScan = {}, heat = Heat(HeatLevel.SEVERE, null)) }

        compose.onNodeWithText("severe").assertExists()
        compose.onNodeWithContentDescription("Heat").assertExists()
    }

    @Test
    fun `with no reading no heat shows`() {
        compose.setContent { ScanStatus(ScanProgress("detectors", 12, 100), onScan = {}, heat = null) }

        compose.onNodeWithContentDescription("Heat").assertDoesNotExist()
        compose.onNodeWithText("moderate", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `the heat is right-aligned on the same line when space permits`() {
        compose.setContent { ScanStatus(ScanProgress("scan", 300, 0), onScan = {}, heat = Heat(HeatLevel.MODERATE, 12)) }

        val phaseBounds = compose.onNodeWithText("Phase 1 of 6").fetchSemanticsNode().boundsInRoot
        val heatBounds = compose.onNodeWithText("moderate · 12%").fetchSemanticsNode().boundsInRoot
        assertTrue("Heat should be to the right of phase", heatBounds.left > phaseBounds.right)
        assertTrue("Heat should be on the same horizontal line", heatBounds.top < phaseBounds.bottom && heatBounds.bottom > phaseBounds.top)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w240dp-h640dp")
    fun `the heat moves to its own line on narrow screens`() {
        compose.setContent {
            ScanStatus(ScanProgress("scan", 300, 0), onScan = {}, heat = Heat(HeatLevel.MODERATE, 12))
        }

        val phaseBounds = compose.onNodeWithText("Phase 1 of 6").fetchSemanticsNode().boundsInRoot
        val heatBounds = compose.onNodeWithText("moderate · 12%").fetchSemanticsNode().boundsInRoot
        assertTrue("Heat should be below the phase line", heatBounds.top >= phaseBounds.bottom)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the heat moves to its own line at large font scale`() {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale = 2f),
            ) {
                ScanStatus(ScanProgress("scan", 300, 0), onScan = {}, heat = Heat(HeatLevel.MODERATE, 12))
            }
        }

        val phaseBounds = compose.onNodeWithText("Phase 1 of 6").fetchSemanticsNode().boundsInRoot
        val heatBounds = compose.onNodeWithText("moderate · 12%").fetchSemanticsNode().boundsInRoot
        assertTrue("Heat should be below the phase line at 2x font scale", heatBounds.top >= phaseBounds.bottom)
    }

    @Test
    @Config(qualifiers = "it")
    fun `the heat shows in Italian with thermometer icon`() {
        compose.setContent { ScanStatus(ScanProgress("scan", 300, 0), onScan = {}, heat = Heat(HeatLevel.MODERATE, 12)) }

        compose.onNodeWithText("moderato · 12%").assertExists()
        compose.onNodeWithContentDescription("Calore").assertExists()
        compose.onNodeWithText("Fase 1 di 6").assertExists()
    }
}
