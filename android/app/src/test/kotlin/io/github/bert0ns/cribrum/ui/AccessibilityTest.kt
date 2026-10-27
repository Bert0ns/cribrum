package io.github.bert0ns.cribrum.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import io.github.bert0ns.cribrum.review.CategorySummary
import io.github.bert0ns.cribrum.review.ReviewGroup
import io.github.bert0ns.cribrum.review.ReviewItem
import io.github.bert0ns.cribrum.review.ReviewOrder
import io.github.bert0ns.cribrum.trash.TrashedImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant

/** Touch targets and large font scale (docs/design.md §8). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp") // a phone's width, so the font scale has something to break
class AccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private val images = (1..3).map { ImageRef("/sdcard/DCIM/img$it.jpg", 1_000_000, 0) }
    private val page = images.map { ReviewGroup(it.path, listOf(ReviewItem(it, suggestTrash = true))) }
    private val marked = images.map { it.path }.toSet()

    /**
     * Every control that takes a tap or a drag is at least 48 dp wide and high, with the space a
     * finger gets around it. A control cut by the edge of the screen is measured once scrolled in.
     * A slider is only checked for width: Material makes its handle 44 dp high.
     */
    private fun assertTouchTargets(screen: String) {
        val minimum = with(compose.density) { 48.dp.toPx() } - 1f // layout rounds to whole pixels
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val controls = compose.onAllNodes(hasClickAction() or SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).fetchSemanticsNodes()
            .filter { it.touchBoundsInRoot.top > root.top && it.touchBoundsInRoot.bottom < root.bottom }
        assertTrue("$screen has no control", controls.isNotEmpty())
        for (control in controls) {
            val bounds = control.touchBoundsInRoot
            val slider = SemanticsActions.SetProgress in control.config
            assertTrue(
                "$screen: ${control.config.getOrNull(SemanticsProperties.Text)} ${control.config.getOrNull(SemanticsProperties.ContentDescription)} is ${bounds.width} x ${bounds.height} px, under 48 dp",
                bounds.width >= minimum && (slider || bounds.height >= minimum),
            )
        }
    }

    @Test
    fun `the home screen has targets of 48 dp`() {
        compose.setContent {
            CategoryList(listOf(CategorySummary(Category.BLURRY, 3, bytes = 3_000_000), CategorySummary(Category.DOCUMENT, 0)), 3_000_000, {}, {})
        }

        assertTouchTargets("home")
    }

    @Test
    fun `the category screen has targets of 48 dp`() {
        compose.setContent { CategoryScreen(page, 3, marked, false, {}, { _, _ -> }, {}) }

        assertTouchTargets("category")
    }

    @Test
    fun `the date menu has a target of 48 dp`() {
        compose.setContent { Box(Modifier.padding(16.dp)) { ReviewOrderMenu(ReviewOrder.OFF, ReviewOrder.entries) {} } }

        assertTouchTargets("date menu")
    }

    @Test
    fun `settings has targets of 48 dp`() {
        compose.setContent { SettingsScreen(excludedFolders = listOf("/sdcard/Download"), embeddingCount = 3) }

        assertTouchTargets("settings")
        for (item in listOf("Detection thresholds", "Add folder", "Delete embeddings")) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(item))
            assertTouchTargets("settings, at $item")
        }
    }

    @Test
    fun `the thresholds screen has targets of 48 dp`() {
        compose.setContent { ThresholdsScreen(THRESHOLDS, emptyMap(), emptyMap(), { _, _ -> }, {}) }

        assertTouchTargets("thresholds")
    }

    @Test
    fun `the trash has targets of 48 dp`() {
        val at = Instant.now() - Duration.ofDays(3)
        val trashed = listOf(TrashedImage(images[0], at, File("/sdcard/.cribrum-trash/x/img1.jpg"), at + Duration.ofDays(30)))
        compose.setContent { TrashScreen(trashed, emptySet(), {}, { _, _ -> }, {}, {}) }

        assertTouchTargets("trash")
    }

    @Test
    fun `the category screen still works at twice the font size`() {
        RuntimeEnvironment.setFontScale(2f)
        var applied = 0
        compose.setContent { CategoryScreen(page, 3, marked, false, {}, { _, _ -> applied++ }, {}) }

        // Both buttons stay on the screen, side by side, and nothing is hidden.
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Skip").assertIsDisplayed()
        val trash = compose.onNodeWithText("Trash 3, keep 0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val skip = compose.onNodeWithText("Skip").fetchSemanticsNode().boundsInRoot
        assertTrue("$skip right of $trash", skip.left >= trash.right)
        assertTrue("$skip inside the screen", skip.right <= screen.right)
        // The hint and the images are there, and the trash button still works.
        compose.onNodeWithText("Marked images go to the trash", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("img1.jpg", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Trash 3, keep 0").performClick()
        compose.onNodeWithText("Move 3 images to the trash?").assertIsDisplayed()
        assertEquals(0, applied)
    }

    @Test
    fun `the home screen still works at twice the font size`() {
        RuntimeEnvironment.setFontScale(2f)
        var opened: Category? = null
        var trash = 0
        compose.setContent {
            CategoryList(listOf(CategorySummary(Category.BLURRY, 3, bytes = 3_000_000)), 3_000_000, { opened = it }, { trash++ })
        }

        compose.onNodeWithText("Blurry").assertIsDisplayed().performClick()
        assertEquals(Category.BLURRY, opened)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Trash"))
        compose.onNodeWithText("Trash").assertIsDisplayed().performClick()
        assertEquals(1, trash)
    }

    @Test
    fun `settings still works at twice the font size`() {
        RuntimeEnvironment.setFontScale(2f)
        var thresholds = 0
        compose.setContent { SettingsScreen(onOpenThresholds = { thresholds++ }, excludedFolders = listOf("/sdcard/Download"), embeddingCount = 3) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Detection thresholds"))
        compose.onNodeWithText("Detection thresholds").assertIsDisplayed().performClick()
        assertEquals(1, thresholds)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Delete embeddings"))
        compose.onNodeWithText("Delete embeddings").assertIsDisplayed().performClick()
        compose.onNodeWithText("Delete the embeddings?").assertIsDisplayed()
    }

    @Test
    fun `the thresholds screen still works at twice the font size`() {
        RuntimeEnvironment.setFontScale(2f)
        var reset = 0
        compose.setContent { ThresholdsScreen(THRESHOLDS, emptyMap(), emptyMap(), { _, _ -> }, { reset++ }) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Reset to defaults"))
        compose.onNodeWithText("Reset to defaults").assertIsDisplayed().performClick()
        assertEquals(1, reset)
    }

    @Test
    fun `the trash still works at twice the font size`() {
        RuntimeEnvironment.setFontScale(2f)
        val at = Instant.now() - Duration.ofDays(3)
        val trashed = listOf(TrashedImage(images[0], at, File("/sdcard/.cribrum-trash/x/img1.jpg"), at + Duration.ofDays(30)))
        var restored = 0
        compose.setContent { TrashScreen(trashed, trashed.map { it.original.path }.toSet(), {}, { _, _ -> }, { restored++ }, {}) }

        // Both buttons stay on the screen, side by side, and nothing is hidden.
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val restore = compose.onNodeWithText("Restore 1").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val empty = compose.onNodeWithText("Empty trash").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("$empty right of $restore", empty.left >= restore.right)
        assertTrue("$empty inside the screen", empty.right <= screen.right)
        compose.onNodeWithContentDescription("img1.jpg", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Restore 1").performClick()
        assertEquals(1, restored)
        compose.onNodeWithText("Empty trash").performClick()
        compose.onNodeWithText("Delete 1 image for good?").assertIsDisplayed()
    }
}
