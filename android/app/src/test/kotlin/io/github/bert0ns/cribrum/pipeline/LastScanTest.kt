package io.github.bert0ns.cribrum.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LastScanTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("last-scan-test", Context.MODE_PRIVATE)

    @Test
    fun `before any scan there is no report`() {
        assertNull(LastScan(preferences).report)
    }

    @Test
    fun `a report keeps counts and file names, and a new instance reads it back`() {
        val stats = Stats(unreadable = 2).apply {
            failures += Failure("/storage/emulated/0/DCIM/a.jpg", "embedding", "OutOfMemoryError: x")
            failures += Failure("/storage/emulated/0/WhatsApp/b.jpg", "embedding", "IOException: y")
            failures += Failure("/storage/emulated/0/WhatsApp/b.jpg", "blur", "IOException: y")
        }

        LastScan(preferences).record(stats, finishedAt = 1_700_000_000_000)

        val report = LastScan(preferences).report!!
        assertEquals(1_700_000_000_000, report.finishedAt)
        assertEquals(2, report.unreadable)
        assertEquals(mapOf("embedding" to 2, "blur" to 1), report.failures)
        assertEquals(listOf(FailedFile("embedding", "a.jpg"), FailedFile("embedding", "b.jpg"), FailedFile("blur", "b.jpg")), report.files)
        assertFalse("/storage" in preferences.all.values.joinToString())
    }

    @Test
    fun `only the first names are kept, but every failure is counted`() {
        val stats = Stats().apply { repeat(LastScan.MAX_FILES + 5) { failures += Failure("/d/$it.jpg", "embedding", "x") } }

        LastScan(preferences).record(stats, finishedAt = 1)

        val report = LastScan(preferences).report!!
        assertEquals(LastScan.MAX_FILES + 5, report.failures.getValue("embedding"))
        assertEquals(LastScan.MAX_FILES, report.files.size)
    }

    @Test
    fun `a later scan replaces the report`() {
        val scan = LastScan(preferences)
        scan.record(Stats().apply { failures += Failure("/d/a.jpg", "blur", "x") }, finishedAt = 1)

        scan.record(Stats(), finishedAt = 2)

        assertEquals(ScanReport(finishedAt = 2, unreadable = 0, failures = emptyMap(), files = emptyList()), scan.report)
    }
}
