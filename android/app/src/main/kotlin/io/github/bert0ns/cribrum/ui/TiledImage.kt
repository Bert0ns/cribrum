// A photo that zooms to its full resolution (docs/design.md §8): a base image from Coil, and tiles of the visible part read with BitmapRegionDecoder.
package io.github.bert0ns.cribrum.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toAndroidRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.withMatrix
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Scale
import io.github.bert0ns.cribrum.imaging.orientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The side of a tile, in decoded pixels. */
private const val TILE = 512

/** The longest side Coil draws the whole photo at, below the tiles. */
private const val BASE_PIXELS = 2048

/** How far a zoom goes: two screen pixels for one of the original, so single pixels and JPEG blocks show. */
private const val MAX_SHARE = 2f

/** A part of a photo to decode: [rect] in the file's own pixels, before its EXIF orientation, read at [sample]. */
internal data class Tile(val sample: Int, val rect: IntRect)

/**
 * A zoom one or more [TiledImage]s share, so the same part of the photo shows on each. [image] is the
 * original's size as displayed; the zoom stops at [MAX_SHARE] times its full resolution.
 */
internal class Zoom(private val image: IntSize) {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)

    /** The zoom as a share of the original's full resolution: 1 is one screen pixel for each pixel of the photo. */
    val share get() = scale * fitScale(image, size)

    /** Keeps the point under [centroid] where it is while zooming, then pans, and never shows past the photo's edge. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        // The event that lifts the last finger has no finger that was down before: no centroid, and NaN would reach the layer.
        if (centroid == Offset.Unspecified) return
        val next = (scale * zoom).coerceIn(1f, max(1f, MAX_SHARE / fitScale(image, size)))
        val moved = centroid - (centroid - offset) * (next / scale) + pan
        scale = next
        offset = Offset(moved.x.coerceIn(size.width * (1 - next), 0f), moved.y.coerceIn(size.height * (1 - next), 0f))
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

/**
 * Pinch zooms [zoom], and one finger pans once zoomed. A single finger on a photo at rest is left to
 * the parent, the summary's scroll or the pager. A double tap goes back to the whole photo.
 */
internal fun Modifier.zoomGestures(zoom: Zoom, onTap: (() -> Unit)? = null) = this
    .pointerInput(zoom, onTap) { detectTapGestures(onTap = onTap?.let { { _ -> it() } }, onDoubleTap = { zoom.reset() }) }
    .pointerInput(zoom) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                if (event.changes.size > 1 || zoom.scale > 1f) {
                    zoom.transform(event.calculateCentroid(), event.calculatePan(), event.calculateZoom())
                    event.changes.forEach { if (it.pressed) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }

/** The preview file keeps its name from one photo to the next, so the file's date is what tells Coil it changed. */
internal fun previewCacheKey(file: File) = "${file.path}:${file.lastModified()}"

/**
 * [file] fitted to the size of this composable, zoomed by [zoom]. Coil draws the whole photo at up
 * to [BASE_PIXELS]; once the zoom needs more, the visible part is decoded again in tiles, at the
 * resolution the zoom shows. Tiles that leave the view are dropped, and closing the view stops the
 * work and the decoder. While [tiled] is false the file is not opened for tiles at all, as the
 * summary's original waits while the preview encodes. [open] is there for tests.
 */
@Composable
internal fun TiledImage(file: File, zoom: Zoom, modifier: Modifier = Modifier, tiled: Boolean = true, open: (File) -> RegionDecoder? = TileSource::open) {
    val key = previewCacheKey(file)
    var source by remember(key) { mutableStateOf<RegionDecoder?>(null) }
    val tiles = remember(key) { mutableStateMapOf<Tile, Bitmap>() }
    LaunchedEffect(key, tiled) {
        if (!tiled) return@LaunchedEffect
        // On IO from the start, so the decoder that opens is always closed, also when the view leaves mid-decode.
        withContext(Dispatchers.IO) {
            val opened = open(file) ?: return@withContext
            source = opened
            TileLoader(opened, tiles).load(snapshotFlow { visibleTiles(opened.raw, opened.orientation, zoom.size, zoom.scale, zoom.offset) })
        }
    }
    Box(modifier.onSizeChanged { zoom.size = it }) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.offset.x
                translationY = zoom.offset.y
            },
        ) {
            AsyncImage(
                ImageRequest.Builder(LocalContext.current).data(file).memoryCacheKey(key).diskCachePolicy(CachePolicy.DISABLED).size(BASE_PIXELS).scale(Scale.FIT).build(),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Canvas(Modifier.fillMaxSize()) {
                val shown = source ?: return@Canvas
                if (tiles.isEmpty()) return@Canvas
                val turned = turnedSize(shown.raw, shown.orientation)
                val fit = fitScale(turned, IntSize(size.width.toInt(), size.height.toInt()))
                val toView = Matrix().apply {
                    setValues(turn(shown.raw, shown.orientation) + floatArrayOf(0f, 0f, 1f))
                    postScale(fit, fit)
                    postTranslate((size.width - turned.width * fit) / 2, (size.height - turned.height * fit) / 2)
                }
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.withMatrix(toView) {
                        for ((tile, bitmap) in tiles.entries.sortedByDescending { it.key.sample }) drawBitmap(bitmap, null, tile.rect.toAndroidRect(), tilePaint) // coarser first, under the finer
                    }
                }
            }
        }
    }
}

private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)

/** What [TileLoader] reads regions from: a [TileSource] on a file, or a fake in tests. [raw] is its size before the EXIF [orientation]. */
internal interface RegionDecoder {
    val raw: IntSize
    val orientation: Int

    /** [rect] at [sample], or null when it cannot be read or does not fit in memory next to the rest. */
    fun decode(rect: IntRect, sample: Int): Bitmap?

    /** Waits for a decode in progress, then lets go of the file. */
    fun close()
}

/** Decoded pixels above which the tiles of one view are read a row of tiles at a time, the row nearest the centre first. */
private const val MAX_BOX_BYTES = 100L shl 20

/**
 * Keeps [tiles] to what each view of the photo needs. A JPEG region decode reads every row of the
 * file above the region at full width, whatever its size, so the missing tiles of a view are read
 * in one decode of their bounding box and cut into the grid: one pass over the file per view, not
 * one per tile. A box over [maxBoxBytes] is read a row of tiles at a time instead, centre first.
 */
internal class TileLoader(private val decoder: RegionDecoder, private val tiles: MutableMap<Tile, Bitmap>, private val maxBoxBytes: Long = MAX_BOX_BYTES) {
    /** Runs until cancelled, then closes the decoder. A view whose decode fails clears the tiles, and the next view tries again. */
    suspend fun load(views: Flow<List<Tile>>) {
        try {
            views.conflate().collect { wanted ->
                evict(wanted)
                val missing = wanted.filter { it !in tiles }
                if (missing.isEmpty()) return@collect
                for (part in parts(missing, wanted)) {
                    val cut = decodeBox(part)
                    if (cut == null) {
                        tiles.clear() // out of memory or an unreadable region: the base image stays until the view changes
                        return@collect
                    }
                    tiles.putAll(cut)
                    evict(wanted)
                }
            }
        } finally {
            decoder.close()
        }
    }

    /**
     * Drops the tiles [wanted] does not hold. A tile of another sample stays, drawn under the new
     * ones, until the tiles of [wanted] over it are loaded, so crossing a power of two never shows
     * the base image in between. That holds up to about twice the tiles while the zoom switches.
     */
    private fun evict(wanted: List<Tile>) {
        val keep = wanted.toSet()
        val sample = wanted.firstOrNull()?.sample
        val drop = tiles.keys.filter { old -> old !in keep && (old.sample == sample || wanted.none { it.rect.overlaps(old.rect) && it !in tiles }) }
        tiles.keys.removeAll(drop.toSet())
    }

    /** [missing] as one box, or as its rows nearest the centre of [wanted] first when the box is too large. */
    private fun parts(missing: List<Tile>, wanted: List<Tile>): List<List<Tile>> {
        val box = bounds(missing)
        val sample = missing.first().sample
        val bytes = 4L * ceil(box.width / sample.toFloat()).toLong() * ceil(box.height / sample.toFloat()).toLong()
        if (bytes <= maxBoxBytes) return listOf(missing)
        val centre = bounds(wanted).center.y
        return missing.groupBy { it.rect.top }.values.sortedBy { abs(it.first().rect.center.y - centre) }
    }

    /** One decode of the bounding box of [part], cut into its tiles; the box itself is never drawn, so it is recycled. */
    private fun decodeBox(part: List<Tile>): Map<Tile, Bitmap>? {
        val box = bounds(part)
        val sample = part.first().sample
        val decoded = decoder.decode(box, sample) ?: return null
        return try {
            part.associateWith {
                val x = (it.rect.left - box.left) / sample
                val y = (it.rect.top - box.top) / sample
                Bitmap.createBitmap(decoded, x, y, min(TILE, decoded.width - x), min(TILE, decoded.height - y))
            }.also { cut -> if (cut.values.none { it === decoded }) decoded.recycle() }
        } catch (_: OutOfMemoryError) {
            decoded.recycle()
            null
        }
    }
}

private fun bounds(tiles: List<Tile>) = IntRect(tiles.minOf { it.rect.left }, tiles.minOf { it.rect.top }, tiles.maxOf { it.rect.right }, tiles.maxOf { it.rect.bottom })

/** A file open for tiles. */
internal class TileSource private constructor(private val decoder: BitmapRegionDecoder, override val orientation: Int) : RegionDecoder {
    override val raw = IntSize(decoder.width, decoder.height)

    override fun decode(rect: IntRect, sample: Int): Bitmap? = try {
        decoder.decodeRegion(rect.toAndroidRect(), BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    override fun close() = decoder.recycle()

    companion object {
        /** Null when the file is gone or is not an image the decoder reads in regions. */
        fun open(file: File): TileSource? = try {
            // The decoder keeps its own copy of the descriptor, so this one closes here and the decoder holds the file until close().
            val decoder: BitmapRegionDecoder? = if (Build.VERSION.SDK_INT >= 31) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { BitmapRegionDecoder.newInstance(it) }
            } else {
                @Suppress("DEPRECATION")
                FileInputStream(file).use { BitmapRegionDecoder.newInstance(it.fd, false) }
            }
            decoder?.let { TileSource(it, orientation(file)) }
        } catch (_: IOException) {
            null
        }
    }
}

/** The scale that fits [content] inside [view], as `ContentScale.Fit` does. */
private fun fitScale(content: IntSize, view: IntSize) = min(view.width.toFloat() / content.width, view.height.toFloat() / content.height)

/** The size of a file of [raw] pixels once turned by its EXIF [orientation]. */
private fun turnedSize(raw: IntSize, orientation: Int) = if (orientation in 5..8) IntSize(raw.height, raw.width) else raw

/**
 * The EXIF [orientation] as an affine map from the file's pixels to the displayed ones,
 * `x' = a x + b y + c` and `y' = d x + e y + f`, as `[a, b, c, d, e, f]`: the same turns as `Imaging.transpose`.
 */
private fun turn(raw: IntSize, orientation: Int): FloatArray {
    val w = raw.width.toFloat()
    val h = raw.height.toFloat()
    return when (orientation) {
        2 -> floatArrayOf(-1f, 0f, w, 0f, 1f, 0f) // mirrored
        3 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h) // rotated 180
        4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, h) // flipped
        5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f) // transposed
        6 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f) // rotated 90 clockwise
        7 -> floatArrayOf(0f, -1f, h, -1f, 0f, w) // transversed
        8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w) // rotated 90 counterclockwise
        else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
    }
}

/** [rect] in displayed pixels back in the file's own: the turn's linear part is a signed permutation, so its inverse is its transpose. */
private fun toRaw(rect: Rect, raw: IntSize, orientation: Int): Rect {
    val t = turn(raw, orientation)
    fun back(p: Offset) = Offset(t[0] * (p.x - t[2]) + t[3] * (p.y - t[5]), t[1] * (p.x - t[2]) + t[4] * (p.y - t[5]))
    val p = back(rect.topLeft)
    val q = back(rect.bottomRight)
    return Rect(min(p.x, q.x), min(p.y, q.y), max(p.x, q.x), max(p.y, q.y))
}

/**
 * The tiles that cover what a [view] shows of a file of [raw] pixels, fitted and zoomed by [scale] and
 * [offset] as [TiledImage] draws it. The sample is the largest power of two that still gives a screen
 * pixel at least one decoded pixel. None at rest, and none until that sample is finer than Coil's
 * [BASE_PIXELS] image, which shows the photo until then.
 */
internal fun visibleTiles(raw: IntSize, orientation: Int, view: IntSize, scale: Float, offset: Offset): List<Tile> {
    if (scale <= 1f || view.width == 0 || view.height == 0) return emptyList()
    val turned = turnedSize(raw, orientation)
    val fit = fitScale(turned, view)
    val origin = Offset((view.width - turned.width * fit) / 2, (view.height - turned.height * fit) / 2)
    val shown = Rect((-offset / scale - origin) / fit, ((Offset(view.width.toFloat(), view.height.toFloat()) - offset) / scale - origin) / fit)
    val region = toRaw(shown, raw, orientation).intersect(Rect(0f, 0f, raw.width.toFloat(), raw.height.toFloat()))
    if (region.isEmpty) return emptyList()
    val sample = Integer.highestOneBit(max(1, (1 / (fit * scale)).toInt()))
    if (sample.toLong() * BASE_PIXELS >= max(raw.width, raw.height)) return emptyList()
    val step = TILE * sample
    return (floor(region.top / step).toInt() until ceil(region.bottom / step).toInt()).flatMap { row ->
        (floor(region.left / step).toInt() until ceil(region.right / step).toInt()).map { column ->
            Tile(sample, IntRect(column * step, row * step, min((column + 1) * step, raw.width), min((row + 1) * step, raw.height)))
        }
    }
}
