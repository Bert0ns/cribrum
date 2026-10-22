package io.github.bert0ns.cribrum.review

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.bert0ns.cribrum.model.Category
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DateGroupingSettingTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test", Context.MODE_PRIVATE)

    @Test
    fun `every category starts off`() {
        assertEquals(DateGrouping.OFF, DateGroupingSetting(preferences)[Category.BLURRY])
    }

    @Test
    fun `the choice is kept per category for the next start`() {
        DateGroupingSetting(preferences)[Category.BLURRY] = DateGrouping.WEEK
        DateGroupingSetting(preferences)[Category.SCREENSHOT] = DateGrouping.MONTH

        val next = DateGroupingSetting(preferences)
        assertEquals(DateGrouping.WEEK, next[Category.BLURRY])
        assertEquals(DateGrouping.MONTH, next[Category.SCREENSHOT])
        assertEquals(DateGrouping.OFF, next[Category.DOCUMENT])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a group category has no date grouping`() {
        DateGroupingSetting(preferences)[Category.BURST] = DateGrouping.DAY
    }

    @Test
    fun `only the five single-image categories offer it`() {
        assertEquals(
            setOf(Category.BLURRY, Category.BAD_EXPOSURE, Category.SCREENSHOT, Category.DOCUMENT, Category.FORWARDED),
            DateGrouping.CATEGORIES,
        )
    }

    @Test
    fun `labels follow the locale`() {
        val day = LocalDate.of(2026, 3, 2)

        assertEquals("Mar 2, 2026", DateGrouping.DAY.label(day, Locale.US))
        assertEquals("2 mar 2026", DateGrouping.DAY.label(day, Locale.ITALY))
        assertEquals("Mar 2, 2026 – Mar 8, 2026", DateGrouping.WEEK.label(day, Locale.US))
        assertEquals("March 2026", DateGrouping.MONTH.label(LocalDate.of(2026, 3, 1), Locale.US))
        assertEquals("marzo 2026", DateGrouping.MONTH.label(LocalDate.of(2026, 3, 1), Locale.ITALY))
    }

    @Test
    fun `a week starts on the given day and a month on the first`() {
        val wednesday = LocalDate.of(2026, 3, 4)

        assertEquals(LocalDate.of(2026, 3, 2), DateGrouping.WEEK.start(wednesday, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 3, 1), DateGrouping.WEEK.start(wednesday, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 3, 1), DateGrouping.MONTH.start(wednesday, DayOfWeek.MONDAY))
    }
}
