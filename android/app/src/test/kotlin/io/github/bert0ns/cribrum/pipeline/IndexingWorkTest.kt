package io.github.bert0ns.cribrum.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Clock
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

/** Stands in for [IndexWorker]: reports progress and never finishes, so the work stays RUNNING. */
class RunningScan(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        setProgress(workDataOf(Indexing.STEP to "detectors", Indexing.DONE to 1, Indexing.TOTAL to 2))
        awaitCancellation()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36]) // Same as the other Robolectric tests: SDK 37 needs more than the default setup
class IndexingWorkTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Europe/Rome")
    private var now = at(21, 12)
    private val clock = Clock { now.toInstant().toEpochMilli() }

    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, zone)

    @Before
    fun setUp() {
        val configuration = Configuration.Builder()
            .setClock(clock)
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker = RunningScan(appContext, workerParameters)
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, configuration)
    }

    private fun scheduled() = WorkManager.getInstance(app).getWorkInfosByTag(Indexing.TAG).get()

    @Test
    fun `opening the app again keeps the first night, however late it is`() {
        Indexing.schedulePeriodic(app, now)
        now = at(21, 20)
        Indexing.schedulePeriodic(app, now)

        assertEquals(at(22, 2).toInstant().toEpochMilli(), scheduled().single().nextScheduleTimeMillis)
    }

    @Test
    fun `the old daily request is cancelled`() {
        val manager = WorkManager.getInstance(app)
        manager.enqueueUniquePeriodicWork("index-daily", androidx.work.ExistingPeriodicWorkPolicy.KEEP, Indexing.dailyRequest(now))

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
        val id = scheduled().single().id
        WorkManagerTestInitHelper.getTestDriver(app)!!.apply {
            setAllConstraintsMet(id)
            setInitialDelayMet(id)
        }

        val progress = runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertEquals(ScanProgress("detectors", 1, 2), progress)
    }

    @Test
    fun `the progress of a scan started with Scan now shows`() {
        Indexing.scanNow(app)

        val progress = runBlocking { withTimeout(10_000) { Indexing.progress(app).filterNotNull().first() } }

        assertTrue(progress.total == 2)
    }
}
