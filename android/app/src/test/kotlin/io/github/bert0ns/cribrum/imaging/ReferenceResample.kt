// Pillow's resample and the channel split as they were before #228, kept so a test can check that
// the faster versions give the same numbers bit for bit. Do not optimize this file.
package io.github.bert0ns.cribrum.imaging

import kotlin.math.max
import kotlin.math.min

private const val PRECISION_BITS = 32 - 8 - 2

private class Coefficients(val first: IntArray, val weights: Array<IntArray>)

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

private fun clip8(sum: Long): Float = ((sum + (1L shl (PRECISION_BITS - 1))) shr PRECISION_BITS).coerceIn(0, 255).toFloat()

internal fun referenceResample(
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

internal fun referenceChannel(image: Argb, shift: Int, orientation: Int): Gray {
    fun pixel(i: Int) = (image.pixels[i] shr shift and 0xff).toFloat()
    val w = image.width
    val h = image.height
    if (orientation !in 2..8) return Gray(w, h, FloatArray(w * h) { pixel(it) })
    val swapped = orientation >= 5
    val outW = if (swapped) h else w
    val outH = if (swapped) w else h
    val out = FloatArray(w * h)
    for (y in 0 until outH) {
        for (x in 0 until outW) {
            val (sx, sy) = when (orientation) {
                2 -> w - 1 - x to y
                3 -> w - 1 - x to h - 1 - y
                4 -> x to h - 1 - y
                5 -> y to x
                6 -> y to h - 1 - x
                7 -> w - 1 - y to h - 1 - x
                else -> w - 1 - y to x
            }
            out[y * outW + x] = pixel(sy * w + sx)
        }
    }
    return Gray(outW, outH, out)
}
