package io.github.bert0ns.cribrum

import android.Manifest
import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.bert0ns.cribrum.model.ImageRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainActivityTest {
    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<CribrumApp>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        app.notificationPreferences.permissionRequested = false
        MainActivity.permissions = object : Permissions by DefaultPermissions {
            override fun hasAllFilesAccess() = true
            override fun hasNotificationPermission(context: Context) = true
        }
    }

    @After
    fun tearDown() {
        MainActivity.resetPermissions()
        app.notificationPreferences.permissionRequested = false
    }

    @Test
    fun `notification hint displays message and triggers settings action`() {
        var clicked = false
        compose.setContent {
            NotificationHint(onOpenSettings = { clicked = true })
        }

        compose.onNodeWithText("Scan progress notification is off").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed().performClick()
        assertTrue(clicked)
    }

    @Test
    fun `notificationSettings intent targets app notification settings`() {
        val intent = notificationSettings(app)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `clicking notification hint settings button starts app notification settings`() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { activityController ->
            val activity = activityController.get()
            compose.setContent {
                NotificationHint(onOpenSettings = { activity.startActivity(notificationSettings(activity)) })
            }

            compose.onNodeWithText("Settings").performClick()
            val nextIntent = shadowOf(activity).nextStartedActivity
            assertNotNull(nextIntent)
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, nextIntent.action)
            assertEquals(activity.packageName, nextIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        }
    }

    @Test
    fun `when all files access is not granted, the access gate is shown and clicking grant launches settings`() {
        MainActivity.permissions = object : Permissions by DefaultPermissions {
            override fun hasAllFilesAccess() = false
        }
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()

            compose.onNodeWithText("Cribrum needs All files access").assertIsDisplayed()
            val grantButton = compose.onNodeWithText("Allow access")
            grantButton.assertIsDisplayed()
            grantButton.performClick()

            val nextIntent = shadowOf(activity).nextStartedActivity
            assertNotNull(nextIntent)
            assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, nextIntent.action)
            assertEquals("package:" + activity.packageName, nextIntent.dataString)
        }
    }

    @Test
    fun `when all files access is granted on resume, model refresh is called`() {
        MainActivity.permissions = object : Permissions by DefaultPermissions {
            override fun hasAllFilesAccess() = false
        }
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()

            // Record an image into index while out of app
            runBlocking {
                app.index.record(ImageRef("/sdcard/test.jpg", 1000, 1L))
            }

            // User grants permission and returns to app
            MainActivity.permissions = object : Permissions by DefaultPermissions {
                override fun hasAllFilesAccess() = true
            }
            controller.resume()
            shadowOf(Looper.getMainLooper()).idle()

            // Refresh should have updated the indexEmpty state in the view model
            repeat(100) {
                shadowOf(Looper.getMainLooper()).idle()
                if (!activity.model.state.value.indexEmpty) return@repeat
                Thread.sleep(10)
            }
            assertFalse(activity.model.state.value.indexEmpty)
        }
    }

    @Test
    fun `notification permission is requested once and rotation does not re-request`() {
        MainActivity.permissions = object : Permissions by DefaultPermissions {
            override fun hasAllFilesAccess() = true
            override fun hasNotificationPermission(context: Context) = false
        }
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            val activity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()

            val firstRequest = shadowOf(activity).lastRequestedPermission
            assertNotNull(firstRequest)
            assertTrue(firstRequest.requestedPermissions.contains(Manifest.permission.POST_NOTIFICATIONS))
            assertTrue(app.notificationPreferences.permissionRequested)

            // Recreate activity (simulating rotation)
            controller.recreate()
            val recreatedActivity = controller.get()
            shadowOf(Looper.getMainLooper()).idle()

            val secondRequest = shadowOf(recreatedActivity).lastRequestedPermission
            Assert.assertNull(secondRequest)
            assertTrue(app.notificationPreferences.permissionRequested)
        }
    }
}
