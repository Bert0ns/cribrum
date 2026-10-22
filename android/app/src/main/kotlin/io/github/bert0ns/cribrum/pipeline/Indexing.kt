// Background indexing with WorkManager (docs/design.md §4): a scan when the user asks for one, and
// an incremental one every night while the phone charges.
package io.github.bert0ns.cribrum.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.mobileclip.MobileClip
import io.github.bert0ns.cribrum.mobileclip.VISION_MODEL_ASSET
import io.github.bert0ns.cribrum.runtime.OnnxRuntime
import io.github.bert0ns.cribrum.sources.FilesystemImageSource
import io.github.bert0ns.cribrum.sources.ImageSource
import io.github.bert0ns.cribrum.sources.MediaStoreImageSource
import io.github.bert0ns.cribrum.sources.MergedImageSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

object Indexing {
    const val WORK = "index"

    /** On every scan request, so the progress covers the nightly one too. */
    const val TAG = "index-scan"
    private const val NIGHTLY_WORK = "index-nightly"
    private const val LEGACY_DAILY_WORK = "index-daily"
    const val STEP = "step"
    const val DONE = "done"
    const val TOTAL = "total"

    /** Only on the nightly request: its worker skips the scan outside the night. */
    const val NIGHTLY_TAG = "index-nightly-scan"

    /** The time the nightly scan is aimed at. */
    private val NIGHT_START = LocalTime.of(2, 0)

    /**
     * The nightly worker scans only before this. WorkManager runs a periodic request when charging
     * starts, which can be 14:00 at a desk after a night without charging. A scan then is not a night
     * scan, so the worker skips it and waits for the next night.
     */
    private val NIGHT_END = LocalTime.of(6, 0)

    /** Starts a scan now, unless one is running. */
    fun scanNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<IndexWorker>().addTag(TAG).build())
    }

    /**
     * Later passes only process new or changed files, so a daily one while charging is cheap.
     * KEEP, so the delay to the night is computed once: UPDATE would measure a new delay from the
     * original enqueue time. The first request had no delay and a name of its own, which is cancelled.
     * KEEP leaves the request as it is, but [pinNextNight] rewrites its constraints, interval and tags from
     * the current code when each nightly run ends, so a change to those reaches installs after their next
     * nightly run. Only the initial delay stays fixed. Changing it needs a new unique name, as this one
     * replaced the first, and drops the pinned night of the installs that have the old name.
     */
    fun schedulePeriodic(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(LEGACY_DAILY_WORK)
        manager.enqueueUniquePeriodicWork(NIGHTLY_WORK, ExistingPeriodicWorkPolicy.KEEP, dailyRequest(now))
    }

    /**
     * WorkManager has no time of day, so the first run waits for [NIGHT_START]. WorkManager measures each
     * later run from the end of the previous one, so the worker pins the next run to the following
     * [NIGHT_START] when it ends ([pinNextNight]). Charging and a battery that is not low decide when a run starts.
     */
    fun dailyRequest(now: ZonedDateTime = ZonedDateTime.now()): PeriodicWorkRequest = nightlyBuilder().setInitialDelay(Duration.between(now, nextNight(now))).build()

    private fun nightlyBuilder(): PeriodicWorkRequest.Builder {
        val constraints = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()
        return PeriodicWorkRequestBuilder<IndexWorker>(1, TimeUnit.DAYS).setConstraints(constraints).addTag(TAG).addTag(NIGHTLY_TAG)
    }

    /** The next [NIGHT_START] after [now]. */
    private fun nextNight(now: ZonedDateTime): ZonedDateTime {
        val tonight = now.toLocalDate().atTime(NIGHT_START).atZone(now.zone)
        return if (tonight.isAfter(now)) tonight else now.toLocalDate().plusDays(1).atTime(NIGHT_START).atZone(now.zone)
    }

    /** WorkManager's clock, so a test can move it. */
    internal fun now(context: Context): ZonedDateTime = Instant.ofEpochMilli(WorkManager.getInstance(context).configuration.clock.currentTimeMillis()).atZone(ZoneId.systemDefault())

    internal fun isNight(now: ZonedDateTime) = now.toLocalTime().isBefore(NIGHT_END)

    /** Called by the nightly worker as it ends: the next run is the following [NIGHT_START], whatever time this one ran. */
    internal fun pinNextNight(context: Context, id: UUID, now: ZonedDateTime = now(context)) {
        val next = nextNight(now).toInstant().toEpochMilli()
        WorkManager.getInstance(context).updateWork(nightlyBuilder().setId(id).setNextScheduleTimeOverride(next).build())
    }

    /** The running scan's progress, from "Scan now" or the nightly request, or null when none runs. */
    fun progress(context: Context): Flow<ScanProgress?> = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
        infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.progress?.let {
            ScanProgress(it.getString(STEP).orEmpty(), it.getInt(DONE, 0), it.getInt(TOTAL, 0))
        }
    }

    /** Every storage volume walked in full, then what MediaStore knows on top. */
    fun deviceSource(context: Context): ImageSource {
        val volumes = context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory }
        return MergedImageSource(volumes.map(::FilesystemImageSource) + MediaStoreImageSource(context.contentResolver))
    }
}

data class ScanProgress(val step: String, val done: Int, val total: Int)

/**
 * Purges the expired trash, then runs the pipeline over the whole device. It runs in the
 * foreground, with a notification, since a first scan of thousands of images outlasts the few
 * minutes Android gives background work. The index keeps what each run did, so a stopped run
 * resumes where it was.
 */
open class IndexWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val nightly = Indexing.NIGHTLY_TAG in tags
        val result = when {
            nightly && !Indexing.isNight(Indexing.now(applicationContext)) -> Result.success()
            // A scan from "Scan now" is already running: two would share the index and the model.
            !running.tryLock() -> Result.success()
            else -> try {
                scan()
            } finally {
                running.unlock()
            }
        }
        if (nightly) Indexing.pinNextNight(applicationContext, id)
        return result
    }

    /** The scan itself, open so a test can tell when one starts. */
    protected open suspend fun scan(): Result {
        if (!Environment.isExternalStorageManager()) return Result.failure()
        val app = applicationContext as CribrumApp
        setForeground(foregroundInfo(applicationContext.getString(R.string.scan_starting)))
        val purged = app.trash.purgeExpired()
        app.index.forget(purged.map { it.path })
        var reported = 0L
        val embedder = MobileClip { OnnxRuntime(applicationContext.assets.open(VISION_MODEL_ASSET).use { it.readBytes() }) }
        Pipeline(app.index, embedder = embedder).run(Indexing.deviceSource(applicationContext)) { step, done, total ->
            val now = System.currentTimeMillis()
            if (now - reported < PROGRESS_INTERVAL_MS && done != total) return@run
            reported = now
            setProgress(workDataOf(Indexing.STEP to step, Indexing.DONE to done, Indexing.TOTAL to total))
            setForeground(foregroundInfo(applicationContext.getString(R.string.scan_progress, done, total)))
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo() = foregroundInfo(applicationContext.getString(R.string.scan_starting))

    private fun foregroundInfo(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val title = applicationContext.getString(R.string.scan_channel)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private companion object {
        /** One scan at a time in the process, whichever request started it. */
        val running = Mutex()
        const val CHANNEL = "scan"
        const val NOTIFICATION_ID = 1
        const val PROGRESS_INTERVAL_MS = 1000L
    }
}
