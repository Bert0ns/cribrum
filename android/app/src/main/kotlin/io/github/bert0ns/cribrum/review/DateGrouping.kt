// Display order by date for the single-image categories (docs/design.md §8). The date only orders
// the review: it never reaches the ranker, and photo age is never a deletion signal.
package io.github.bert0ns.cribrum.review

import android.content.SharedPreferences
import android.text.format.DateFormat
import androidx.core.content.edit
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class DateGrouping {
    OFF,
    DAY,
    WEEK,
    MONTH,
    ;

    /** The first day of the day, week or month that holds [date]: the date a group is named by. */
    fun start(date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate = when (this) {
        OFF, DAY -> date
        WEEK -> date.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        MONTH -> date.withDayOfMonth(1)
    }

    /** The group's header, in the locale's own format: a day, a week as its first and last day, or a month. */
    fun label(start: LocalDate, locale: Locale): String {
        val day = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        return when (this) {
            OFF, DAY -> day.format(start)
            WEEK -> "${day.format(start)} – ${day.format(start.plusDays(6))}"
            MONTH -> DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale).format(start)
        }
    }

    companion object {
        /** Categories of one image per group: duplicates and bursts keep their order, and a sticker has no use for it. */
        val CATEGORIES = setOf(Category.BLURRY, Category.BAD_EXPOSURE, Category.SCREENSHOT, Category.DOCUMENT, Category.FORWARDED)
    }
}

/** The EXIF day when the index has one, the file's last modified day otherwise. Few images have EXIF. */
fun imageDate(image: ImageRef, capturedAt: LocalDateTime?, zone: ZoneId): LocalDate = capturedAt?.toLocalDate() ?: Instant.ofEpochSecond(0, image.mtimeNs).atZone(zone).toLocalDate()

/** The choice per category, stored with the other settings. Off until the user picks one. */
class DateGroupingSetting(private val preferences: SharedPreferences) {
    operator fun get(category: Category): DateGrouping = DateGrouping.entries.firstOrNull { it.name == preferences.getString(key(category), null) } ?: DateGrouping.OFF

    operator fun set(category: Category, value: DateGrouping) {
        require(category in DateGrouping.CATEGORIES) { "$category has no date grouping" }
        preferences.edit { putString(key(category), value.name) }
    }

    private fun key(category: Category) = "date_grouping_${category.name}"
}
