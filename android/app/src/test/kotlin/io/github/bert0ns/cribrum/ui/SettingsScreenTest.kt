package io.github.bert0ns.cribrum.ui

import android.content.Context
import android.text.format.Formatter
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.bert0ns.cribrum.App
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        // App() schedules the daily scan on the home screen.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun `settings screen opens from home and shows version and uninstall warning`() {
        compose.setContent {
            App()
        }

        compose.onNodeWithContentDescription("Settings").performClick()

        // The view model changes screen on its own scope, after it has read the retention.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Version", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Settings").assertIsDisplayed()
        // The release workflow sets the version, so it can't be a literal here.
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        compose.onNodeWithText("Version $version").assertIsDisplayed()
        compose.onNodeWithText("Keep trashed images for").assertIsDisplayed()
        compose.onNodeWithText("30 days").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Uninstalling leaves the trash folder on the phone. Empty the trash before uninstalling so trashed images do not keep taking space unseen."))
        compose.onNodeWithText("Uninstalling leaves the trash folder on the phone. Empty the trash before uninstalling so trashed images do not keep taking space unseen.").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        // Going back reloads the home screen on the view model's own scope.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Cribrum").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cribrum").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun `the user picks how many days the trash keeps images`() {
        var days = 30
        compose.setContent {
            var chosen by remember { mutableIntStateOf(days) }
            SettingsScreen(retentionDays = chosen, onRetentionChange = { chosen = it.also { days = it } })
        }

        compose.onNodeWithText("Keep trashed images for").performClick()
        compose.onNodeWithText("7 days").performClick()

        assertEquals(7, days)
        compose.onNodeWithText("7 days").assertIsDisplayed()
    }

    @Test
    fun `lowering the retention below some trashed images asks before deleting them`() {
        var confirmed = 0
        var cancelled = 0
        compose.setContent {
            SettingsScreen(
                retentionDays = 90,
                pendingRetention = RetentionChange(days = 7, expiring = 3),
                onConfirmRetention = { confirmed++ },
                onCancelRetention = { cancelled++ },
            )
        }

        compose.onNodeWithText("Delete 3 images for good?").assertIsDisplayed()
        compose.onNodeWithText("The next scan deletes the 3 images that have been in the trash for more than 7 days. This cannot be undone.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf(0, 1), listOf(confirmed, cancelled))

        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf(1, 1), listOf(confirmed, cancelled))
    }

    @Test
    fun `settings screen opens the detection thresholds`() {
        var opened = 0
        compose.setContent { SettingsScreen(onOpenThresholds = { opened++ }) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Detection thresholds"))
        compose.onNodeWithText("Detection thresholds").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `the haptic switch is on as set and reports a change`() {
        var enabled = true
        compose.setContent {
            SettingsScreen(hapticsEnabled = enabled, onHapticsChange = { enabled = it })
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Haptic feedback"))
        compose.onNodeWithText("Haptic feedback").assertIsOn()
        compose.onNodeWithText("Haptic feedback").performClick()

        assertEquals(false, enabled)
    }

    @Test
    fun `deleting the embeddings is a strong pulse once confirmed`() {
        val feedback = RecordingHaptics()
        val given = feedback.types
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                SettingsScreen(embeddingCount = 5)
            }
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Delete embeddings"))
        compose.onNodeWithText("Delete embeddings").performClick()
        assertEquals(emptyList<HapticFeedbackType>(), given)
        compose.onNodeWithText("Delete").performClick()

        assertEquals(listOf(Haptic.TRASH.type), given)
    }

    @Test
    fun `settings screen opens website and source links`() {
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                opened.add(uri)
            }
        }

        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                SettingsScreen()
            }
        }

        compose.onNodeWithText("https://cribrum.bertons.me").performClick()
        assertEquals(listOf("https://cribrum.bertons.me"), opened)

        compose.onNodeWithText("https://github.com/Bert0ns/cribrum").performClick()
        assertEquals(listOf("https://cribrum.bertons.me", "https://github.com/Bert0ns/cribrum"), opened)
    }

    @Test
    fun `settings screen lists excluded folders and removes one`() {
        val removed = mutableListOf<String>()
        compose.setContent {
            SettingsScreen(excludedFolders = listOf("/sdcard/Pictures/Family", "/sdcard/Memes"), onRemoveExcludedFolder = { removed += it })
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("/sdcard/Memes"))
        compose.onNodeWithText("/sdcard/Memes").assertExists()
        compose.onAllNodesWithContentDescription("Remove")[0].performClick()

        assertEquals(listOf("/sdcard/Pictures/Family"), removed)
    }

    @Test
    fun `settings screen says when no folder is excluded`() {
        compose.setContent { SettingsScreen() }

        val text = "No folders are excluded. Exclude one from the storage map, or from an image at full size."
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertExists()
    }

    @Test
    fun `add folder opens the storage map`() {
        var opened = false
        compose.setContent { SettingsScreen(onOpenStorageMap = { opened = true }) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Add folder"))
        compose.onNodeWithText("Add folder").performClick()

        assertEquals(true, opened)
    }

    @Test
    fun `deleting the embeddings asks first and says when they come back`() {
        var deleted = 0
        compose.setContent { SettingsScreen(embeddingCount = 120, onDeleteEmbeddings = { deleted++ }) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Delete embeddings"))
        compose.onNodeWithText("120 embeddings stored. Deleting them frees their space.").assertIsDisplayed()
        compose.onNodeWithText("Delete embeddings").performClick()

        compose.onNodeWithText("Delete the embeddings?").assertIsDisplayed()
        compose.onNodeWithText("The app stores one embedding per image to find duplicates, documents and forwarded images. They are computed again at the next scan, which you start with Scan now or which runs by itself at night while the phone charges. A full one takes a while: about 15 to 20 minutes for 10,000 images. Until then the review screen keeps what it shows. Your images, the trash and your decisions stay as they are.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, deleted)

        compose.onNodeWithText("Delete embeddings").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(1, deleted)
    }

    @Test
    fun `deleting the embeddings is off while a scan runs and when none are stored`() {
        compose.setContent { SettingsScreen(embeddingCount = 5, scanning = true) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Delete embeddings"))
        compose.onNodeWithText("Delete embeddings").assertIsNotEnabled()
        compose.onNodeWithText("Wait for the scan to finish.").assertIsDisplayed()
    }

    @Test
    fun `settings says when no embeddings are stored`() {
        compose.setContent { SettingsScreen(embeddingCount = 0) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Delete embeddings"))
        compose.onNodeWithText("No embeddings stored.").assertIsDisplayed()
        compose.onNodeWithText("Delete embeddings").assertIsNotEnabled()
    }

    @Test
    fun `settings lists the four sizes and the embeddings next to their delete item`() {
        compose.setContent { SettingsScreen(storage = StorageUse(app = 20_000_000, cache = 3_000_000, index = 1_000_000, embeddings = 5_000_000, trash = 42_000_000), embeddingCount = 3) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Delete embeddings"))
        compose.onNodeWithText("App").assertExists()
        compose.onNodeWithText(Formatter.formatShortFileSize(context, 20_000_000)).assertExists()
        compose.onNodeWithText("Cache").assertExists()
        compose.onNodeWithText(Formatter.formatShortFileSize(context, 3_000_000)).assertExists()
        compose.onNodeWithText("Index database").assertExists()
        compose.onNodeWithText(Formatter.formatShortFileSize(context, 1_000_000), substring = true).assertExists()
        compose.onNodeWithText("Files in the trash").assertExists()
        compose.onNodeWithText(Formatter.formatShortFileSize(context, 42_000_000)).assertExists()
        compose.onNodeWithText("3 embeddings stored. Deleting them frees their space.").assertExists()
        compose.onNodeWithText(Formatter.formatShortFileSize(context, 5_000_000), substring = true).assertExists()
    }

    @Test
    fun `settings hides the app and cache lines when Android does not say`() {
        compose.setContent { SettingsScreen(storage = StorageUse(app = null, cache = null, index = 1_000_000, embeddings = 0, trash = 0)) }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Delete embeddings"))
        compose.onNodeWithText("Index database").assertExists()
        compose.onNodeWithText("App").assertDoesNotExist()
        compose.onNodeWithText("Cache").assertDoesNotExist()
    }
}
