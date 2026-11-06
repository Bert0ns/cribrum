package io.github.bert0ns.cribrum.ui

import androidx.tracing.Trace
import coil3.ImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import coil3.request.Options
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
 * Gives the request the grid tiles' decoder (see [TileDecoder]) and a `section` trace for its load. Only the grids use it:
 * the full size views load with Coil's default decoder.
 */
internal fun ImageRequest.Builder.tile(section: String): ImageRequest.Builder = decoderFactory(TileDecoder.factory).traced(section)

/**
 * The decoder of the grid tiles (Compress, trash and review): Coil's [BitmapFactoryDecoder] under a limit of its own,
 * [tilePermits]. It is the same decoder, with the same options, so a tile draws and caches as before. The limit is held
 * here, and the factory below it is not limited, so the wait for a permit and the decode are each a trace section.
 */
internal object TileDecoder {
    val factory: Decoder.Factory = Factory(BitmapFactoryDecoder.Factory(Semaphore(Int.MAX_VALUE)), Semaphore(tilePermits(Runtime.getRuntime().availableProcessors())))

    private class Factory(private val inner: Decoder.Factory, private val permits: Semaphore) : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val decoder = inner.create(result, options, imageLoader) ?: return null
            // The size is read for the trace only, so no stat is made when nothing records one.
            val bucket = if (Trace.isEnabled()) sizeBucket(result.source.fileOrNull()?.let { result.source.fileSystem.metadataOrNull(it)?.size }) else "?"
            return PermitDecoder(decoder, permits, bucket)
        }
    }
}

/** [decoder] under [permits]: waits for a permit, then decodes holding it. */
internal class PermitDecoder(private val decoder: Decoder, private val permits: Semaphore, private val bucket: String) : Decoder {
    override suspend fun decode(): DecodeResult? {
        traceSuspend("Tile.wait") { permits.acquire() }
        try {
            return traceSuspend("Tile.decode $bucket") { decoder.decode() }
        } finally {
            permits.release()
        }
    }
}
