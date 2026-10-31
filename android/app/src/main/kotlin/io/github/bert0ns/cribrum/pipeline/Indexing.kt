// Background indexing with WorkManager (docs/design.md §4): a scan when the user asks for one, and
// an incremental one every night while the phone charges.
package io.github.bert0ns.cribrum.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
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
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.runtime.FallbackRuntime
import io.github.bert0ns.cribrum.runtime.ModelRuntime
import io.github.bert0ns.cribrum.runtime.OnnxRuntime
import io.github.bert0ns.cribrum.sources.FilesystemImageSource
import io.github.bert0ns.cribrum.sources.ImageSource
import io.github.bert0ns.cribrum.sources.MediaStoreImageSource
import io.github.bert0ns.cribrum.sources.MergedImageSource
import io.github.bert0ns.cribrum.sources.NotImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private const val LEGACY_COSTS_KEY = "scan_costs"
    const val STEP = "step"
    const val DONE = "done"
    const val TOTAL = "total"

    /** The time left of the current phase in ms, absent while there is no estimate. */
    const val LEFT_MS = "left_ms"

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
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit { remove(LEGACY_COSTS_KEY) }
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

    /** How many phases a scan has. A rescan may skip one, so the number a user sees can jump. */
    internal const val PHASES = 6

    /**
     * The number and the title of a [Pipeline] step. The step of a group detector is named after
     * the detector, and the step is empty until the walk sends its first progress.
     */
    private fun phase(step: String) = when (step) {
        "", "scan" -> 1 to R.string.scan_phase_scan
        "metadata" -> 2 to R.string.scan_phase_metadata
        "detectors" -> 3 to R.string.scan_phase_analysis
        "embeddings" -> 4 to R.string.scan_phase_embeddings
        "embedded_detectors" -> 5 to R.string.scan_phase_classification
        else -> 6 to R.string.scan_phase_groups
    }

    @StringRes
    internal fun phaseTitle(step: String) = phase(step).second

    internal fun phaseNumber(step: String) = phase(step).first

    /** "Phase 3 of 6". */
    internal fun phaseText(resources: Resources, step: String) = resources.getString(R.string.scan_phase_number, phaseNumber(step), PHASES)

    /** The time left, rounded to the minute: "About 12 min left in this step", or "Less than a minute left in this step". */
    internal fun timeLeft(resources: Resources, ms: Long): String {
        val minutes = (ms + 30_000) / 60_000
        return when {
            ms < 60_000 -> resources.getString(R.string.scan_left_under_a_minute)
            minutes < 60 -> resources.getString(R.string.scan_left_minutes, minutes)
            else -> resources.getString(R.string.scan_left_hours, minutes / 60, minutes % 60)
        }
    }

    /** The running scan's progress, from "Scan now" or the nightly request, or null when none runs. */
    fun progress(context: Context): Flow<ScanProgress?> = WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
        infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.progress?.let {
            ScanProgress(it.getString(STEP).orEmpty(), it.getInt(DONE, 0), it.getInt(TOTAL, 0), it.getLong(LEFT_MS, -1).takeIf { left -> left >= 0 })
        }
    }

    /** Every storage volume walked in full, then what MediaStore knows on top. [indexed] is what the index already has. */
    fun deviceSource(context: Context, indexed: suspend () -> Collection<ImageRef>, notImages: NotImages): ImageSource {
        val volumes = context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory }
        return MergedImageSource(volumes.map { FilesystemImageSource(it, notImages = notImages, indexed = indexed) } + MediaStoreImageSource(context.contentResolver, volumes))
    }
}

/** [leftMs] is the time left of the current phase, null until the estimate is stable (docs/design.md §4). */
data class ScanProgress(val step: String, val done: Int, val total: Int, val leftMs: Long? = null)

/**
 * Purges the expired trash, then runs the pipeline over the whole device. It runs in the
 * foreground, with a notification, since a first scan of thousands of images outlasts the few
 * minutes Android gives background work. The index keeps what each run did, so a stopped run
 * resumes where it was.
 */
open class IndexWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val nightly = Indexing.NIGHTLY_TAG in tags
        try {
            return when {
                nightly && !Indexing.isNight(Indexing.now(applicationContext)) -> Result.success()
                // A scan from "Scan now" is already running: two would share the index and the model.
                !running.tryLock() -> Result.success()
                else -> try {
                    scan()
                } finally {
                    running.unlock()
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.e(Indexing.TAG, "Scan failed", error)
            (applicationContext as? CribrumApp)?.lastScan?.recordFailure()
            return Result.failure()
        } finally {
            if (nightly) Indexing.pinNextNight(applicationContext, id)
        }
    }

    protected var isForeground = false

    /** The scan itself, open so a test can tell when one starts. */
    protected open suspend fun scan(): Result {
        val app = applicationContext as CribrumApp
        if (!hasStorageAccess()) {
            app.lastScan.recordFailure()
            return Result.failure()
        }
        try {
            setForegroundInfo(foregroundInfo("scan", applicationContext.getString(R.string.scan_starting)))
            isForeground = true
        } catch (e: IllegalStateException) {
            Log.w(Indexing.TAG, "Foreground service start refused, continuing in background", e)
        }
        try {
            val trashFailures = mutableListOf<Failure>()
            try {
                val purged = purgeExpired(app)
                app.index.forgetTrashed(purged.map { it.path })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w(Indexing.TAG, "Failed to purge expired trash", error)
                trashFailures += Failure("", "trash", failureMessage(error))
            }
            // The model loads when the first batch needs it, and the session is closed when the scan ends.
            MobileClip(::loadVision).use { embedder ->
                app.lastScan.run {
                    val stats = pipeline(app, embedder).run(Indexing.deviceSource(applicationContext, app.index::images, app.index), ::report)
                    stats.failures.addAll(trashFailures)
                    stats
                }
            }
            return Result.success()
        } finally {
            applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    // The CPU fallback reads the model again only if the GPU fails, so the bytes are not held through the scan.
    protected open fun loadVision(): ModelRuntime = FallbackRuntime(OnnxRuntime(visionModel(), gpu = true)) { OnnxRuntime(visionModel()) }

    private fun visionModel() = applicationContext.assets.open(VISION_MODEL_ASSET).use { it.readBytes() }

    protected open fun hasStorageAccess(): Boolean = Environment.isExternalStorageManager()

    protected open suspend fun setForegroundInfo(info: ForegroundInfo) = setForeground(info)

    protected open suspend fun purgeExpired(app: CribrumApp): List<ImageRef> = app.trash.purgeExpired()

    protected open fun pipeline(app: CribrumApp, embedder: MobileClip): Pipeline = Pipeline(app.index, embedder = embedder, trash = app.trash)

    private var title = 0
    private var reported = 0L
    private var notified = 0L

    private val estimate = ScanEstimate(SystemClock::elapsedRealtime)

    /**
     * Sends the progress out, at most five times a second to the app and once a second to the
     * notification, but at once when the phase and so the title change.
     */
    protected suspend fun report(step: String, done: Int, total: Int) {
        estimate.update(step, done, total)
        val now = System.currentTimeMillis()
        val phaseTitle = Indexing.phaseTitle(step)
        val phaseChanged = phaseTitle != title
        if (now - reported < PROGRESS_INTERVAL_MS && done != total && !phaseChanged) return
        reported = now
        title = phaseTitle
        val left = estimate.millisLeft()
        setProgress(workDataOf(Indexing.STEP to step, Indexing.DONE to done, Indexing.TOTAL to total, Indexing.LEFT_MS to (left ?: -1L)))
        if (isForeground && (phaseChanged || done == total || now - notified >= NOTIFICATION_INTERVAL_MS)) {
            notified = now
            // A stop that came meanwhile has had the notification removed: posting now would bring it back.
            currentCoroutineContext().ensureActive()
            // The service is in the foreground since scan() began, so the notification is updated in place.
            val count = if (total == 0) applicationContext.getString(R.string.scan_progress_open, done) else applicationContext.getString(R.string.scan_progress, done, total)
            val text = left?.let { applicationContext.getString(R.string.scan_joined, count, Indexing.timeLeft(applicationContext.resources, it)) } ?: count
            val info = foregroundInfo(step, text)
            applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, info.notification)
        }
    }

    override suspend fun getForegroundInfo() = foregroundInfo("scan", applicationContext.getString(R.string.scan_starting))

    private fun foregroundInfo(step: String, text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.scan_channel), NotificationManager.IMPORTANCE_LOW))
        val title = applicationContext.getString(R.string.scan_joined, Indexing.phaseText(applicationContext.resources, step), applicationContext.getString(Indexing.phaseTitle(step)))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    internal companion object {
        /** One scan at a time in the process, whichever request started it. */
        val running = Mutex()
        const val CHANNEL = "scan"
        const val NOTIFICATION_ID = 1
        const val PROGRESS_INTERVAL_MS = 200L
        const val NOTIFICATION_INTERVAL_MS = 1000L
    }
}
