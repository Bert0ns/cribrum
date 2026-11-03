// A batch of compressions in the background: one photo at a time, never during a scan (docs/design.md §9).
package io.github.bert0ns.cribrum.compress

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.index.ImageIndex
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.pipeline.IndexWorker
import io.github.bert0ns.cribrum.review.Review
import io.github.bert0ns.cribrum.trash.AppTrash
import io.github.bert0ns.cribrum.ui.HeatLevel
import io.github.bert0ns.cribrum.ui.heatOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** What a finished batch did. */
data class BatchResult(
    val total: Int,
    val compressed: Int,
    /** How many photos each refusal stopped. */
    val refused: Map<Refusal, Int>,
    /** The bytes the compressed photos take less than their originals did. */
    val savedBytes: Long,
    /** Every original the batch moved to the trash, those a swap that failed halfway left there included: what Undo restores. */
    val originals: List<ImageRef>,
) {
    /** The photos a cancel, or a failure of the job itself, kept the batch from reaching. They are untouched. */
    val notReached: Int get() = total - compressed - refused.values.sum()
}

sealed interface BatchState {
    data object Idle : BatchState

    /** Asked for while a scan runs: it starts when the scan ends. */
    data class WaitingForScan(val total: Int) : BatchState

    /** [done] of [total] photos are finished. [paused] while the phone is severely hot, between two photos. */
    data class Running(val done: Int, val total: Int, val savedBytes: Long, val paused: Boolean = false) : BatchState

    /** Kept until the next batch starts or this one is undone. Only in memory: a killed process forgets it. */
    data class Finished(val result: BatchResult) : BatchState
}

/** The one batch the app runs at a time, and its state for the compress page. */
object CompressBatch {
    internal const val WORK = "compress"

    private val mutableState = MutableStateFlow<BatchState>(BatchState.Idle)
    val state: StateFlow<BatchState> = mutableState.asStateFlow()

    internal class Request(val paths: List<String>, val level: CompressionLevel)

    // shortcut: the request lives in memory, with the id of the worker it is for, so a process killed mid-batch drops
    // the rest of it, and WorkManager's rerun finds nothing to do. The originals compressed so far stay in the trash. Persist it if that bites.
    private var pending: Pair<UUID, Request>? = null

    /** While a batch waits or runs, a scan does not start (docs/design.md §9). */
    internal val busy: Boolean get() = state.value.let { it is BatchState.WaitingForScan || it is BatchState.Running }

    /**
     * Compresses [paths], indexed photos, at [level], one at a time. Returns false, and starts nothing, while
     * another batch waits or runs.
     */
    fun start(context: Context, paths: List<String>, level: CompressionLevel): Boolean {
        val work = OneTimeWorkRequestBuilder<CompressWorker>().build()
        synchronized(this) {
            if (busy) return false
            pending = work.id to Request(paths, level)
            mutableState.value = if (IndexWorker.running.isLocked) BatchState.WaitingForScan(paths.size) else BatchState.Running(0, paths.size, 0)
        }
        // APPEND, since the previous batch's worker may still be ending after it published its result.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        return true
    }

    /** Stops the batch after the photo it is compressing. The photos done so far stay compressed. */
    fun cancel(context: Context) {
        synchronized(this) {
            // Not taken by the worker yet: nothing was done.
            pending?.let { (_, request) -> finish(BatchResult(request.paths.size, 0, emptyMap(), 0, emptyList())) }
            pending = null
        }
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /**
     * Puts every original of [result] back from the trash, and its compressed copy in the trash in its place, through
     * [Review.restore]. It waits while a scan or a batch runs, since a restore rewrites rows a scan may be recording.
     * Returns the originals that stayed in the trash, because their path holds a file that is not their copy any more.
     */
    suspend fun undo(context: Context, result: BatchResult): List<ImageRef> {
        val app = context.applicationContext as CribrumApp
        return undo(Review(app.index, app.trash), result)
    }

    internal suspend fun undo(review: Review, result: BatchResult): List<ImageRef> = IndexWorker.running.withLock {
        val restored = review.restore(result.originals).toSet()
        mutableState.compareAndSet(BatchState.Finished(result), BatchState.Idle)
        result.originals.filterNot { it in restored }
    }

    /** The request [start] made for the worker [id], once. A rerun of an older worker gets nothing. */
    internal fun take(id: UUID): Request? = synchronized(this) {
        pending?.takeIf { it.first == id }?.second?.also { pending = null }
    }

    internal fun publish(state: BatchState) {
        mutableState.value = state
    }

    internal fun finish(result: BatchResult) = publish(BatchState.Finished(result))

    /** For tests: the state is process-wide. */
    internal fun reset() = synchronized(this) {
        pending = null
        mutableState.value = BatchState.Idle
    }
}

/**
 * Runs the batch [CompressBatch.start] asked for, in the foreground with a notification. It holds the scan's lock for
 * the whole batch, so it waits for a running scan and a scan does not start under it.
 */
open class CompressWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val app get() = applicationContext as CribrumApp
    protected open val index: ImageIndex get() = app.index
    protected open val trash: AppTrash get() = app.trash
    private val compressor by lazy { Compressor.forDevice(applicationContext, trash, index) }

    /** How long a pause for the heat waits before it reads the heat again, at least the 10 s the headroom needs. */
    protected open val coolDownMs = 10_000L

    private var isForeground = false
    private var headroom: Float? = null

    override suspend fun doWork(): Result {
        val request = CompressBatch.take(id) ?: return Result.success()
        val total = request.paths.size
        var compressed = 0
        val refused = mutableMapOf<Refusal, Int>()
        var saved = 0L
        val originals = mutableListOf<ImageRef>()
        try {
            try {
                setForegroundInfo(foregroundInfo(BatchState.Running(0, total, 0)))
                isForeground = true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Foreground service start refused, continuing in background", e)
            }
            if (!IndexWorker.running.tryLock()) {
                publish(BatchState.WaitingForScan(total))
                IndexWorker.running.lock()
            }
            try {
                val images = index.images()
                runCatching { trash.discardCopiesIn(images.mapNotNullTo(HashSet()) { File(it.path).parentFile }) }
                    .onFailure { Log.w(TAG, "Could not sweep the copies of killed compressions", it) }
                val byPath = images.associateBy { it.path }
                for ((done, path) in request.paths.withIndex()) {
                    while (heat() == HeatLevel.SEVERE) {
                        publish(BatchState.Running(done, total, saved, paused = true))
                        delay(coolDownMs)
                    }
                    currentCoroutineContext().ensureActive()
                    publish(BatchState.Running(done, total, saved))
                    val image = byPath[path]
                    if (image == null) {
                        // The index does not hold it, so there is no size and mtime to check the file against.
                        refused.merge(Refusal.FAILED, 1, Int::plus)
                        continue
                    }
                    when (val outcome = compress(image, request.level)) {
                        is Compression.Done -> {
                            compressed++
                            saved += image.size - outcome.copy.size
                            originals += image
                        }
                        is Compression.Refused -> {
                            refused.merge(outcome.reason, 1, Int::plus)
                            if (outcome.reason == Refusal.LEFT_IN_TRASH) originals += image
                        }
                    }
                }
            } finally {
                IndexWorker.running.unlock()
            }
        } finally {
            // Before the result: once it is out, the next batch can start and post its own notification under this id.
            applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            CompressBatch.finish(BatchResult(total, compressed, refused, saved, originals))
        }
        return Result.success()
    }

    /** Not cancellable, so a cancel lets the photo it is on finish. */
    protected open suspend fun compress(image: ImageRef, level: CompressionLevel): Compression = compressor.compress(image, level)

    /** The worse of the thermal status and the headroom, as the scan's heat line reads them (docs/design.md §4). */
    protected open fun heat(): HeatLevel? {
        val power = applicationContext.getSystemService(PowerManager::class.java)
        // NaN when read again within 10 seconds: the last good reading stays.
        power.getThermalHeadroom(0).takeIf { it > 0 }?.let { headroom = it }
        return heatOf(power.currentThermalStatus, headroom)?.level
    }

    protected open suspend fun setForegroundInfo(info: ForegroundInfo) = setForeground(info)

    private suspend fun publish(state: BatchState) {
        CompressBatch.publish(state)
        if (!isForeground) return
        // A stop that came meanwhile has had the notification removed: posting now would bring it back.
        currentCoroutineContext().ensureActive()
        applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, foregroundInfo(state).notification)
    }

    override suspend fun getForegroundInfo() = foregroundInfo(BatchState.Running(0, 0, 0))

    private fun foregroundInfo(state: BatchState): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.compress_channel), NotificationManager.IMPORTANCE_LOW))
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, context.getString(R.string.compress_cancel), WorkManager.getInstance(context).createCancelPendingIntent(id))
        if (state is BatchState.Running) {
            val saved = context.getString(R.string.compress_saved, Formatter.formatShortFileSize(context, state.savedBytes))
            builder.setContentTitle(context.getString(R.string.compress_progress, minOf(state.done + 1, state.total), state.total))
                .setContentText(if (state.paused) context.getString(R.string.compress_paused, saved) else saved)
                .setProgress(state.total, state.done, false)
        } else {
            builder.setContentTitle(context.getString(R.string.compress_waiting))
        }
        // Media processing has a time budget of its own from API 35, so a long batch does not use up the scan's dataSync one.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(NOTIFICATION_ID, builder.build(), type)
    }

    internal companion object {
        const val TAG = "compress"
        const val CHANNEL = "compress"
        const val NOTIFICATION_ID = 2
    }
}
