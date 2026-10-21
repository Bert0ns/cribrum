package io.github.bert0ns.cribrum.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.bert0ns.cribrum.App
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
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

    private val app = ApplicationProvider.getApplicationContext<CribrumApp>()

    private val tree = buildFolderTree(
        listOf(
            ImageRef("/storage/emulated/0/DCIM/Camera/IMG_001.jpg", 10_000_000L, 0L),
            ImageRef("/storage/emulated/0/Pictures/vacation.jpg", 5_000_000L, 0L),
        ),
    )

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

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
    fun `tapping the map twice zooms into the folder once`() {
        val zoomed = mutableListOf<Folder>()
        compose.setContent { StorageMapScreen(currentFolder = tree, onZoomIn = { zoomed += it }) }

        compose.onNodeWithTag("storage_map").performTouchInput { click(Offset(width / 2f, 20f)) }
        assertEquals(emptyList<Folder>(), zoomed)
        compose.onNodeWithTag("storage_map").performTouchInput { click(Offset(width / 2f, 20f)) }
        assertEquals(listOf("DCIM"), zoomed.map { it.name })
    }

    @Test
    fun `tapping the map of a folder without subfolders does not zoom`() {
        val leaf = tree.children.first().children.single()
        val zoomed = mutableListOf<Folder>()
        compose.setContent { StorageMapScreen(currentFolder = leaf, onZoomIn = { zoomed += it }) }

        repeat(2) { compose.onNodeWithTag("storage_map").performTouchInput { click(center) } }
        assertEquals(emptyList<Folder>(), zoomed)
    }

    @Test
    fun `images of size zero are not shown as an empty index`() {
        val zeros = buildFolderTree(listOf(ImageRef("/a/1.jpg", 0L, 0L), ImageRef("/a/2.jpg", 0L, 0L)))
        compose.setContent { StorageMapScreen(currentFolder = zeros, onZoomIn = {}) }

        compose.onNodeWithText("No images indexed").assertDoesNotExist()
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

    /** Navigation runs on the view model scope, so the screen changes after the click returns. */
    private fun awaitText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun openStorageMapFromSettings() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Storage map"))
        compose.onNodeWithText("Storage map").assertIsDisplayed().performClick()
        awaitText("DCIM")
    }

    @Test
    fun `storage map opens from settings and displays indexed folders`() = runBlocking<Unit> {
        app.index.record(ImageRef("/storage/emulated/0/DCIM/Camera/IMG_001.jpg", 10_000_000L, 1L))
        app.index.record(ImageRef("/storage/emulated/0/Pictures/vacation.jpg", 5_000_000L, 1L))
        app.index.record(ImageRef("/storage/emulated/0/Old/gone.jpg", 5_000_000L, 1L))
        app.index.setReviewState("/storage/emulated/0/Old/gone.jpg", ReviewState.TRASHED)

        // viewModel() caches the first Application it sees, which is stale after another test class.
        val model = ReviewViewModel(app)
        compose.setContent {
            App(model)
        }

        openStorageMapFromSettings()

        compose.onNodeWithText("DCIM").assertIsDisplayed()
        compose.onNodeWithText("Pictures").assertIsDisplayed()
        compose.onNodeWithText("/storage/emulated/0").assertIsDisplayed()
        compose.onNodeWithText("Old").assertDoesNotExist()
    }

    @Test
    fun `back button zooms out of folder before leaving storage map`() = runBlocking<Unit> {
        app.index.record(ImageRef("/storage/emulated/0/DCIM/Camera/IMG_001.jpg", 10_000_000L, 1L))
        app.index.record(ImageRef("/storage/emulated/0/Pictures/vacation.jpg", 5_000_000L, 1L))

        // viewModel() caches the first Application it sees, which is stale after another test class.
        val model = ReviewViewModel(app)
        compose.setContent {
            App(model)
        }

        openStorageMapFromSettings()

        // Zoom into DCIM
        compose.onNodeWithText("DCIM").performClick()
        awaitText("Camera")
        compose.onNodeWithText("Camera").assertIsDisplayed()

        // First back zooms out to root folder
        compose.onNodeWithContentDescription("Back").performClick()
        awaitText("Pictures")
        compose.onNodeWithText("DCIM").assertIsDisplayed()
        compose.onNodeWithText("Pictures").assertIsDisplayed()

        // Second back leaves storage map to settings
        compose.onNodeWithContentDescription("Back").performClick()
        awaitText("Settings")
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Storage map"))
        compose.onNodeWithText("Storage map").assertIsDisplayed()
    }
}
