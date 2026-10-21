package io.github.bert0ns.cribrum.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stands in for the platform's feedback and keeps what was asked of it. */
class RecordingHaptics : HapticFeedback {
    val types = mutableListOf<HapticFeedbackType>()

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        types += hapticFeedbackType
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HapticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `trashing, keeping and a tick are three different feedbacks`() {
        assertEquals(
            listOf(HapticFeedbackType.Reject, HapticFeedbackType.Confirm, HapticFeedbackType.SegmentTick),
            listOf(Haptic.TRASH, Haptic.KEEP, Haptic.TICK).map { it.type },
        )
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun longPress(enabled: Boolean): List<HapticFeedbackType> {
        val platform = RecordingHaptics()
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides platform) {
                WithHaptics(enabled) {
                    Box(Modifier.testTag("box").combinedClickable(onClick = {}, onLongClick = {}))
                }
            }
        }
        compose.onNodeWithTag("box").performTouchInput { longClick() }
        return platform.types
    }

    @Test
    fun `the platform's own feedback goes through while the setting is on`() {
        assertEquals(listOf(HapticFeedbackType.LongPress), longPress(enabled = true))
    }

    @Test
    fun `turning the setting off silences the platform's own feedback too`() {
        assertEquals(emptyList<HapticFeedbackType>(), longPress(enabled = false))
    }
}
