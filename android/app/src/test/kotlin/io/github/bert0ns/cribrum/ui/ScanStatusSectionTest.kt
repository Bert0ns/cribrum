package io.github.bert0ns.cribrum.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.bert0ns.cribrum.pipeline.Coverage
import io.github.bert0ns.cribrum.pipeline.FailedFile
import io.github.bert0ns.cribrum.pipeline.ScanReport
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScanStatusSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val coverage = listOf(Coverage("embedding", 1, analysed = 90, needed = 100), Coverage("blur", 1, analysed = 120, needed = 120))

    private fun show(coverage: List<Coverage>?, lastScan: ScanReport?) = compose.setContent { SettingsScreen(coverage = coverage, lastScan = lastScan) }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    fun `each step shows the images analysed out of the images that need it, and its version`() {
        show(coverage, null)

        scrollTo("Embeddings")
        scrollTo("Blurry")
        scrollTo("90 of 100 images analysed • version 1")
        scrollTo("120 of 120 images analysed • version 1")
    }

    @Test
    fun `before a scan finishes it says so`() {
        show(coverage, null)

        scrollTo("No scan has finished since this version.")
    }

    @Test
    fun `the last scan shows its unreadable files and its failures by step, and the file names on a tap`() {
        val report = ScanReport(1_700_000_000_000, unreadable = 3, failures = mapOf("embedding" to 2), files = listOf(FailedFile("embedding", "a.jpg"), FailedFile("embedding", "b.jpg")))
        show(coverage, report)

        scrollTo("3 unreadable files")
        scrollTo("2 failed in the last scan")
        compose.onNodeWithText("2 failures in the last scan").assertHasClickAction()
        compose.onNode(hasText("2 failures in the last scan") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertExists()
        compose.onNode(hasText("2 failures in the last scan") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "File names hidden")).assertExists()
        compose.onNodeWithText("2 failures in the last scan").performClick()
        compose.onNode(hasText("2 failures in the last scan") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "File names shown")).assertExists()
        scrollTo("embedding: a.jpg")
        scrollTo("embedding: b.jpg")
    }

    @Test
    fun `a clean scan says there were no failures`() {
        show(coverage, ScanReport(1_700_000_000_000, unreadable = 1, failures = emptyMap(), files = emptyList()))

        scrollTo("1 unreadable file")
        scrollTo("No failures in the last scan")
    }

    @Test
    fun `when the last scan attempt failed it displays the failure message`() {
        compose.setContent { SettingsScreen(coverage = coverage, lastScan = null, lastScanFailed = true) }

        scrollTo("The last scan attempt failed.")
    }
}
