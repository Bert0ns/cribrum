package io.github.bert0ns.cribrum.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StorageMapScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val tree = buildFolderTree(
        listOf(
            "/storage/emulated/0/DCIM/Camera/IMG_001.jpg" to 10_000_000L,
            "/storage/emulated/0/Pictures/vacation.jpg" to 5_000_000L,
        ),
    )

    @Test
    fun `storage map displays subfolders and fires zoom callback on tap`() {
        var zoomed: Folder? = null
        compose.setContent {
            StorageMapScreen(
                currentFolder = tree,
                onZoomIn = { zoomed = it },
            )
        }

        compose.onNodeWithText("DCIM").assertIsDisplayed()
        compose.onNodeWithText("Pictures").assertIsDisplayed()

        compose.onNodeWithText("DCIM").performClick()
        assertEquals("DCIM", zoomed?.name)
    }

    @Test
    fun `storage map shows empty message when there are no images`() {
        compose.setContent {
            StorageMapScreen(
                currentFolder = Folder(name = "", path = "", size = 0L, imageCount = 0),
                onZoomIn = {},
            )
        }

        compose.onNodeWithText("No images indexed").assertIsDisplayed()
    }

    @Test
    fun `settings screen provides entry to storage map`() {
        var opened = false
        compose.setContent {
            SettingsScreen(onOpenStorageMap = { opened = true })
        }

        compose.onNodeWithText("Storage map").assertIsDisplayed().performClick()
        assertEquals(true, opened)
    }
}
