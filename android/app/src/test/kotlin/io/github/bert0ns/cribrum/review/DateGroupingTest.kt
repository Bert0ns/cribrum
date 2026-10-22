package io.github.bert0ns.cribrum.review

import io.github.bert0ns.cribrum.index.memoryIndex
import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.trash.AppTrash
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DateGroupingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val index = memoryIndex()
    private val volume by lazy { folder.newFolder("storage") }
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private var firstDay = DayOfWeek.MONDAY
    private val review by lazy { Review(index, AppTrash({ listOf(volume) }), zone = { tokyo }, firstDayOfWeek = { firstDay }) }

    @After
    fun close() = index.close()

    /** A blurry image taken at [capturedAt] when it has EXIF, and last modified at [mtime] otherwise. */
    private fun image(name: String, score: Float, capturedAt: LocalDateTime? = null, mtime: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0)): String = runBlocking {
        val ref = ImageRef(name, 1_000, TimeUnit.SECONDS.toNanos(mtime.atZone(tokyo).toEpochSecond()))
        index.record(ref)
        index.setSignals(name, "blur", 1, listOf(Signal("blur", 1, Category.BLURRY, score)))
        if (capturedAt != null) index.setMetadata(name, ImageMetadata(10, 10, "image/jpeg", capturedAt = capturedAt))
        name
    }

    private fun order(grouping: DateGrouping, skipped: Set<String> = emptySet()) = runBlocking {
        review.page(Category.BLURRY, skipped, grouping).groups
    }

    @Test
    fun `off lists the candidates by confidence alone, with no date`() {
        image("old.jpg", 0.99f, LocalDateTime.of(2020, 1, 1, 12, 0))
        image("new.jpg", 0.6f, LocalDateTime.of(2026, 3, 2, 12, 0))
        image("mid.jpg", 0.8f, LocalDateTime.of(2024, 5, 5, 12, 0))

        val groups = order(DateGrouping.OFF)

        assertEquals(listOf("old.jpg", "mid.jpg", "new.jpg"), groups.map { it.id })
        assertEquals(listOf(null, null, null), groups.map { it.date })
    }

    @Test
    fun `by day, the newest day comes first and confidence orders each day`() {
        image("a.jpg", 0.6f, LocalDateTime.of(2026, 3, 2, 10, 0))
        image("b.jpg", 0.9f, LocalDateTime.of(2026, 3, 2, 18, 0))
        image("c.jpg", 0.99f, LocalDateTime.of(2026, 2, 28, 9, 0))

        val groups = order(DateGrouping.DAY)

        assertEquals(listOf("b.jpg", "a.jpg", "c.jpg"), groups.map { it.id })
        assertEquals(listOf(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 2), LocalDate.of(2026, 2, 28)), groups.map { it.date })
    }

    @Test
    fun `an image without EXIF falls back to its mtime in the local time zone`() {
        // 2026-03-02 20:00 in Tokyo is the same day as the EXIF one below; 2026-03-03 05:00 in Tokyo is the next.
        image("exif.jpg", 0.95f, LocalDateTime.of(2026, 3, 2, 12, 0))
        image("mtime.jpg", 0.9f, mtime = LocalDateTime.of(2026, 3, 3, 5, 0))

        val groups = order(DateGrouping.DAY)

        assertEquals(listOf("mtime.jpg", "exif.jpg"), groups.map { it.id })
        assertEquals(LocalDate.of(2026, 3, 3), groups.first().date)
    }

    @Test
    fun `by week, a week starts on the locale's first day of the week`() {
        image("sun.jpg", 0.9f, LocalDateTime.of(2026, 3, 1, 12, 0)) // a Sunday
        image("mon.jpg", 0.8f, LocalDateTime.of(2026, 3, 2, 12, 0))

        firstDay = DayOfWeek.MONDAY
        assertEquals(listOf(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 2, 23)), order(DateGrouping.WEEK).map { it.date })

        firstDay = DayOfWeek.SUNDAY
        assertEquals(listOf(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1)), order(DateGrouping.WEEK).map { it.date })
        assertEquals(listOf("sun.jpg", "mon.jpg"), order(DateGrouping.WEEK).map { it.id })
    }

    @Test
    fun `by month, the first of the month names the group`() {
        image("mar.jpg", 0.5f, LocalDateTime.of(2026, 3, 31, 12, 0))
        image("apr.jpg", 0.4f, LocalDateTime.of(2026, 4, 1, 12, 0))
        image("apr2.jpg", 0.6f, LocalDateTime.of(2026, 4, 30, 12, 0))

        val groups = order(DateGrouping.MONTH)

        assertEquals(listOf("apr2.jpg", "apr.jpg", "mar.jpg"), groups.map { it.id })
        assertEquals(listOf(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 3, 1)), groups.map { it.date })
    }

    @Test
    fun `pages follow the date order, and a skipped group leaves its date`() = runBlocking {
        repeat(PAGE_SIZE) { image("new$it.jpg", 0.5f, LocalDateTime.of(2026, 3, 2, 12, 0)) }
        repeat(3) { image("old$it.jpg", 0.99f, LocalDateTime.of(2026, 1, 2, 12, 0)) }

        val page = review.page(Category.BLURRY, emptySet(), DateGrouping.MONTH)

        assertEquals(PAGE_SIZE, page.groups.size)
        assertEquals(setOf(LocalDate.of(2026, 3, 1)), page.groups.map { it.date }.toSet())
        assertEquals(PAGE_SIZE + 3, page.remaining)

        val next = review.page(Category.BLURRY, page.groups.map { it.id }.toSet(), DateGrouping.MONTH)
        assertEquals(3, next.groups.size)
        assertEquals(3, next.remaining)
        assertEquals(setOf(LocalDate.of(2026, 1, 1)), next.groups.map { it.date }.toSet())
    }
}
