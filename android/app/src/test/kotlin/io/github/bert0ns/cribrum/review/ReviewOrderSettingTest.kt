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
class ReviewOrderSettingTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("test", Context.MODE_PRIVATE)

    @Test
    fun `every category starts off`() {
        assertEquals(ReviewOrder.OFF, ReviewOrderSetting(preferences)[Category.BLURRY])
    }

    @Test
    fun `the choice is kept per category for the next start`() {
        ReviewOrderSetting(preferences)[Category.BLURRY] = ReviewOrder.WEEK
        ReviewOrderSetting(preferences)[Category.SCREENSHOT] = ReviewOrder.MONTH

        val next = ReviewOrderSetting(preferences)
        assertEquals(ReviewOrder.WEEK, next[Category.BLURRY])
        assertEquals(ReviewOrder.MONTH, next[Category.SCREENSHOT])
        assertEquals(ReviewOrder.OFF, next[Category.DOCUMENT])
    }

    @Test
    fun `a choice saved under the old key and name still loads`() {
        preferences.edit().putString("date_grouping_BLURRY", "WEEK").putString("date_grouping_BURST", "SIZE").commit()

        assertEquals(ReviewOrder.WEEK, ReviewOrderSetting(preferences)[Category.BLURRY])
        assertEquals(ReviewOrder.SIZE, ReviewOrderSetting(preferences)[Category.BURST])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a group category has no date order`() {
        ReviewOrderSetting(preferences)[Category.BURST] = ReviewOrder.DAY
    }

    @Test
    fun `size is offered by every category and the dates only by the single-image ones`() {
        assertEquals(listOf(ReviewOrder.OFF, ReviewOrder.SIZE), ReviewOrder.optionsFor(Category.BURST))
        assertEquals(listOf(ReviewOrder.OFF, ReviewOrder.SIZE), ReviewOrder.optionsFor(Category.STICKER))
        assertEquals(ReviewOrder.entries, ReviewOrder.optionsFor(Category.BLURRY))
    }

    @Test
    fun `a group category keeps a size sort`() {
        ReviewOrderSetting(preferences)[Category.BURST] = ReviewOrder.SIZE

        assertEquals(ReviewOrder.SIZE, ReviewOrderSetting(preferences)[Category.BURST])
    }

    @Test
    fun `only the five single-image categories offer it`() {
        assertEquals(
            setOf(Category.BLURRY, Category.BAD_EXPOSURE, Category.SCREENSHOT, Category.DOCUMENT, Category.FORWARDED),
            ReviewOrder.CATEGORIES,
        )
    }

    @Test
    fun `labels follow the locale`() {
        val day = LocalDate.of(2026, 3, 2)

        assertEquals("Mar 2, 2026", ReviewOrder.DAY.label(day, Locale.US))
        assertEquals("2 mar 2026", ReviewOrder.DAY.label(day, Locale.ITALY))
        assertEquals("Mar 2, 2026 – Mar 8, 2026", ReviewOrder.WEEK.label(day, Locale.US))
        assertEquals("March 2026", ReviewOrder.MONTH.label(LocalDate.of(2026, 3, 1), Locale.US))
        assertEquals("marzo 2026", ReviewOrder.MONTH.label(LocalDate.of(2026, 3, 1), Locale.ITALY))
    }

    @Test
    fun `a week starts on the given day and a month on the first`() {
        val wednesday = LocalDate.of(2026, 3, 4)

        assertEquals(LocalDate.of(2026, 3, 2), ReviewOrder.WEEK.start(wednesday, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 3, 1), ReviewOrder.WEEK.start(wednesday, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 3, 1), ReviewOrder.MONTH.start(wednesday, DayOfWeek.MONDAY))
    }
}
