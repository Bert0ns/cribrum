package io.github.bert0ns.cribrum.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.content.edit

/**
 * The feedback an action gives, stronger for the ones that matter more (docs/design.md §8). The
 * types are the platform's own, so the phone's touch feedback setting still applies on top of ours.
 */
enum class Haptic(val type: HapticFeedbackType) {
    /** A destructive confirmation: images go to the trash, or the embeddings are deleted. */
    TRASH(HapticFeedbackType.Reject),

    /** A decision that keeps things as they are. */
    KEEP(HapticFeedbackType.Confirm),

    /** Selecting, skipping and other small steps. */
    TICK(HapticFeedbackType.SegmentTick),
}

/** Whether the app gives haptic feedback: a user setting, on by default. */
class HapticsSetting(private val preferences: SharedPreferences) {
    var enabled: Boolean
        get() = preferences.getBoolean(KEY, true)
        set(value) = preferences.edit { putBoolean(KEY, value) }

    private companion object {
        const val KEY = "haptic_feedback"
    }
}

/**
 * Gives the feedback of the setting to everything below: off silences the platform's own feedback
 * too, such as the long press on a thumbnail.
 */
@Composable
fun WithHaptics(enabled: Boolean, content: @Composable () -> Unit) {
    val platform = LocalHapticFeedback.current
    CompositionLocalProvider(LocalHapticFeedback provides if (enabled) platform else NoHaptics, content = content)
}

private object NoHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {}
}
