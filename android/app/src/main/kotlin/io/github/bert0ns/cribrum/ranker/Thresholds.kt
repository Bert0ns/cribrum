package io.github.bert0ns.cribrum.ranker

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.bert0ns.cribrum.model.Category

/**
 * The thresholds the app ranks with: the user's overrides over the defaults in [THRESHOLDS]
 * (docs/design.md §6.1). A value equal to its default is not stored, so the defaults stay the
 * lab's.
 */
class Thresholds(private val preferences: SharedPreferences) {
    val values: Map<Category, Float>
        get() = THRESHOLDS.mapValues { (category, default) ->
            preferences.getFloat(key(category), default).takeIf { it in 0f..1f } ?: default
        }

    operator fun set(category: Category, value: Float) {
        require(value in 0f..1f) { "not a threshold: $value" }
        preferences.edit {
            if (value == THRESHOLDS[category]) remove(key(category)) else putFloat(key(category), value)
        }
    }

    fun reset() = preferences.edit { THRESHOLDS.keys.forEach { remove(key(it)) } }

    private fun key(category: Category) = "threshold_${category.name}"
}
