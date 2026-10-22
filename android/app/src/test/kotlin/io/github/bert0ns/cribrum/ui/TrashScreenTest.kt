package io.github.bert0ns.cribrum.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.trash.TrashedImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Compose tests reach InputManager.getInstance, which SDK 37 removed
class TrashScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val trashed = listOf("a.jpg", "b.jpg").map {
        val trashedAt = Instant.now() - Duration.ofDays(3)
        TrashedImage(ImageRef("/sdcard/DCIM/$it", 1, 0), trashedAt, File("/sdcard/.cribrum-trash/x/$it"), trashedAt + Duration.ofDays(30))
    }

    @Test
    fun `an image is restored in two taps`() {
        var restored = emptySet<String>()
        compose.setContent {
            var selected by remember { mutableStateOf(emptySet<String>()) }
            TrashScreen(trashed, selected, onSelect = { selected = selected + it.original.path }, onSelectAll = { _, _ -> }, onRestore = { restored = selected }, onEmpty = {})
        }

        compose.onNodeWithText("Restore 0").assertIsNotEnabled()
        compose.onNodeWithContentDescription("a.jpg").performClick()
        compose.onNodeWithText("Restore 1").performClick()

        assertEquals(setOf("/sdcard/DCIM/a.jpg"), restored)
    }

    @Test
    fun `a screen reader hears which images are selected`() {
        compose.setContent {
            var selected by remember { mutableStateOf(emptySet<String>()) }
            TrashScreen(trashed, selected, onSelect = { selected = selected + it.original.path }, onSelectAll = { _, _ -> }, onRestore = {}, onEmpty = {})
        }

        compose.onNodeWithContentDescription("a.jpg").assertIsNotSelected()
        compose.onNodeWithContentDescription("a.jpg").performClick()
        compose.onNodeWithContentDescription("a.jpg").assertIsSelected()
        compose.onNodeWithContentDescription("b.jpg").assertIsNotSelected()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and hasContentDescription("a.jpg")).assertExists()
    }

    @Test
    fun `each image shows the days before it is purged`() {
        compose.setContent { TrashScreen(trashed, emptySet(), {}, { _, _ -> }, {}, {}) }

        assertEquals(2, compose.onAllNodesWithText("26d left", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `emptying the trash asks first`() {
        var emptied = 0
        compose.setContent { TrashScreen(trashed, emptySet(), {}, { _, _ -> }, {}, onEmpty = { emptied++ }) }

        compose.onNodeWithText("Empty trash").performClick()
        assertEquals(0, emptied)
        compose.onNodeWithText("Delete 2 images for good?").assertExists()
        compose.onNodeWithText("Delete").performClick()

        assertEquals(1, emptied)
    }

    @Test
    fun `stickers have their own section, selected all at once`() {
        val sticker = TrashedImage(
            ImageRef("/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers/s.webp", 1, 0),
            Instant.now(),
            File("/sdcard/.cribrum-trash/y/s.webp"),
            Instant.now() + Duration.ofDays(30),
        )
        var chosen: List<TrashedImage> = emptyList()
        compose.setContent { TrashScreen(trashed + sticker, emptySet(), {}, { items, _ -> chosen = items }, {}, {}) }

        compose.onNodeWithText("2 images").assertExists()
        compose.onNodeWithText("1 WhatsApp sticker").assertExists()
        compose.onAllNodesWithText("Select all")[1].performClick()

        assertEquals(listOf(sticker), chosen)
    }
}
