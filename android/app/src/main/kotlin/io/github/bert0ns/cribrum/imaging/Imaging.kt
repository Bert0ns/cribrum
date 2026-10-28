// Pixel loading shared by the detectors that look at image content. Mirrors
// lab/src/cribrum_lab/imaging.py, step by step, so measures computed on the pixels match the lab's:
// Pillow's reduced JPEG decoding, EXIF orientation, ITU-R 601 luminance and Lanczos thumbnail.
package io.github.bert0ns.cribrum.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import io.github.bert0ns.cribrum.metadata.UnreadableImage
import java.io.File
import java.io.IOException
import java.nio.IntBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Luminance in [0, 255], row by row. */
class Gray(val width: Int, val height: Int, val pixels: FloatArray) {
    operator fun get(x: Int, y: Int): Float = pixels[y * width + x]
}

/**
 * Decodes an image as luminance in [0, 255], downscaled to fit [maxSide].
 *
 * Measures computed on it depend on [maxSide]: changing it means bumping the version of every
 * detector that uses it.
 */
fun loadGrayscale(file: File, maxSide: Int): Gray {
    val (width, height, argb) = decode(file, maxSide)
    return thumbnail(transpose(luminance(width, height, argb), orientation(file)), maxSide)
}

/** Decoded pixels as unpremultiplied ARGB, row by row, before EXIF orientation. */
internal data class Argb(val width: Int, val height: Int, val pixels: IntArray)

/**
 * More pixels than this and a non-JPEG image decodes at a sample size: the bitmap, the [Argb] array
 * and a float plane are held at once, 12 bytes a pixel, and a 4320 x 7680 PNG did not fit the heap.
 * 16 MP leaves every photo and screenshot at full size, so what the lab computes on them still matches.
 */
internal const val MAX_PIXELS = 16_000_000

/**
 * Decodes with Pillow's draft: a JPEG decodes straight at 1/2, 1/4 or 1/8 while both sides stay
 * >= [draftSide]. Another format has no draft, so above [maxPixels] it is sampled by the same rule.
 * Alpha is left as stored, since Pillow drops it when it converts to RGB or L. A wide gamut image
 * keeps its stored values, as Pillow does: converted to sRGB they moved the embeddings off the lab's,
 * to a cosine of 0.9993 on a saturated test image.
 */
internal fun decode(file: File, draftSide: Int, maxPixels: Int = MAX_PIXELS): Argb {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UnreadableImage(file)
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPremultiplied = false
        val draft = draftScale(bounds.outWidth, bounds.outHeight, draftSide)
        inSampleSize = if (bounds.outMimeType == "image/jpeg") draft else min(draft, sampleFor(bounds.outWidth, bounds.outHeight, maxPixels))
    }
    val bitmap = BitmapFactory.decodeFile(file.path, options) ?: throw UnreadableImage(file)
    try {
        val argb = IntArray(bitmap.width * bitmap.height)
        // A 16-bit or 10-bit image comes in another config, which the raw copy does not fit: getPixels reads those.
        if (bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.colorSpace?.isSrgb == false) {
            // getPixels converts to sRGB, where Pillow reads the stored values and ignores the profile.
            bitmap.copyPixelsToBuffer(IntBuffer.wrap(argb))
            for (i in argb.indices) argb[i] = (argb[i] and 0xff00ff00.toInt()) or (argb[i] shl 16 and 0xff0000) or (argb[i] shr 16 and 0xff) // RGBA bytes to ARGB
        } else {
            bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        return Argb(bitmap.width, bitmap.height, argb)
    } finally {
        bitmap.recycle()
    }
}

/** The smallest power of two that brings the image down to [maxPixels]. */
private fun sampleFor(width: Int, height: Int, maxPixels: Int): Int = generateSequence(1) { it * 2 }.first { width.toLong() * height / (it.toLong() * it) <= maxPixels }

internal fun draftScale(width: Int, height: Int, maxSide: Int): Int {
    val scale = min(width / maxSide, height / maxSide)
    return listOf(8, 4, 2).firstOrNull { scale >= it } ?: 1
}

/** Pillow's RGB to L: ITU-R 601-2 luma in 16-bit fixed point, rounded. */
private fun luminance(width: Int, height: Int, argb: IntArray): Gray {
    val pixels = FloatArray(argb.size) { i ->
        val c = argb[i]
        (((c shr 16 and 0xff) * 19595 + (c shr 8 and 0xff) * 38470 + (c and 0xff) * 7471 + 0x8000) shr 16).toFloat()
    }
    return Gray(width, height, pixels)
}

/**
 * One 8-bit channel of [Argb], shifted by 16 for red, 8 for green, 0 for blue, already turned
 * as [transpose] does. It never builds the plane before the turn, which would double the memory.
 */
internal fun channel(image: Argb, shift: Int, orientation: Int): Gray = turn(image.width, image.height, orientation) { (image.pixels[it] shr shift and 0xff).toFloat() }

// shortcut: a HEIC with a container rotation (irot) and EXIF orientation may turn twice, in here and in readMetadata. Robolectric cannot
// decode HEIC (decodeFile leaves the bounds at -1), so no unit test sees it. Check on a device; if it turns twice, skip the EXIF turn for image/heif.
internal fun orientation(file: File): Int = try {
    ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
} catch (_: IOException) {
    ExifInterface.ORIENTATION_NORMAL
}

/** Pillow's exif_transpose: turns the pixels so the image is as displayed. */
internal fun transpose(gray: Gray, orientation: Int): Gray = if (orientation !in 2..8) gray else turn(gray.width, gray.height, orientation) { gray.pixels[it] }

/** The turned image of [w] x [h] pixels, [pixel] giving the value at an index of the original. */
private inline fun turn(w: Int, h: Int, orientation: Int, pixel: (Int) -> Float): Gray {
    if (orientation !in 2..8) return Gray(w, h, FloatArray(w * h) { pixel(it) })
    val swapped = orientation >= 5
    val outW = if (swapped) h else w
    val outH = if (swapped) w else h
    val out = FloatArray(w * h)
    for (y in 0 until outH) {
        for (x in 0 until outW) {
            val (sx, sy) = when (orientation) {
                2 -> w - 1 - x to y // mirrored
                3 -> w - 1 - x to h - 1 - y // rotated 180
                4 -> x to h - 1 - y // flipped
                5 -> y to x // transposed
                6 -> y to h - 1 - x // rotated 90 clockwise
                7 -> w - 1 - y to h - 1 - x // transversed
                else -> w - 1 - y to x // 8: rotated 90 counterclockwise
            }
            out[y * outW + x] = pixel(sy * w + sx)
        }
    }
    return Gray(outW, outH, out)
}

/** Pillow's thumbnail((maxSide, maxSide), LANCZOS) with its default reducing gap of 2. */
internal fun thumbnail(gray: Gray, maxSide: Int): Gray {
    if (maxSide >= gray.width && maxSide >= gray.height) return gray
    val (outW, outH) = fitted(gray.width, gray.height, maxSide)
    if (outW == gray.width && outH == gray.height) return gray
    val factorX = max((gray.width.toDouble() / outW / REDUCING_GAP).toInt(), 1)
    val factorY = max((gray.height.toDouble() / outH / REDUCING_GAP).toInt(), 1)
    if (factorX == 1 && factorY == 1) {
        return resample(gray, outW, outH, 0.0, 0.0, gray.width.toDouble(), gray.height.toDouble())
    }
    val reduced = reduce(gray, factorX, factorY)
    return resample(reduced, outW, outH, 0.0, 0.0, gray.width.toDouble() / factorX, gray.height.toDouble() / factorY)
}

/**
 * Pillow's Image.resize for mode F (32-bit float): two-pass Lanczos with double coefficients
 * and no integer clipping or reducing gap.
 */
internal fun resize(gray: Gray, outW: Int, outH: Int): Gray {
    if (outW == gray.width && outH == gray.height) return gray
    val horizontal = floatCoefficients(gray.width, outW)
    val vertical = floatCoefficients(gray.height, outH)
    val firstRow = vertical.first[0]
    val lastRow = vertical.first[outH - 1] + vertical.weights[outH - 1].size
    val intermediate = FloatArray(outW * (lastRow - firstRow))
    for (y in firstRow until lastRow) {
        for (x in 0 until outW) {
            val w = horizontal.weights[x]
            val firstX = horizontal.first[x]
            var sum = 0.0
            for (i in w.indices) sum += gray[firstX + i, y] * w[i]
            intermediate[(y - firstRow) * outW + x] = sum.toFloat()
        }
    }
    val out = FloatArray(outW * outH)
    for (y in 0 until outH) {
        for (x in 0 until outW) {
            val w = vertical.weights[y]
            val firstY = vertical.first[y]
            var sum = 0.0
            for (i in w.indices) sum += intermediate[(firstY - firstRow + i) * outW + x] * w[i]
            out[y * outW + x] = sum.toFloat()
        }
    }
    return Gray(outW, outH, out)
}

private const val REDUCING_GAP = 2.0

/** The size that keeps the aspect ratio inside maxSide x maxSide, rounded the way Pillow rounds it. */
internal fun fitted(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
    val aspect = width.toDouble() / height
    fun roundAspect(number: Double, key: (Int) -> Double): Int {
        val low = floor(number).toInt()
        val high = ceil(number).toInt()
        return max(if (key(high) < key(low)) high else low, 1)
    }
    return if (maxSide.toDouble() / maxSide >= aspect) {
        roundAspect(maxSide * aspect) { Math.abs(aspect - it.toDouble() / maxSide) } to maxSide
    } else {
        maxSide to roundAspect(maxSide / aspect) { if (it == 0) 0.0 else Math.abs(aspect - maxSide.toDouble() / it) }
    }
}

/** Pillow's reduce: the rounded mean of each factorX x factorY block, partial blocks at the edges too. */
internal fun reduce(gray: Gray, factorX: Int, factorY: Int): Gray {
    val outW = (gray.width + factorX - 1) / factorX
    val outH = (gray.height + factorY - 1) / factorY
    val out = FloatArray(outW * outH)
    for (y in 0 until outH) {
        for (x in 0 until outW) {
            var sum = 0L
            var count = 0
            for (yy in y * factorY until min((y + 1) * factorY, gray.height)) {
                for (xx in x * factorX until min((x + 1) * factorX, gray.width)) {
                    sum += gray[xx, yy].toLong()
                    count++
                }
            }
            out[y * outW + x] = ((sum + count / 2) / count).toFloat()
        }
    }
    return Gray(outW, outH, out)
}

private const val LANCZOS_SUPPORT = 3.0
private const val PRECISION_BITS = 32 - 8 - 2

private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(x * Math.PI) / (x * Math.PI)

private fun lanczos(x: Double): Double = if (x >= -LANCZOS_SUPPORT && x < LANCZOS_SUPPORT) sinc(x) * sinc(x / 3) else 0.0

/** A Pillow resampling filter: its support at scale 1 and its kernel. */
internal class Filter(val support: Double, val kernel: (Double) -> Double)

internal val LANCZOS = Filter(LANCZOS_SUPPORT, ::lanczos)
internal val BILINEAR = Filter(1.0) { x -> if (Math.abs(x) < 1.0) 1.0 - Math.abs(x) else 0.0 }

/** Per output pixel: the first input pixel it reads and its fixed-point weights. */
private class Coefficients(val first: IntArray, val weights: Array<IntArray>)

/** Pillow's precompute_coeffs and normalize_coeffs_8bpc. */
private fun coefficients(inSize: Int, in0: Double, in1: Double, outSize: Int, filter: Filter): Coefficients {
    val scale = (in1 - in0) / outSize
    val filterScale = max(scale, 1.0)
    val support = filter.support * filterScale
    val first = IntArray(outSize)
    val weights = Array(outSize) { xx ->
        val center = in0 + (xx + 0.5) * scale
        val xmin = max((center - support + 0.5).toInt(), 0)
        val xmax = min((center + support + 0.5).toInt(), inSize) - xmin
        val k = DoubleArray(max(xmax, 0)) { x -> filter.kernel((x + xmin - center + 0.5) / filterScale) }
        val total = k.sum()
        first[xx] = xmin
        IntArray(k.size) { x ->
            val w = if (total != 0.0) k[x] / total else k[x]
            (if (w < 0) w * (1 shl PRECISION_BITS) - 0.5 else w * (1 shl PRECISION_BITS) + 0.5).toInt()
        }
    }
    return Coefficients(first, weights)
}

/** Per output pixel: the first input pixel it reads and its floating-point weights. */
private class FloatCoefficients(val first: IntArray, val weights: Array<DoubleArray>)

private fun floatCoefficients(inSize: Int, outSize: Int): FloatCoefficients {
    val scale = inSize.toDouble() / outSize
    val filterScale = max(scale, 1.0)
    val support = LANCZOS_SUPPORT * filterScale
    val first = IntArray(outSize)
    val weights = Array(outSize) { xx ->
        val center = (xx + 0.5) * scale
        val xmin = max((center - support + 0.5).toInt(), 0)
        val xmax = min((center + support + 0.5).toInt(), inSize) - xmin
        val k = DoubleArray(max(xmax, 0)) { x -> lanczos((x + xmin - center + 0.5) / filterScale) }
        val total = k.sum()
        first[xx] = xmin
        if (total != 0.0) {
            DoubleArray(k.size) { x -> k[x] / total }
        } else {
            k
        }
    }
    return FloatCoefficients(first, weights)
}

private fun clip8(sum: Long): Float = ((sum + (1L shl (PRECISION_BITS - 1))) shr PRECISION_BITS).coerceIn(0, 255).toFloat()

/** Pillow's ImagingResample for 8-bit images: horizontal pass, then vertical, over the box. */
internal fun resample(
    gray: Gray,
    outW: Int,
    outH: Int,
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    filter: Filter = LANCZOS,
): Gray {
    val horizontal = coefficients(gray.width, x0, x1, outW, filter)
    val vertical = coefficients(gray.height, y0, y1, outH, filter)
    val needHorizontal = outW != gray.width || x0 != 0.0 || x1 != gray.width.toDouble()
    val needVertical = outH != gray.height || y0 != 0.0 || y1 != gray.height.toDouble()
    var image = gray
    var rowOffset = 0
    if (needHorizontal) {
        val firstRow = vertical.first[0]
        val lastRow = vertical.first[outH - 1] + vertical.weights[outH - 1].size
        val out = FloatArray(outW * (lastRow - firstRow))
        for (y in firstRow until lastRow) {
            for (x in 0 until outW) {
                val w = horizontal.weights[x]
                var sum = 0L
                for (i in w.indices) sum += image[horizontal.first[x] + i, y].toLong() * w[i]
                out[(y - firstRow) * outW + x] = clip8(sum)
            }
        }
        image = Gray(outW, lastRow - firstRow, out)
        rowOffset = firstRow
    }
    if (needVertical) {
        val out = FloatArray(image.width * outH)
        for (y in 0 until outH) {
            for (x in 0 until image.width) {
                val w = vertical.weights[y]
                var sum = 0L
                for (i in w.indices) sum += image[x, vertical.first[y] - rowOffset + i].toLong() * w[i]
                out[y * image.width + x] = clip8(sum)
            }
        }
        image = Gray(image.width, outH, out)
    }
    return image
}
