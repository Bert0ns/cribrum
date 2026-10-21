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
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
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
    private val NIGHT_START = LocalTime.of(2, 0)

    /** Starts a scan now, unless one is running. */
    fun scanNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<IndexWorker>().addTag(TAG).build())
    }

    /**
     * Later passes only process new or changed files, so a daily one while charging is cheap.
     * KEEP, so the delay to the night is computed once: UPDATE would measure a new delay from the
     * original enqueue time. The first request had no delay and a name of its own, which is cancelled.
     */
    fun schedulePeriodic(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(LEGACY_DAILY_WORK)
        manager.enqueueUniquePeriodicWork(NIGHTLY_WORK, ExistingPeriodicWorkPolicy.KEEP, dailyRequest(now))
    }

    /**
     * WorkManager has no time of day, so the first run waits for [NIGHT_START] and the daily period keeps
     * later runs about a day apart. Charging and a battery that is not low decide when it actually starts.
     * shortcut: a run held back by the constraints shifts later runs, when it matters add a flex window.
     */
    fun dailyRequest(now: ZonedDateTime = ZonedDateTime.now()): PeriodicWorkRequest {
        val constraints = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()
        var next = now.toLocalDate().atTime(NIGHT_START).atZone(now.zone)
        if (!next.isAfter(now)) next = now.toLocalDate().plusDays(1).atTime(NIGHT_START).atZone(now.zone)
        return PeriodicWorkRequestBuilder<IndexWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setInitialDelay(Duration.between(now, next))
            .addTag(TAG)
            .build()
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
class IndexWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
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
        const val CHANNEL = "scan"
        const val NOTIFICATION_ID = 1
        const val PROGRESS_INTERVAL_MS = 1000L
    }
}
