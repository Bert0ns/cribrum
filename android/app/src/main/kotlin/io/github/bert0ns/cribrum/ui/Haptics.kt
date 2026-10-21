package io.github.bert0ns.cribrum.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.content.edit

/**
 * The feedback an action gives, stronger for the ones that matter more (docs/design.md §8). The
 * types are the platform's own, so the phone's touch feedback setting still applies on top of ours.
 */
enum class Haptic(val type: HapticFeedbackType) {
    /** Images go to the trash. */
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

/** Gives the feedback of the current setting. It does nothing unless [App] provides it, as in the tests of a single screen. */
val LocalHaptics = staticCompositionLocalOf<(Haptic) -> Unit> { {} }

@Composable
fun rememberHaptics(enabled: Boolean): (Haptic) -> Unit {
    val feedback = LocalHapticFeedback.current
    return remember(feedback, enabled) {
        if (enabled) ({ haptic: Haptic -> feedback.performHapticFeedback(haptic.type) }) else ({ _: Haptic -> })
    }
}
