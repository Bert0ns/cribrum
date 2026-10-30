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
import io.github.bert0ns.cribrum.detectors.AnalysisContext
import io.github.bert0ns.cribrum.detectors.GroupDetector
import io.github.bert0ns.cribrum.mobileclip.MobileClip
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.model.toImageRef
import io.github.bert0ns.cribrum.runtime.ModelRuntime
import io.github.bert0ns.cribrum.runtime.Tensor
import io.github.bert0ns.cribrum.trash.AppTrash
import kotlinx.coroutines.CancellationException
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

/** Stands in for the scan itself: counts the scans that start, reports progress and waits for [gate]. */
class FakeScan(
    context: Context,
    parameters: WorkerParameters,
    private val scans: AtomicInteger,
    private val gate: CompletableDeferred<Unit>,
    private val steps: List<Triple<String, Int, Int>> = emptyList(),
    private val leftMs: Long? = null,
) : IndexWorker(context, parameters) {
    init {
        isForeground = true
    }

    override suspend fun scan(): Result {
        scans.incrementAndGet()
        steps.forEach { (step, done, total) -> report(step, done, total) }
        setProgress(workDataOf(Indexing.STEP to "detectors", Indexing.DONE to 1, Indexing.TOTAL to 2, Indexing.LEFT_MS to (leftMs ?: -1L)))
        gate.await()
        return Result.success()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Same as the other Robolectric tests: SDK 37 needs more than the default setup
class IndexingWorkTest {
    @get:Rule
    val folder = TemporaryFolder()

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
    fun `the stored scan costs of an older version are removed`() {
        val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        settings.edit().putString("scan_costs", "{}").putBoolean("other", true).commit()

        Indexing.schedulePeriodic(app, now)

        assertFalse(settings.contains("scan_costs"))
        assertTrue(settings.getBoolean("other", false))
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
    fun `the progress carries the time left`() {
        workerFactory = { ctx, params -> FakeScan(ctx, params, scans, gate, leftMs = 720_000) }
        Indexing.scanNow(app)

        val progress = runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertEquals(ScanProgress("detectors", 1, 2, leftMs = 720_000), progress)
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

    private fun notificationText() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.last().extras.getString("android.text")

    @Test
    fun `the notification shows no total while the scan does not know one`() {
        steps += Triple("scan", 300, 0)
        Indexing.scanNow(app)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertEquals("300 so far", notificationText())
    }

    @Test
    fun `the notification shows the total once the phase knows it`() {
        steps += Triple("embeddings", 3, 10)
        Indexing.scanNow(app)
        runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertEquals("3 of 10", notificationText())
    }

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
        assertFalse(IndexWorker.running.isLocked)
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

    @Test
    fun `the scan forgets the rows of what the real trash purged, and keeps the row of an item it could not delete`() {
        val appInstance = app as CribrumApp
        val volume = folder.newFolder("storage")
        val trash = AppTrash({ listOf(volume) }, retention = { Duration.ZERO })
        val purged = File(volume, "purged.jpg").apply { writeText("purged") }.toImageRef()
        val stuck = File(volume, "stuck.jpg").apply { writeText("stuck") }.toImageRef()
        runBlocking {
            listOf(purged, stuck).forEach {
                appInstance.index.record(it)
                appInstance.index.setReviewState(it.path, ReviewState.TRASHED)
            }
            trash.moveToTrash(listOf(purged, stuck))
        }
        val stuckFolder = runBlocking { trash.trashed() }.single { it.original == stuck }.file.parentFile!!
        assumeTrue("the folder is still deletable (running as root?)", stuckFolder.setWritable(false) && !stuckFolder.canWrite())
        val done = CompletableDeferred<Unit>()
        workerFactory = { ctx, params ->
            object : IndexWorker(ctx, params) {
                override fun hasStorageAccess() = true
                override suspend fun setForegroundInfo(info: ForegroundInfo) {}
                override suspend fun purgeExpired(app: CribrumApp) = trash.purgeExpired()
                override fun pipeline(app: CribrumApp, embedder: MobileClip) = Pipeline(app.index, detectors = emptyList(), groupDetectors = emptyList())
                override suspend fun scan(): Result {
                    try {
                        return super.scan()
                    } finally {
                        done.complete(Unit)
                    }
                }
            }
        }
        try {
            Indexing.schedulePeriodic(app, now)
            startNightlyAt(at(22, 2, 30))
            waitForScan(done)
        } finally {
            stuckFolder.setWritable(true)
        }

        assertEquals(listOf(stuck), runBlocking { appInstance.index.images() })
    }

    /** A model that counts how often it is closed. */
    private class CountingRuntime : ModelRuntime {
        var closed = 0

        override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> = emptyMap()

        override fun close() {
            closed++
        }
    }

    /** The real [IndexWorker.scan] with its outside calls replaced: they are counted and nothing touches the device. */
    private class CountingScan(context: Context, parameters: WorkerParameters, private val runtime: CountingRuntime, private val done: CompletableDeferred<Unit>, private val loadModel: Boolean, private val stopWith: GroupDetector? = null, private val fail: Boolean = false) : IndexWorker(context, parameters) {
        var foreground = 0
        var purged = 0
        var models = 0

        override fun hasStorageAccess() = true

        override suspend fun setForegroundInfo(info: ForegroundInfo) {
            foreground++
        }

        override suspend fun purgeExpired(app: CribrumApp): List<ImageRef> {
            purged++
            return emptyList()
        }

        override fun loadVision(): ModelRuntime {
            models++
            return runtime
        }

        override fun pipeline(app: CribrumApp, embedder: MobileClip): Pipeline {
            if (loadModel) embedder.load()
            check(!fail) { "pipeline exploded" }
            return Pipeline(app.index, detectors = emptyList(), groupDetectors = listOfNotNull(stopWith), embedder = embedder)
        }

        override suspend fun scan(): Result {
            try {
                return super.scan()
            } finally {
                done.complete(Unit)
            }
        }
    }

    private fun runCountingScan(loadModel: Boolean, stopWith: GroupDetector? = null, fail: Boolean = false): Pair<CountingScan, CountingRuntime> {
        val runtime = CountingRuntime()
        val done = CompletableDeferred<Unit>()
        lateinit var worker: CountingScan
        workerFactory = { ctx, params -> CountingScan(ctx, params, runtime, done, loadModel, stopWith, fail).also { worker = it } }
        Indexing.schedulePeriodic(app, now)
        startNightlyAt(at(22, 2, 30))
        waitForScan(done)
        return worker to runtime
    }

    @Test
    fun `the real scan goes foreground, purges the trash and closes the model it loaded`() {
        val (worker, runtime) = runCountingScan(loadModel = true)

        assertEquals(1, worker.foreground)
        assertEquals(1, worker.purged)
        assertEquals(1, worker.models)
        assertEquals(1, runtime.closed)
        assertFalse(IndexWorker.running.isLocked)
    }

    @Test
    fun `the real scan loads no model when it has nothing to embed`() {
        val (worker, runtime) = runCountingScan(loadModel = false)

        assertEquals(1, worker.purged)
        assertEquals(0, worker.models)
        assertEquals(0, runtime.closed)
    }

    @Test
    fun `the real scan closes the model when the pipeline throws`() {
        val (_, runtime) = runCountingScan(loadModel = true, fail = true)

        assertEquals(1, runtime.closed)
        assertFalse(IndexWorker.running.isLocked)
    }

    @Test
    fun `the real scan closes the model when it is stopped`() {
        // The device has no images here, so the group detector runs on an empty list and stops the scan.
        val stop = object : GroupDetector {
            override val id = "stop"
            override val version = 1

            override suspend fun analyze(images: List<ImageRef>, context: AnalysisContext): Map<ImageRef, List<Signal>> = throw CancellationException("stopped")
        }

        val (_, runtime) = runCountingScan(loadModel = true, stopWith = stop)

        assertEquals(1, runtime.closed)
        assertFalse(IndexWorker.running.isLocked)
    }
}
