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
}
