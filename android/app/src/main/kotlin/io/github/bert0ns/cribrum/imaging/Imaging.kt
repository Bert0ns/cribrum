// Pixel loading shared by the detectors that look at image content. Mirrors
// lab/src/cribrum_lab/imaging.py, step by step, so measures computed on the pixels match the lab's:
// Pillow's reduced JPEG decoding, EXIF orientation, ITU-R 601 luminance and Lanczos thumbnail.
package io.github.bert0ns.cribrum.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.tracing.trace
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
 * >= [draftSide]. Another format has no draft: above [maxPixels] it is sampled until it fits, even if a side
 * falls under [draftSide], since a long screenshot must not run the heap out.
 * Alpha is left as stored, since Pillow drops it when it converts to RGB or L. A wide gamut image
 * keeps its stored values, as Pillow does: converted to sRGB they moved the embeddings off the lab's,
 * to a cosine of 0.9993 on a saturated test image.
 */
internal fun decode(file: File, draftSide: Int, maxPixels: Int = MAX_PIXELS): Argb = trace("Imaging.decode") {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UnreadableImage(file)
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPremultiplied = false
        inSampleSize = if (bounds.outMimeType == "image/jpeg") {
            draftScale(bounds.outWidth, bounds.outHeight, draftSide)
        } else {
            sampleFor(bounds.outWidth, bounds.outHeight, maxPixels)
        }
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
        Argb(bitmap.width, bitmap.height, argb)
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
// decode HEIC (decodeFile leaves the bounds at -1), so no unit test sees it. Check on a device (#220); if it turns twice, skip the EXIF turn for image/heif.
internal fun orientation(file: File): Int = try {
    ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
} catch (_: IOException) {
    ExifInterface.ORIENTATION_NORMAL
}

/** Pillow's exif_transpose: turns the pixels so the image is as displayed. */
internal fun transpose(gray: Gray, orientation: Int): Gray = if (orientation !in 2..8) gray else turn(gray.width, gray.height, orientation) { gray.pixels[it] }

/**
 * Where a turned image takes its pixels from: its pixel (x, y) is the original's at
 * `start + x * stepX + y * stepY`. [width] and [height] are the turned image's.
 */
private class Turn(val width: Int, val height: Int, val start: Int, val stepX: Int, val stepY: Int)

/** An [orientation] outside 2..8 leaves the image as it is. */
private fun turnOf(w: Int, h: Int, orientation: Int): Turn = when (orientation) {
    2 -> Turn(w, h, w - 1, -1, w) // mirrored
    3 -> Turn(w, h, w * h - 1, -1, -w) // rotated 180
    4 -> Turn(w, h, (h - 1) * w, 1, -w) // flipped
    5 -> Turn(h, w, 0, w, 1) // transposed
    6 -> Turn(h, w, (h - 1) * w, -w, 1) // rotated 90 clockwise
    7 -> Turn(h, w, w * h - 1, -w, -1) // transversed
    8 -> Turn(h, w, w - 1, w, -1) // rotated 90 counterclockwise
    else -> Turn(w, h, 0, 1, w)
}

/** The size of [image] once turned as [transpose] does. */
internal fun turnedSize(image: Argb, orientation: Int): Pair<Int, Int> = turnOf(image.width, image.height, orientation).let { it.width to it.height }

/** The turned image of [w] x [h] pixels, [pixel] giving the value at an index of the original. */
private inline fun turn(w: Int, h: Int, orientation: Int, pixel: (Int) -> Float): Gray {
    val t = turnOf(w, h, orientation)
    val out = FloatArray(w * h)
    var dst = 0
    for (y in 0 until t.height) {
        var src = t.start + y * t.stepY
        for (x in 0 until t.width) {
            out[dst++] = pixel(src)
            src += t.stepX
        }
    }
    return Gray(t.width, t.height, out)
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

private fun round8(sum: Long): Int = ((sum + (1L shl (PRECISION_BITS - 1))) shr PRECISION_BITS).coerceIn(0, 255).toInt()

private fun clip8(sum: Long): Float = round8(sum).toFloat()

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
            var src = y * gray.width
            var dst = (y - firstRow) * outW
            for (x in 0 until outW) {
                val w = horizontal.weights[x]
                var i = src + horizontal.first[x]
                var sum = 0L
                for (weight in w) sum += gray.pixels[i++].toLong() * weight
                out[dst++] = clip8(sum)
            }
        }
        image = Gray(outW, lastRow - firstRow, out)
        rowOffset = firstRow
    }
    if (needVertical) {
        val pixels = image.pixels
        image = Gray(image.width, outH, verticalPass(image.width, rowOffset, vertical, outH) { pixels[it].toLong() })
    }
    return image
}

/**
 * The red, green and blue planes of [image], turned as [transpose] does and resampled with the bilinear filter,
 * in that order. The turn is read straight from the pixels and the three channels are summed together, so no
 * full-size plane is built: one per channel took 4 bytes a pixel and a pass over the image each. What the
 * horizontal pass leaves is a byte a value, since it is clipped to 0..255.
 */
internal fun resampleChannels(image: Argb, orientation: Int, outW: Int, outH: Int): Array<Gray> {
    val t = turnOf(image.width, image.height, orientation)
    val horizontal = coefficients(t.width, 0.0, t.width.toDouble(), outW, BILINEAR)
    val vertical = coefficients(t.height, 0.0, t.height.toDouble(), outH, BILINEAR)
    val firstRow = vertical.first[0]
    val rows = vertical.first[outH - 1] + vertical.weights[outH - 1].size - firstRow
    val pixels = image.pixels
    val stepX = t.stepX
    val weights = horizontal.weights
    val first = horizontal.first
    val planes = Array(3) { ByteArray(outW * rows) }
    val (red, green, blue) = planes
    for (row in 0 until rows) {
        val rowStart = t.start + (firstRow + row) * t.stepY
        var dst = row * outW
        for (x in 0 until outW) {
            val w = weights[x]
            var i = rowStart + first[x] * stepX
            var r = 0L
            var g = 0L
            var b = 0L
            for (weight in w) {
                val c = pixels[i]
                r += (c shr 16 and 0xff).toLong() * weight
                g += (c shr 8 and 0xff).toLong() * weight
                b += (c and 0xff).toLong() * weight
                i += stepX
            }
            red[dst] = round8(r).toByte()
            green[dst] = round8(g).toByte()
            blue[dst++] = round8(b).toByte()
        }
    }
    return Array(3) { c ->
        val plane = planes[c]
        if (outH == t.height) {
            Gray(outW, outH, FloatArray(plane.size) { (plane[it].toInt() and 0xff).toFloat() })
        } else {
            Gray(outW, outH, verticalPass(outW, firstRow, vertical, outH) { (plane[it].toInt() and 0xff).toLong() })
        }
    }
}

/** The vertical pass: each output row is the weighted sum of the rows [value] reads, a row at a time. */
private inline fun verticalPass(width: Int, rowOffset: Int, vertical: Coefficients, outH: Int, value: (Int) -> Long): FloatArray {
    val out = FloatArray(width * outH)
    val sums = LongArray(width)
    for (y in 0 until outH) {
        sums.fill(0L)
        val w = vertical.weights[y]
        for (i in w.indices) {
            val weight = w[i].toLong()
            var s = (vertical.first[y] - rowOffset + i) * width
            for (x in 0 until width) sums[x] += value(s++) * weight
        }
        var dst = y * width
        for (x in 0 until width) out[dst++] = clip8(sums[x])
    }
    return out
}
