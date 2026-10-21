package io.github.bert0ns.cribrum.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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

        compose.onNodeWithText("Settings").assertIsDisplayed()
        // The release workflow sets the version, so it can't be a literal here.
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        compose.onNodeWithText("Version $version").assertIsDisplayed()
        compose.onNodeWithText("Keep trashed images for").assertIsDisplayed()
        compose.onNodeWithText("30 days").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Uninstalling leaves the trash folder on the phone. Empty the trash before uninstalling so trashed images do not keep taking space unseen."))
        compose.onNodeWithText("Uninstalling leaves the trash folder on the phone. Empty the trash before uninstalling so trashed images do not keep taking space unseen.").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
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
}
