package io.github.bert0ns.cribrum.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class IndexingTest {
    private val zone = ZoneId.of("Europe/Rome")

    private fun at(hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, 21, hour, minute, 0, 0, zone)

    @Test
    fun `the daily scan runs once a day, only while charging and with the battery not low`() {
        val request = Indexing.dailyRequest(at(12))

        assertEquals(Duration.ofDays(1).toMillis(), request.workSpec.intervalDuration)
        assertTrue(request.workSpec.constraints.requiresCharging())
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
    }

    @Test
    fun `the daily scan waits for the next night window`() {
        assertEquals(Duration.ofHours(14).toMillis(), Indexing.dailyRequest(at(12)).workSpec.initialDelay)
        assertEquals(Duration.ofHours(1).toMillis(), Indexing.dailyRequest(at(1)).workSpec.initialDelay)
        assertEquals(Duration.ofHours(23).plusMinutes(30).toMillis(), Indexing.dailyRequest(at(2, 30)).workSpec.initialDelay)
    }

    @Test
    fun `a request made at the start of the window waits a full day`() {
        assertEquals(Duration.ofDays(1).toMillis(), Indexing.dailyRequest(at(2)).workSpec.initialDelay)
    }

    private fun on(month: Int, day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone)

    private fun delay(now: ZonedDateTime) = Duration.ofMillis(Indexing.dailyRequest(now).workSpec.initialDelay)

    @Test
    fun `the spring forward night has no 02h00 and the scan waits for 03h00`() {
        // 2026-03-29: 02:00 CET does not exist, the clocks jump to 03:00 CEST.
        assertEquals(Duration.ofHours(14), delay(on(3, 28, 12)))
        assertEquals(Duration.ofHours(1), delay(on(3, 29, 1)))
        assertEquals(Duration.ofHours(21), delay(on(3, 29, 5)))
    }

    @Test
    fun `the fall back night is 25 hours long and the scan waits for the first 02h00`() {
        // 2026-10-25: 02:00 CEST happens first, then the clocks go back to 02:00 CET.
        assertEquals(Duration.ofHours(14), delay(on(10, 24, 12)))
        assertEquals(Duration.ofHours(1), delay(on(10, 25, 1)))
        assertEquals(Duration.ofHours(24).plusMinutes(30), delay(on(10, 25, 2, 30)))
        assertEquals(Duration.ofHours(14), delay(on(10, 25, 12)))
    }
}
