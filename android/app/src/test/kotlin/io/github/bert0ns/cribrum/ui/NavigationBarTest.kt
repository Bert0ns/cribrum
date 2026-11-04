package io.github.bert0ns.cribrum.ui

import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.bert0ns.cribrum.App
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.model.Category
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NavigationBarTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<CribrumApp>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    private fun awaitText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    /** The model finishes its changes on a background thread, which posts back to the main looper. */
    private fun awaitScreen(model: ReviewViewModel, screen: Screen) = compose.waitUntil(5_000) {
        shadowOf(Looper.getMainLooper()).idle()
        !model.state.value.loading && model.state.value.screen == screen
    }

    @Test
    fun `the bar lists clean, compress, map and settings and compress opens its page`() {
        val model = ReviewViewModel(app)
        compose.setContent { App(model) }

        for (label in listOf("Clean", "Compress", "Map", "Settings")) compose.onNodeWithText(label).assertIsDisplayed()

        compose.onNodeWithText("Compress").performClick()
        awaitText("Supported formats: JPEG. Motion Photos are not compressed.")
    }

    @Test
    fun `a full screen view covers the bar`() {
        val model = ReviewViewModel(app)
        compose.setContent { App(model) }
        compose.onNodeWithText("Clean").assertIsDisplayed()

        model.open(Category.BLURRY)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Clean").fetchSemanticsNodes().isEmpty() }

        compose.onAllNodesWithText("Compress").assertCountEquals(0)
    }

    @Test
    fun `the top bar opens the trash from Clean and from Compress, and Back returns to where it came from`() {
        val model = ReviewViewModel(app)
        compose.setContent { App(model) }
        awaitScreen(model, Screen.Home)

        compose.onNodeWithContentDescription("Trash").performClick()
        awaitScreen(model, Screen.Trash(Destination.Clean))
        compose.onAllNodesWithText("Compress").assertCountEquals(0)
        compose.onNodeWithContentDescription("Back").performClick()
        awaitText("Compress")
        awaitScreen(model, Screen.Home)

        compose.onNodeWithText("Compress").performClick()
        awaitText("Supported formats: JPEG. Motion Photos are not compressed.")
        compose.onNodeWithContentDescription("Trash").performClick()
        awaitScreen(model, Screen.Trash(Destination.Compress))
        compose.onNodeWithContentDescription("Back").performClick()
        awaitScreen(model, Screen.Compress)
        awaitText("Supported formats: JPEG. Motion Photos are not compressed.")
    }
}
