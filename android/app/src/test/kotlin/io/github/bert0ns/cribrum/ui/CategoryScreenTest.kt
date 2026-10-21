package io.github.bert0ns.cribrum.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.review.CategorySummary
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
    fun `category list says how much space the candidates can free`() {
        val categories = listOf(CategorySummary(Category.BLURRY, 3, hasDetector = true, bytes = 3_000_000))
        compose.setContent {
            CategoryList(categories = categories, onOpen = {}, onTrash = {})
        }

        compose.onNodeWithText("3 images to review · can free 3.0 MB").assertExists()
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

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
}
