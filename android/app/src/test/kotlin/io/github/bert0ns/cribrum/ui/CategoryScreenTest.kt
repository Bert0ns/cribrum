package io.github.bert0ns.cribrum.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.review.CategorySummary
import io.github.bert0ns.cribrum.review.DateGrouping
import io.github.bert0ns.cribrum.review.ReviewGroup
import io.github.bert0ns.cribrum.review.ReviewItem
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Compose tests reach InputManager.getInstance, which SDK 37 removed
class CategoryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val images = (1..3).map { ImageRef("/sdcard/DCIM/img$it.jpg", 1_000_000, 0) }
    private val page = images.map { ReviewGroup(it.path, listOf(ReviewItem(it, suggestTrash = true))) }

    @Test
    fun `category list shows candidate count and opens on tap`() {
        var opened: Category? = null
        val categories = listOf(CategorySummary(Category.BLURRY, 3, hasDetector = true, bytes = 3_000_000))
        compose.setContent {
            CategoryList(categories = categories, onOpen = { opened = it }, onTrash = {})
        }

        compose.onNodeWithText("Blurry").assertExists()
        compose.onNodeWithText("3 images to review · can free 3.0 MB").assertExists()
        compose.onNodeWithText("Blurry").assertIsEnabled().performClick()
        assertEquals(Category.BLURRY, opened)
    }

    @Test
    fun `category list says what the candidates can free in all, and nothing when there are none`() {
        var freeable by mutableStateOf(2_000_000L)
        compose.setContent {
            CategoryList(categories = listOf(CategorySummary(Category.BLURRY, 3, bytes = 3_000_000)), freeableBytes = freeable, onOpen = {}, onTrash = {})
        }

        compose.onNodeWithText("Up to 2.0 MB can be freed").assertExists()

        freeable = 0
        compose.onNodeWithText("Up to 2.0 MB can be freed").assertDoesNotExist()
        compose.onAllNodesWithText("Up to", substring = true).assertCountEquals(0)
    }

    @Test
    fun `category list shows no space for a category without candidates`() {
        val categories = listOf(CategorySummary(Category.BLURRY, 0, hasDetector = true))
        compose.setContent {
            CategoryList(categories = categories, onOpen = {}, onTrash = {})
        }

        compose.onNodeWithText("can free", substring = true).assertDoesNotExist()
    }

    @Test
    fun `empty category shows zero images and opens nothing`() {
        var opened: Category? = null
        val categories = listOf(CategorySummary(Category.BLURRY, 0, hasDetector = true))
        compose.setContent {
            CategoryList(categories = categories, onOpen = { opened = it }, onTrash = {})
        }

        compose.onNodeWithText("Blurry").assertExists()
        compose.onNodeWithText("0 images to review").assertExists()
        compose.onNodeWithText("Blurry").assertIsNotEnabled()
        assertNull(opened)
    }

    @Test
    fun `category without detector shows coming soon and opens nothing`() {
        var opened: Category? = null
        val categories = listOf(CategorySummary(Category.NEAR_DUPLICATE, 0, hasDetector = false))
        compose.setContent {
            CategoryList(categories = categories, onOpen = { opened = it }, onTrash = {})
        }

        compose.onNodeWithText("Similar copies").assertExists()
        compose.onNodeWithText("Coming soon").assertExists()
        compose.onNodeWithText("Similar copies").assertIsNotEnabled()
        assertNull(opened)
    }

    @Test
    fun `every candidate starts marked, a tap keeps it, and nothing moves before the summary is confirmed`() {
        var applied = 0
        compose.setContent {
            var marked by remember { mutableStateOf(images.map { it.path }.toSet()) }
            CategoryScreen(page, 100, marked, grouped = false, onToggle = { image ->
                marked = if (image.path in marked) marked - image.path else marked + image.path
            }, onApply = { applied++ }, onSkip = {})
        }

        compose.onNodeWithText("3 of 100 images to review. Confirm these to see the next ones.").assertExists()
        compose.onNodeWithText("Trash 3, keep 0").assertExists()
        compose.onAllNodesWithContentDescription("img1.jpg")[0].performClick()
        compose.onNodeWithText("Trash 2, keep 1").performClick()

        compose.onNodeWithText("Move 2 images to the trash?").assertExists()
        assertEquals(0, applied)
        compose.onNodeWithText("Move to trash").performClick()
        assertEquals(1, applied)
    }

    @Test
    fun `skip sits right of the trash button and skips without a summary`() {
        var skipped = emptySet<String>()
        var applied = 0
        compose.setContent {
            CategoryScreen(page, 3, images.map { it.path }.toSet(), false, {}, { applied++ }, onSkip = { skipped = it })
        }

        val trash = compose.onNodeWithText("Trash 3, keep 0").fetchSemanticsNode().boundsInRoot
        val skip = compose.onNodeWithText("Skip").fetchSemanticsNode().boundsInRoot
        assertTrue("$skip right of $trash", skip.left >= trash.right)
        compose.onNodeWithText("Skip").performClick()

        assertEquals(images.map { it.path }.toSet(), skipped)
        assertEquals(0, applied)
        compose.onNodeWithText("Move 3 images to the trash?").assertDoesNotExist()
    }

    @Test
    fun `an empty page says there is nothing more and offers no buttons`() {
        compose.setContent { CategoryScreen(emptyList(), 0, emptySet(), false, {}, {}, {}) }

        compose.onNodeWithText("No more images to review. Skipped ones come back when you open this category again.").assertExists()
        compose.onNodeWithText("Skip").assertDoesNotExist()
    }

    @Test
    fun `holding an image offers to never suggest its folder again`() {
        var excluded: ImageRef? = null
        compose.setContent {
            CategoryScreen(page, 3, emptySet(), false, {}, {}, {}, onExcludeFolder = { excluded = it })
        }

        compose.onAllNodesWithContentDescription("img2.jpg")[0].performTouchInput { longClick() }
        compose.onNodeWithText("Never suggest this folder or its subfolders").performClick()

        assertEquals(images[1], excluded)
        compose.onNodeWithText("Never suggest this folder or its subfolders").assertDoesNotExist()
    }

    private fun dated(grouping: DateGrouping, vararg days: LocalDate) = images.zip(days.toList()).map { (image, day) ->
        ReviewGroup(image.path, listOf(ReviewItem(image, suggestTrash = true)), date = if (grouping == DateGrouping.OFF) null else grouping.start(day, DayOfWeek.MONDAY))
    }

    @Test
    fun `a header marks where each date starts and nothing is shown when the grouping is off`() {
        val days = arrayOf(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 2), LocalDate.of(2026, 2, 28))
        var grouping by mutableStateOf(DateGrouping.DAY)
        compose.setContent { CategoryScreen(dated(grouping, *days), 3, emptySet(), false, {}, {}, {}, dateGrouping = grouping) }

        compose.onNodeWithText("Mar 2, 2026").assertExists()
        compose.onNodeWithText("Feb 28, 2026").assertExists()
        compose.onAllNodesWithText("Mar 2, 2026").assertCountEquals(1)

        grouping = DateGrouping.OFF
        compose.onNodeWithText("Mar 2, 2026").assertDoesNotExist()
    }

    @Test
    fun `changing the grouping scrolls the grid back to its top`() {
        val many = (1..80).map { ImageRef("/sdcard/DCIM/many$it.jpg", 1_000, 0) }
        val long = many.map { ReviewGroup(it.path, listOf(ReviewItem(it, suggestTrash = true))) }
        var grouping by mutableStateOf(DateGrouping.OFF)
        compose.setContent { CategoryScreen(long, 80, emptySet(), false, {}, {}, {}, dateGrouping = grouping) }

        compose.onNode(hasScrollAction()).performScrollToIndex(60)
        compose.onAllNodesWithContentDescription("many1.jpg").assertCountEquals(0)

        grouping = DateGrouping.DAY
        compose.waitForIdle()

        compose.onAllNodesWithContentDescription("many1.jpg").assertCountEquals(1)
    }

    @Test
    fun `the date control offers off, day, week, month and size and reports the choice`() {
        var chosen: DateGrouping? = null
        compose.setContent { DateGroupingMenu(DateGrouping.DAY) { chosen = it } }

        compose.onNodeWithText("Week").assertDoesNotExist()
        compose.onNodeWithContentDescription("Group by date or sort by size").performClick()
        listOf("Off", "Day", "Week", "Month", "Largest first").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Week").performClick()

        assertEquals(DateGrouping.WEEK, chosen)
        compose.onNodeWithText("Week").assertDoesNotExist()
    }

    @Test
    fun `the control of a category without dates offers off and size only`() {
        compose.setContent { DateGroupingMenu(DateGrouping.OFF, DateGrouping.optionsFor(Category.STICKER)) {} }

        compose.onNodeWithContentDescription("Group by date or sort by size").performClick()

        listOf("Off", "Largest first").forEach { compose.onNodeWithText(it).assertExists() }
        listOf("Day", "Week", "Month").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    private fun openFullSize(name: String) = compose.onAllNodesWithContentDescription(name)[0].performTouchInput { longClick() }

    @Test
    fun `swiping the full size view pages through the images of the list`() {
        compose.setContent { CategoryScreen(page, 3, emptySet(), false, {}, {}, {}) }

        openFullSize("img1.jpg")
        compose.onNodeWithText(images[0].path).assertExists()
        compose.onNodeWithTag("fullSize").performTouchInput { swipeLeft() }
        compose.onNodeWithText(images[1].path).assertExists()
        compose.onNodeWithTag("fullSize").performTouchInput { swipeRight() }
        compose.onNodeWithText(images[0].path).assertExists()
    }

    @Test
    fun `the full size chip follows the mark, including the user's taps`() {
        val best = ReviewGroup("g", listOf(ReviewItem(images[0], suggestTrash = false, isBest = true), ReviewItem(images[1], suggestTrash = true)))
        compose.setContent {
            var marked by remember { mutableStateOf(setOf(images[1].path)) }
            CategoryScreen(listOf(best), 1, marked, true, { image ->
                marked = if (image.path in marked) marked - image.path else marked + image.path
            }, {}, {})
        }

        openFullSize("img1.jpg")
        compose.onNodeWithText("Best copy, kept").assertExists()
        compose.onNodeWithTag("fullSize").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Marked for the trash").assertExists()
        compose.onNodeWithTag("fullSize").performTouchInput { click() }
        compose.onAllNodesWithContentDescription("img2.jpg")[0].performClick()
        openFullSize("img2.jpg")
        compose.onNodeWithText("Kept").assertExists()
    }

    @Test
    fun `the full size view says why the image is a candidate, strongest reason first`() {
        val dark = Signal("exposure", 1, Category.BAD_EXPOSURE, 1f, mapOf("case" to "dark"))
        val blurry = Signal("blur", 1, Category.BLURRY, 0.9f)
        val group = ReviewGroup("g", listOf(ReviewItem(images[0], suggestTrash = true, reasons = listOf(dark, blurry)), ReviewItem(images[1], suggestTrash = true)))
        compose.setContent { CategoryScreen(listOf(group), 2, emptySet(), false, {}, {}, {}) }

        openFullSize("img1.jpg")
        compose.onNodeWithText("Too dark").assertExists()
        compose.onNodeWithText("Blurry, the subject is out of focus").assertExists()
        compose.onNodeWithTag("fullSize").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Too dark").assertDoesNotExist()
    }

    @Test
    fun `excluding a folder from the full size view excludes the image on show and closes the view`() {
        var excluded: ImageRef? = null
        compose.setContent { CategoryScreen(page, 3, emptySet(), false, {}, {}, {}, onExcludeFolder = { excluded = it }) }
        val exclude = "Never suggest this folder or its subfolders"

        openFullSize("img1.jpg")
        compose.onNodeWithTag("fullSize").performTouchInput { swipeLeft() }
        compose.onNodeWithText(exclude).performClick()

        assertEquals(images[1], excluded)
        compose.onNodeWithText(exclude).assertDoesNotExist()
    }

    @Test
    fun `a drag on a zoomed image pans instead of changing page`() {
        compose.setContent { CategoryScreen(page, 3, emptySet(), false, {}, {}, {}) }

        openFullSize("img1.jpg")
        compose.onNodeWithTag("fullSize").performTouchInput {
            pinch(center - Offset(50f, 0f), center - Offset(150f, 0f), center + Offset(50f, 0f), center + Offset(150f, 0f))
        }
        compose.onNodeWithTag("fullSize").performTouchInput { swipeLeft() }

        compose.onNodeWithText(images[0].path).assertExists()
    }

    @Test
    fun `a group with a copy in an excluded folder says so`() {
        val group = ReviewGroup("g", listOf(ReviewItem(images[0], suggestTrash = true)), hasExcludedCopy = true)
        compose.setContent { CategoryScreen(listOf(group), 1, emptySet(), grouped = true, {}, {}, {}) }

        compose.onNodeWithText("Another copy is in an excluded folder").assertExists()
    }

    @Test
    fun `cancelling the summary trashes nothing`() {
        var applied = 0
        compose.setContent { CategoryScreen(page, 3, images.map { it.path }.toSet(), false, {}, { applied++ }, {}) }

        compose.onNodeWithText("The last 3 images to review.").assertExists()
        compose.onNodeWithText("Trash 3, keep 0").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(0, applied)
    }

    @Test
    fun `the undo snackbar shows above the trash button, not over it`() {
        val snackbar = SnackbarHostState()
        compose.setContent { CategoryScreen(page, 3, images.map { it.path }.toSet(), false, {}, {}, {}, snackbar = snackbar) }

        compose.runOnIdle { scope.launch { snackbar.showSnackbar("3 images moved to the trash", "Undo") } }

        val message = compose.onNodeWithText("3 images moved to the trash").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText("Trash 3, keep 0").fetchSemanticsNode().boundsInRoot
        assertTrue("$message over $button", message.bottom <= button.top)
        compose.onNodeWithText("Trash 3, keep 0").performClick()
        compose.onNodeWithText("Move 3 images to the trash?").assertExists()
    }

    private fun haptics(page: List<ReviewGroup>, marked: Set<String>, act: () -> Unit): List<HapticFeedbackType> {
        val feedback = RecordingHaptics()
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                var now by remember { mutableStateOf(marked) }
                CategoryScreen(page, 3, now, false, { image ->
                    now = if (image.path in now) now - image.path else now + image.path
                }, {}, {}, onExcludeFolder = {})
            }
        }
        act()
        return feedback.types
    }

    @Test
    fun `selecting and unselecting an image gives a tick, and nothing else`() {
        val given = haptics(page, emptySet()) {
            compose.onAllNodesWithContentDescription("img1.jpg")[0].performClick()
            compose.onAllNodesWithContentDescription("img1.jpg")[0].performClick()
        }

        assertEquals(listOf(Haptic.TICK.type, Haptic.TICK.type), given)
    }

    @Test
    fun `skipping gives a tick`() {
        assertEquals(listOf(Haptic.TICK.type), haptics(page, emptySet()) { compose.onNodeWithText("Skip").performClick() })
    }

    @Test
    fun `opening the summary gives nothing, and trashing is a strong pulse`() {
        val given = haptics(page, images.map { it.path }.toSet()) {
            compose.onNodeWithText("Trash 3, keep 0").performClick()
            compose.onNodeWithText("Move to trash").performClick()
        }

        assertEquals(listOf(Haptic.TRASH.type), given)
    }

    @Test
    fun `confirming a page with nothing to trash is a lighter confirm`() {
        val given = haptics(page, emptySet()) {
            compose.onNodeWithText("Trash 0, keep 3").performClick()
            compose.onNodeWithText("Move to trash").performClick()
        }

        assertEquals(listOf(Haptic.KEEP.type), given)
    }

    @Test
    fun `excluding a folder is a lighter confirm`() {
        val given = haptics(page, emptySet()) {
            compose.onAllNodesWithContentDescription("img2.jpg")[0].performTouchInput { longClick() }
            compose.onNodeWithText("Never suggest this folder or its subfolders").performClick()
        }

        // The long press that opens the image is the platform's own.
        assertEquals(listOf(HapticFeedbackType.LongPress, Haptic.KEEP.type), given)
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
}
