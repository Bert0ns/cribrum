package io.github.bert0ns.cribrum.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HapticsTest {
    @get:Rule
    val compose = createComposeRule()

    private val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test", Context.MODE_PRIVATE)

    private fun performed(enabled: Boolean, vararg haptics: Haptic): List<HapticFeedbackType> {
        val types = mutableListOf<HapticFeedbackType>()
        val feedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                types += hapticFeedbackType
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides feedback) {
                val perform = rememberHaptics(enabled)
                haptics.forEach(perform)
            }
        }
        compose.waitForIdle()
        return types
    }

    @Test
    fun `it is on by default`() {
        assertTrue(HapticsSetting(preferences).enabled)
    }

    @Test
    fun `a choice is kept for the next start`() {
        HapticsSetting(preferences).enabled = false

        assertEquals(false, HapticsSetting(preferences).enabled)
    }

    @Test
    fun `trashing, keeping and a tick are three different feedbacks`() {
        val types = performed(true, Haptic.TRASH, Haptic.KEEP, Haptic.TICK)

        assertEquals(listOf(HapticFeedbackType.Reject, HapticFeedbackType.Confirm, HapticFeedbackType.SegmentTick), types)
    }

    @Test
    fun `none is given when the setting is off`() {
        assertEquals(emptyList<HapticFeedbackType>(), performed(false, Haptic.TRASH, Haptic.KEEP, Haptic.TICK))
    }
}
