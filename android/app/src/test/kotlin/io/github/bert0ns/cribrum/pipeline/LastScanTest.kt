package io.github.bert0ns.cribrum.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun `a scan that finishes is recorded, one that throws or is cancelled leaves the previous report`() {
        val scan = LastScan(preferences)
        runBlocking { scan.run(now = { 1 }) { Stats(unreadable = 1) } }

        assertThrows(IllegalStateException::class.java) { runBlocking { scan.run(now = { 2 }) { error("disk gone") } } }
        assertThrows(CancellationException::class.java) { runBlocking { scan.run(now = { 3 }) { throw CancellationException("stopped") } } }

        assertEquals(ScanReport(finishedAt = 1, unreadable = 1, failures = emptyMap(), files = emptyList()), scan.report)
        runBlocking { scan.run(now = { 4 }) { Stats() } }
        assertEquals(4, scan.report!!.finishedAt)
    }

    @Test
    fun `failed reflects whether the last scan had critical failures or aborted`() {
        val scan = LastScan(preferences)
        assertFalse(scan.failed)

        // Scan abort sets the failed flag
        scan.recordFailure()
        assertTrue(scan.failed)

        // Clean scan clears the failed flag
        scan.record(Stats(), finishedAt = 1)
        assertFalse(scan.failed)

        // Per-image failure does not mark whole scan as failed
        val perImageStats = Stats().apply { failures += Failure("/d/a.jpg", "blur", "error") }
        scan.record(perImageStats, finishedAt = 2)
        assertFalse(scan.failed)

        // Group detector failure marks the scan as failed
        val groupDetectorStats = Stats().apply { failures += Failure("", "exact_duplicate", "error") }
        scan.record(groupDetectorStats, finishedAt = 3)
        assertTrue(scan.failed)

        // Clean run clears failed flag
        runBlocking { scan.run(now = { 4 }) { Stats() } }
        assertFalse(scan.failed)

        // Trash failure marks the scan as failed
        val trashStats = Stats().apply { failures += Failure("", "trash", "error") }
        scan.record(trashStats, finishedAt = 5)
        assertTrue(scan.failed)
    }
}
