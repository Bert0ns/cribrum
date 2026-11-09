package io.github.bert0ns.cribrum.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.tracing.Trace
import androidx.tracing.trace
import coil3.BitmapImage
import coil3.Extras
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.BitmapFactoryDecoder
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ExifOrientationStrategy
import coil3.decode.StaticImageDecoder
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.bitmapConfig
import io.github.bert0ns.cribrum.tracing.traceSuspend
import io.github.bert0ns.cribrum.tracing.traced
import kotlinx.coroutines.sync.Semaphore

/**
 * How many grid tiles decode at once: the cores minus two for the UI and render threads, from 2 to 6. Coil's own limit is 4,
 * which queues the tiles of a Compress screen behind its few largest files. A tile decode is small in memory (its bitmap
 * plus libjpeg's row buffers), unlike a full size view's, so the full size views keep Coil's default.
 */
internal fun tilePermits(cores: Int): Int = (cores - 2).coerceIn(2, 6)

/** The size bucket in a `Tile.decode` section's name: the smallest of 1, 2, 4, ... 32 MB the file fits in, or `>32MB`. */
internal fun sizeBucket(bytes: Long?): String {
    if (bytes == null) return "?"
    var limit = 1L
    while (limit <= 32 && bytes > limit * MB) limit *= 2
    return if (limit > 32) ">32MB" else "<=${limit}MB"
}

private const val MB = 1024L * 1024L

/**
 * Gives the request the grid tiles' decoder (see [TileDecoder]), their disk cache ([TileCache]) and a `section` trace for its
 * load. Only the grids use it: the full size views load with Coil's default fetcher and decoder.
 */
internal fun ImageRequest.Builder.tile(section: String): ImageRequest.Builder = fetcherFactory(TileCache.fetcherFactory, Uri::class).decoderFactory(TileDecoder.factory).traced(section)

/**
 * The decoder of the grid tiles (Compress, trash and review), under a limit of its own, [tilePermits]. It picks the decoder
 * the way Coil's default loader does on Android 10 and later: [StaticImageDecoder] (`ImageDecoder`) when it applies, else
 * [BitmapFactoryDecoder], with Coil's default options. So a tile draws and caches as before. The limit is held here, and
 * the factories below it are not limited, so the wait for a permit and the decode are each a trace section. It also stores
 * a cache miss's bitmap in [TileCache], see [PermitDecoder].
 */
internal object TileDecoder {
    val factory: Decoder.Factory = Factory(
        listOf(
            StaticImageDecoder.Factory(Semaphore(Int.MAX_VALUE)),
            BitmapFactoryDecoder.Factory(Semaphore(Int.MAX_VALUE), ExifOrientationStrategy.RESPECT_PERFORMANCE),
        ),
        Semaphore(tilePermits(Runtime.getRuntime().availableProcessors())),
    )

    private class Factory(private val inner: List<Decoder.Factory>, private val permits: Semaphore) : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val miss = result.source.metadata as? TileCache.Miss
            // A hardware bitmap cannot be encoded without a copy back on the render thread, so a miss decodes in software.
            val hardware = miss != null && options.bitmapConfig == Bitmap.Config.HARDWARE
            val decodeOptions = if (hardware) options.copy(extras = Extras.Builder(options.extras).set(Extras.Key.bitmapConfig, Bitmap.Config.ARGB_8888).build()) else options
            val decoder = inner.firstNotNullOfOrNull { it.create(result, decodeOptions, imageLoader) } ?: return null
            // The size is read for the trace only, so no stat is made when nothing records one.
            val bucket = if (Trace.isEnabled()) sizeBucket(result.source.fileOrNull()?.let { result.source.fileSystem.metadataOrNull(it)?.size }) else "?"
            return PermitDecoder(decoder, permits, bucket, hit = result.source.metadata === TileCache.Hit, miss = miss, hardware = hardware)
        }
    }
}

/**
 * [decoder] under [permits]: waits for a permit, then decodes holding it. A [hit] decodes a thumbnail, which is sampled from
 * the original: it says so, as the original's decode did, so Coil's memory cache never serves it to a request for the whole
 * image. A [miss] stores its bitmap after the permit is given back, and only a sampled, opaque one: an image no larger than
 * the tile gains nothing, and a JPEG has no alpha. A [hardware] miss decoded in software, and gets the hardware copy the
 * request asked for, as the decoders make it.
 */
internal class PermitDecoder(
    private val decoder: Decoder,
    private val permits: Semaphore,
    private val bucket: String,
    private val hit: Boolean = false,
    private val miss: TileCache.Miss? = null,
    private val hardware: Boolean = false,
) : Decoder {
    override suspend fun decode(): DecodeResult? {
        traceSuspend("Tile.wait") { permits.acquire() }
        val result = try {
            traceSuspend("Tile.decode $bucket") { decoder.decode() }
        } finally {
            permits.release()
        }
        return when {
            result == null -> null
            hit -> DecodeResult(result.image, isSampled = true)
            miss != null -> stored(result, miss)
            else -> result
        }
    }

    private fun stored(result: DecodeResult, miss: TileCache.Miss): DecodeResult {
        val bitmap = (result.image as? BitmapImage)?.bitmap ?: return result
        if (result.isSampled && !bitmap.hasAlpha()) trace("Tile.store") { miss.store(bitmap) }
        if (!hardware) return result
        val copy = bitmap.copy(Bitmap.Config.HARDWARE, false) ?: return result
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) bitmap.gainmap?.let { copy.gainmap = it }
        return DecodeResult(copy.asImage(), result.isSampled)
    }
}
