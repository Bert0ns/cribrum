package io.github.bert0ns.cribrum.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.bert0ns.cribrum.pipeline.ScanProgress
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

        compose.onNodeWithText("detectors: 12 of 100").assertExists()
        compose.onNodeWithText(empty).assertDoesNotExist()
    }
}
