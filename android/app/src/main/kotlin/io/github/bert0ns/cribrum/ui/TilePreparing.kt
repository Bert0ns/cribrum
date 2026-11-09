// Fills the grid tiles' disk cache for the first two Compress pages after a scan (docs/design.md §8).
package io.github.bert0ns.cribrum.ui

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import io.github.bert0ns.cribrum.CribrumApp
import io.github.bert0ns.cribrum.compress.CompressBatch
import io.github.bert0ns.cribrum.pipeline.IndexWorker
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

object TilePreparing {
    const val WORK = "tile-prepare"

    /** How many photos it prepares: the first two Compress pages. */
    internal const val PHOTOS = 2 * COMPRESS_PAGE_SIZE

    /** After a scan ends, with no lock held. REPLACE: the list of the newest scan is the one to prepare. */
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<TilePrepareWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request)
    }
}

/**
 * Decodes the tiles of the first two Compress pages into [TileCache], largest first, so the first Compress screen after a
 * scan decodes thumbnails and not originals of up to 200 MP. It decodes at the size of the last Compress tile, so its
 * thumbnails are the ones the page asks for, and does nothing before the page has shown a tile. One photo at a time, on one
 * thread of background priority. Between two photos it stops when a scan starts (the scan's end enqueues it again) or
 * the phone is hot, and waits for a compression batch to end, as WorkManager retries it.
 */
open class TilePrepareWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private var headroom: Float? = null

    override suspend fun doWork(): Result {
        val size = tileSize() ?: return Result.success()
        val files = photos().take(TilePreparing.PHOTOS)
        Executors.newSingleThreadExecutor {
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                it.run()
            }, "tile-prepare")
        }
            .asCoroutineDispatcher()
            .use { thread ->
                for (file in files) {
                    when {
                        CompressBatch.busy -> return Result.retry()
                        IndexWorker.running.isLocked -> return Result.success()
                        hot() -> return Result.success()
                    }
                    prepare(file, size, thread)
                }
            }
        return Result.success()
    }

    protected open fun tileSize(): Size? = TileCache.get(applicationContext).compressTileSize

    /** The photos of the Compress list, largest first, as the page lists them. */
    protected open suspend fun photos(): List<File> = (applicationContext as CribrumApp).index.compressible(Build.VERSION.SDK_INT)
        .filter { it.info.marker == null }
        .map { File(it.path) }

    /** Loads [file] as a Compress tile of [size] would, so a miss stores its thumbnail, and skips it when the cache holds it. */
    protected open suspend fun prepare(file: File, size: Size, thread: CoroutineContext) {
        val cache = TileCache.get(applicationContext)
        val key = cache.key(file, size, Scale.FILL, Precision.INEXACT) ?: return
        if (cache.contains(key)) return
        // The scale and precision of AsyncImage with ContentScale.Crop. No memory cache and no hardware bitmap: nothing shows it.
        val request = ImageRequest.Builder(applicationContext).data(file).tile("Tile.prepare")
            .size(size).scale(Scale.FILL).precision(Precision.INEXACT)
            .memoryCachePolicy(CachePolicy.DISABLED).allowHardware(false)
            .fetcherCoroutineContext(thread).decoderCoroutineContext(thread)
            .build()
        SingletonImageLoader.get(applicationContext).execute(request)
    }

    /** Moderate heat or worse, as the scan's heat line reads it (docs/design.md §4). */
    protected open fun hot(): Boolean {
        val power = applicationContext.getSystemService(PowerManager::class.java)
        // NaN when read again within 10 seconds: the last good reading stays.
        power.getThermalHeadroom(0).takeIf { it > 0 }?.let { headroom = it }
        val level = heatOf(power.currentThermalStatus, headroom)?.level ?: return false
        return level >= HeatLevel.MODERATE
    }
}
