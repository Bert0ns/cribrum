package io.github.bert0ns.cribrum.trash

import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.Duration

/** How long the trash keeps an image before purging it: a user setting (docs/design.md §9). */
class TrashRetention(private val preferences: SharedPreferences) {
    var days: Int
        get() = preferences.getInt(KEY, DEFAULT_DAYS).takeIf { it in CHOICES } ?: DEFAULT_DAYS
        set(value) {
            require(value in CHOICES) { "not a retention choice: $value" }
            preferences.edit { putInt(KEY, value) }
        }

    val duration: Duration get() = Duration.ofDays(days.toLong())

    companion object {
        const val DEFAULT_DAYS = 30
        val CHOICES = listOf(7, 14, DEFAULT_DAYS, 60, 90)
        val DEFAULT: Duration = Duration.ofDays(DEFAULT_DAYS.toLong())
        private const val KEY = "trash_retention_days"
    }
}
