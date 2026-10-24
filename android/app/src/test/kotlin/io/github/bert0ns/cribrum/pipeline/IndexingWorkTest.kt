package io.github.bert0ns.cribrum.pipeline

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Clock
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.mobileclip.MobileClip
import io.github.bert0ns.cribrum.model.ImageRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/** Stands in for the scan itself: counts the scans that start, reports progress and waits for [gate]. */
class FakeScan(context: Context, parameters: WorkerParameters, private val scans: AtomicInteger, private val gate: CompletableDeferred<Unit>, private val steps: List<Triple<String, Int, Int>> = emptyList()) : IndexWorker(context, parameters) {
    init {
        isForeground = true
    }

    override suspend fun scan(): Result {
        scans.incrementAndGet()
        steps.forEach { (step, done, total) -> report(step, done, total) }
        setProgress(workDataOf(Indexing.STEP to "detectors", Indexing.DONE to 1, Indexing.TOTAL to 2))
        gate.await()
        return Result.success()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Same as the other Robolectric tests: SDK 37 needs more than the default setup
class IndexingWorkTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Europe/Rome")
    private val defaultZone = TimeZone.getDefault()
    private var now = at(21, 12)
    private val clock = Clock { now.toInstant().toEpochMilli() }
    private val scans = AtomicInteger()
    private val gate = CompletableDeferred<Unit>()
    private val steps = mutableListOf<Triple<String, Int, Int>>()
    private var workerFactory: (Context, WorkerParameters) -> ListenableWorker = { ctx, params ->
        FakeScan(ctx, params, scans, gate, steps)
    }

    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        val configuration = Configuration.Builder()
            .setClock(clock)
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker = workerFactory(appContext, workerParameters)
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, configuration)
    }

    /** A scan left waiting would keep the process-wide lock for the next test. */
    @After
    fun tearDown() {
        gate.complete(Unit)
        runBlocking { withTimeout(10_000) { while (scheduled().any { it.state == WorkInfo.State.RUNNING }) kotlinx.coroutines.yield() } }
        TimeZone.setDefault(defaultZone)
    }

    private fun scheduled() = WorkManager.getInstance(app).getWorkInfosByTag(Indexing.TAG).get()

    private fun nightly() = scheduled().single { it.tags.contains(Indexing.NIGHTLY_TAG) }

    private fun millis(time: ZonedDateTime) = time.toInstant().toEpochMilli()

    /** Lets the nightly request start at [time], as if charging and the delay were met then. */
    private fun startNightlyAt(time: ZonedDateTime) {
        now = time
        val id = nightly().id
        WorkManagerTestInitHelper.getTestDriver(app)!!.apply {
            setAllConstraintsMet(id)
            setInitialDelayMet(id)
        }
    }

    @Test
    fun `opening the app again keeps the first night, however late it is`() {
        Indexing.schedulePeriodic(app, now)
        now = at(21, 20)
        Indexing.schedulePeriodic(app, now)

        assertEquals(millis(at(22, 2)), nightly().nextScheduleTimeMillis)
    }

    @Test
    fun `the old daily request is cancelled`() {
        val manager = WorkManager.getInstance(app)
        manager.enqueueUniquePeriodicWork("index-daily", ExistingPeriodicWorkPolicy.KEEP, Indexing.dailyRequest(now))

        Indexing.schedulePeriodic(app, now)

        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfosForUniqueWork("index-daily").get().single().state)
        assertEquals(1, scheduled().count { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun `no scan is shown while none runs`() {
        Indexing.schedulePeriodic(app, now)

        assertNull(runBlocking { withTimeout(10_000) { Indexing.progress(app).first() } })
    }

    @Test
    fun `the progress of the nightly scan shows`() {
        Indexing.schedulePeriodic(app, now)
        startNightlyAt(at(22, 2, 30))

        val progress = runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertEquals(ScanProgress("detectors", 1, 2), progress)
    }

    @Test
    fun `the progress of a scan started with Scan now shows`() {
        Indexing.scanNow(app)

        val progress = runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertEquals(ScanProgress("detectors", 1, 2), progress)
    }

    @Test
    fun `a run in the night scans and pins the next one to the following night`() {
        Indexing.schedulePeriodic(app, now)
        gate.complete(Unit)

        startNightlyAt(at(22, 2, 30))

        assertEquals(1, scans.get())
        assertEquals(millis(at(23, 2)), nightly().nextScheduleTimeMillis)
    }

    @Test
    fun `a run in the daytime skips the scan and pins the next one to the coming night`() {
        Indexing.schedulePeriodic(app, now)
        gate.complete(Unit)

        startNightlyAt(at(22, 14))

        assertEquals(0, scans.get())
        assertEquals(millis(at(23, 2)), nightly().nextScheduleTimeMillis)
    }

    @Test
    fun `the nightly run does not start a second scan on top of one started with Scan now`() {
        Indexing.scanNow(app)
        Indexing.schedulePeriodic(app, now)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        startNightlyAt(at(22, 2, 30))

        assertEquals(1, scans.get())
    }

    private fun notificationTitle() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.last().extras.getString("android.title")

    @Test
    fun `each phase of the pipeline has a title of its own`() {
        assertEquals(R.string.scan_phase_scan, Indexing.phaseTitle("scan"))
        assertEquals(R.string.scan_phase_metadata, Indexing.phaseTitle("metadata"))
        assertEquals(R.string.scan_phase_embeddings, Indexing.phaseTitle("embeddings"))
        assertEquals(R.string.scan_phase_analysis, Indexing.phaseTitle("detectors"))
        assertEquals(R.string.scan_phase_classification, Indexing.phaseTitle("embedded_detectors"))
        assertEquals(R.string.scan_phase_groups, Indexing.phaseTitle("near_duplicate"))
    }

    @Test
    fun `the notification title follows the phase, even right after the last update`() {
        steps += Triple("scan", 100, 0)
        steps += Triple("embeddings", 1, 10)
        Indexing.scanNow(app)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertEquals(app.getString(R.string.scan_phase_embeddings), notificationTitle())
    }

    private fun waitForScan(done: CompletableDeferred<Unit>, expectedNextNight: ZonedDateTime = at(23, 2)) = runBlocking {
        withTimeout(10_000) {
            done.await()
            while (nightly().nextScheduleTimeMillis != millis(expectedNextNight)) yield()
        }
    }

    @Test
    fun `a crashing scan records failure and pins the next night`() {
        val appInstance = app as CribrumApp
        appInstance.lastScan.record(Stats(), finishedAt = 100)
        assertFalse(appInstance.lastScan.failed)
        val done = CompletableDeferred<Unit>()

        workerFactory = { ctx, params ->
            object : IndexWorker(ctx, params) {
                override suspend fun scan(): Result {
                    try {
                        scans.incrementAndGet()
                        throw IllegalStateException("crash in scan")
                    } finally {
                        done.complete(Unit)
                    }
                }
            }
        }
        Indexing.schedulePeriodic(app, now)
        startNightlyAt(at(22, 2, 30))
        waitForScan(done)

        assertEquals(1, scans.get())
        assertTrue(appInstance.lastScan.failed)
        assertEquals(millis(at(23, 2)), nightly().nextScheduleTimeMillis)
    }

    @Test
    fun `missing storage permission fails the scan, records failure and pins the next night`() {
        val appInstance = app as CribrumApp
        appInstance.lastScan.record(Stats(), finishedAt = 100)
        assertFalse(appInstance.lastScan.failed)
        val done = CompletableDeferred<Unit>()

        workerFactory = { ctx, params ->
            object : IndexWorker(ctx, params) {
                override fun hasStorageAccess() = false
                override suspend fun scan(): Result {
                    try {
                        return super.scan()
                    } finally {
                        done.complete(Unit)
                    }
                }
            }
        }
        Indexing.schedulePeriodic(app, now)
        startNightlyAt(at(22, 2, 30))
        waitForScan(done)

        assertTrue(appInstance.lastScan.failed)
        assertEquals(millis(at(23, 2)), nightly().nextScheduleTimeMillis)
    }

    @Test
    fun `foreground service start refusal does not abort the scan and pins the next night`() {
        var completedScan = false
        var foregroundAttempted = false
        val done = CompletableDeferred<Unit>()
        workerFactory = { ctx, params ->
            object : IndexWorker(ctx, params) {
                override fun hasStorageAccess() = true
                override suspend fun setForegroundInfo(info: ForegroundInfo) {
                    foregroundAttempted = true
                    throw IllegalStateException("ForegroundServiceStartNotAllowedException")
                }
                override suspend fun purgeExpired(app: CribrumApp) = emptyList<ImageRef>()
                override fun pipeline(app: CribrumApp, embedder: MobileClip) = Pipeline(app.index, detectors = emptyList(), groupDetectors = emptyList())
                override suspend fun scan(): Result {
                    try {
                        return super.scan()
                    } finally {
                        completedScan = true
                        done.complete(Unit)
                    }
                }
            }
        }
        Indexing.schedulePeriodic(app, now)
        startNightlyAt(at(22, 2, 30))
        waitForScan(done)

        assertTrue(foregroundAttempted)
        assertTrue(completedScan)
        assertEquals(millis(at(23, 2)), nightly().nextScheduleTimeMillis)
        assertNull(shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(IndexWorker.NOTIFICATION_ID))
    }

    @Test
    fun `purgeExpired failure is recorded in failures and scan continues to completion`() {
        val appInstance = app as CribrumApp
        var completedScan = false
        val done = CompletableDeferred<Unit>()
        workerFactory = { ctx, params ->
            object : IndexWorker(ctx, params) {
                override fun hasStorageAccess() = true
                override suspend fun setForegroundInfo(info: ForegroundInfo) {}
                override suspend fun purgeExpired(app: CribrumApp): List<ImageRef> = throw IllegalStateException("disk error during purge")
                override fun pipeline(app: CribrumApp, embedder: MobileClip) = Pipeline(app.index, detectors = emptyList(), groupDetectors = emptyList())
                override suspend fun scan(): Result {
                    try {
                        return super.scan()
                    } finally {
                        completedScan = true
                        done.complete(Unit)
                    }
                }
            }
        }
        Indexing.schedulePeriodic(app, now)
        startNightlyAt(at(22, 2, 30))
        waitForScan(done)

        assertTrue(completedScan)
        assertEquals(millis(at(23, 2)), nightly().nextScheduleTimeMillis)
        assertTrue(appInstance.lastScan.failed)
        assertEquals(1, appInstance.lastScan.report?.failures?.get("trash"))
    }
}
