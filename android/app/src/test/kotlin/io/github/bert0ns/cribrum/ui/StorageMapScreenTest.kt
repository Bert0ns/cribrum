package io.github.bert0ns.cribrum.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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
import androidx.compose.ui.test.performSemanticsAction
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
// A tall screen: the bar takes room from the folder list, which the tests read.
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
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
    fun `a list row selects its folder on the first tap and opens it on the second, like a block`() {
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
        assertEquals(null, zoomed)
        compose.onAllNodesWithText("DCIM").assertCountEquals(2) // the card and the list
        compose.onAllNodesWithText("DCIM")[1].performClick()
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
    fun `exclude button excludes the selected folder`() {
        val excluded = mutableListOf<String>()
        compose.setContent { StorageMapScreen(currentFolder = tree, onZoomIn = {}, onExclude = { excluded += it.path }, atRoot = true) }

        compose.onNodeWithTag("storage_map").performTouchInput { click(Offset(width / 2f, 20f)) }
        compose.onNodeWithText("Exclude this folder").performClick()

        assertEquals(listOf("/storage/emulated/0/DCIM"), excluded)
    }

    @Test
    fun `exclude button excludes the open folder when zoomed in`() {
        val excluded = mutableListOf<String>()
        val dcim = tree.children.first()
        compose.setContent { StorageMapScreen(currentFolder = dcim, onZoomIn = {}, onExclude = { excluded += it.path }) }

        compose.onNodeWithText("Exclude this folder").performClick()

        assertEquals(listOf(dcim.path), excluded)
    }

    @Test
    fun `the map's root has no exclude button until a folder is selected`() {
        compose.setContent { StorageMapScreen(currentFolder = tree, onZoomIn = {}, atRoot = true) }

        compose.onNodeWithText("Exclude this folder").assertDoesNotExist()
        compose.onNodeWithTag("storage_map").performTouchInput { click(Offset(width / 2f, 20f)) }
        compose.onNodeWithText("Exclude this folder").assertIsDisplayed()
    }

    @Test
    fun `excluding a folder on the map shows a snackbar with Undo`() = runBlocking<Unit> {
        app.index.record(ImageRef("/storage/emulated/0/DCIM/Camera/IMG_001.jpg", 10_000_000L, 1L))
        app.index.record(ImageRef("/storage/emulated/0/Pictures/vacation.jpg", 5_000_000L, 1L))
        val model = ReviewViewModel(app)
        compose.setContent { App(model) }
        openStorageMapFromTheBar()

        compose.onNodeWithTag("storage_map").performTouchInput { click(Offset(width / 2f, 20f)) }
        compose.onNodeWithText("Exclude this folder").performClick()
        awaitText("Undo")

        compose.onNodeWithText("Excluded DCIM and its subfolders").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        awaitText("DCIM")
        assertEquals(emptyList<String>(), app.index.excludedFolders())
    }

    @Test
    fun `images of size zero are not shown as an empty index`() {
        val zeros = buildFolderTree(listOf(ImageRef("/a/1.jpg", 0L, 0L), ImageRef("/a/2.jpg", 0L, 0L)))
        compose.setContent { StorageMapScreen(currentFolder = zeros, onZoomIn = {}) }

        compose.onNodeWithText("No images found yet").assertDoesNotExist()
    }

    @Test
    fun `storage map shows empty message when there are no images`() {
        compose.setContent {
            StorageMapScreen(
                currentFolder = Folder(name = "", path = "", size = 0L, imageCount = 0),
                onZoomIn = {},
            )
        }

        compose.onNodeWithText("No images found yet").assertIsDisplayed()
    }

    /** Navigation runs on the view model scope, so the screen changes after the click returns. */
    private fun awaitText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun openStorageMapFromTheBar() {
        compose.onNodeWithText("Settings").performClick()
        awaitText("Keep trashed images for")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Storage map"))
        compose.onNodeWithText("Storage map").performClick()
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

        openStorageMapFromTheBar()

        compose.onNodeWithText("DCIM").assertIsDisplayed()
        compose.onNodeWithText("Pictures").assertIsDisplayed()
        compose.onNodeWithText("All explored folders").assertIsDisplayed()
        compose.onNodeWithText("Old").assertDoesNotExist()
    }

    @Test
    fun `back zooms out of a folder, then returns to settings`() = runBlocking<Unit> {
        app.index.record(ImageRef("/storage/emulated/0/DCIM/Camera/IMG_001.jpg", 10_000_000L, 1L))
        app.index.record(ImageRef("/storage/emulated/0/Pictures/vacation.jpg", 5_000_000L, 1L))

        // viewModel() caches the first Application it sees, which is stale after another test class.
        val model = ReviewViewModel(app)
        compose.setContent {
            App(model)
        }

        openStorageMapFromTheBar()

        // Zoom into DCIM: its row selects it, and Show contents opens it.
        compose.onNodeWithText("DCIM").performClick()
        compose.onNodeWithText("Show contents").performClick()
        awaitText("Camera")
        // The top bar names the open folder.
        compose.onAllNodesWithText("DCIM").assertCountEquals(2) // the bar and the card
        compose.onNodeWithText("Camera").assertIsDisplayed()

        // First back zooms out to root folder
        compose.onNodeWithContentDescription("Back").performClick()
        awaitText("Pictures")
        compose.onNodeWithText("DCIM").assertIsDisplayed()
        compose.onNodeWithText("Pictures").assertIsDisplayed()

        // At the root Back returns to Settings, which the map belongs to.
        compose.onNodeWithContentDescription("Back").performClick()
        awaitText("Excluded folders")
        assertEquals(Screen.Settings, model.state.value.screen)
    }

    @Test
    fun `the card names the selected folder and opens it with a button`() {
        var zoomed: Folder? = null
        compose.setContent { StorageMapScreen(currentFolder = tree, onZoomIn = { zoomed = it }, atRoot = true) }

        compose.onNodeWithText("Show contents").assertDoesNotExist()
        compose.onNodeWithTag("storage_map").performTouchInput { click(Offset(width / 2f, 20f)) }
        compose.onAllNodesWithText("DCIM").assertCountEquals(2) // the card and the list
        compose.onNodeWithText("Show contents").performClick()

        assertEquals("DCIM", zoomed?.name)
    }

    @Test
    fun `a screen reader opens a folder from its block`() {
        var zoomed: Folder? = null
        compose.setContent { StorageMapScreen(currentFolder = tree, onZoomIn = { zoomed = it }, atRoot = true) }

        val actions = compose.onNodeWithContentDescription("Pictures", substring = true).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnUiThread { actions.single { it.label == "Show contents" }.action() }

        assertEquals("Pictures", zoomed?.name)
    }

    @Test
    fun `a screen reader finds each top-level block and selects it`() {
        compose.setContent { StorageMapScreen(currentFolder = tree, onZoomIn = {}, atRoot = true) }

        compose.onNodeWithContentDescription("Pictures", substring = true).performSemanticsAction(SemanticsActions.OnClick)

        compose.onNodeWithContentDescription("Pictures", substring = true).assertIsSelected()
        compose.onNodeWithText("Show contents").assertIsDisplayed()
    }
}
