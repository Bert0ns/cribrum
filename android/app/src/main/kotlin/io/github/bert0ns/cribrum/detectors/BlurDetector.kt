// BLURRY: variance of the Laplacian on a downscaled grayscale image (docs/design.md §5). Mirrors
// lab/src/cribrum_lab/detectors/blur.py.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.imaging.Gray
import io.github.bert0ns.cribrum.imaging.loadGrayscale
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Signal
import java.io.File
import java.util.Locale

class BlurDetector : Detector {
    override val id = "blur"
    override val version = 1
    override val category = Category.BLURRY
    override val decodeSide = MAX_SIDE

    override suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal> {
        val (globalVariance, peakVariance) = measure(context.pixels(image).gray(MAX_SIDE)) ?: return emptyList()
        val score = (HALF_SCORE_VARIANCE / (HALF_SCORE_VARIANCE + peakVariance)).toFloat()
        val evidence = mapOf(
            "global_variance" to "%.1f".format(Locale.ROOT, globalVariance),
            PEAK_VARIANCE_KEY to "%.1f".format(Locale.ROOT, peakVariance),
        )
        return listOf(Signal(id, version, Category.BLURRY, score, evidence))
    }

    companion object {
        const val MAX_SIDE = 512

        /** The image is split into TILES x TILES regions; the sharpest one stands for the subject. */
        const val TILES = 4

        /** Laplacian variance that scores 0.5. A starting point, to be tuned for recall in the lab. */
        const val HALF_SCORE_VARIANCE = 100.0

        /** The evidence key of the sharpest region's variance, which BURST reads back as sharpness. */
        const val PEAK_VARIANCE_KEY = "peak_variance"

        /** The peak variance [measure] gives for [image], 0 when it can't be decoded or is under 3 px on a side. */
        fun peakVariance(image: ImageRef): Double = try {
            measure(loadGrayscale(File(image.path), MAX_SIDE))?.second ?: 0.0
        } catch (_: Exception) { // undecodable pixels have no detail
            0.0
        }

        /**
         * Laplacian variance of the whole image and of its sharpest tile, which stands for the
         * subject. Null when the image is under 3 px on a side.
         */
        fun measure(gray: Gray): Pair<Double, Double>? {
            val w = gray.width - 2
            val h = gray.height - 2
            if (w <= 0 || h <= 0) return null
            // 4-neighbor Laplacian over the interior pixels, summed in Float as before, left to right
            val pixels = gray.pixels
            val stride = gray.width
            val laplacian = DoubleArray(w * h)
            for (y in 0 until h) {
                var i = (y + 1) * stride + 1
                for (x in 0 until w) {
                    laplacian[y * w + x] = (pixels[i - stride] + pixels[i + stride] + pixels[i - 1] + pixels[i + 1] - 4 * pixels[i]).toDouble()
                    i++
                }
            }
            // A portrait with a blurred background still has a sharp region: score on that one.
            var peak = 0.0
            for (rows in split(h, TILES)) {
                for (columns in split(w, TILES)) {
                    if (rows.isEmpty() || columns.isEmpty()) continue // images narrower than TILES px
                    peak = maxOf(peak, variance(laplacian, w, rows, columns))
                }
            }
            return variance(laplacian, w, 0 until h, 0 until w) to peak
        }

        /** numpy's array_split: the first n % parts ranges get one item more. */
        private fun split(n: Int, parts: Int): List<IntRange> {
            var start = 0
            return (0 until parts).map { i ->
                val size = n / parts + if (i < n % parts) 1 else 0
                (start until start + size).also { start += size }
            }
        }

        /**
         * The variance of the [rows] x [columns] block of [values], a [w] wide image, row by row. The sums
         * add the same values in the same order as a list of the block did, so the result is the same to the bit.
         */
        private fun variance(values: DoubleArray, w: Int, rows: IntRange, columns: IntRange): Double {
            val count = (rows.last - rows.first + 1) * (columns.last - columns.first + 1)
            var sum = 0.0
            for (y in rows) for (x in columns) sum += values[y * w + x]
            val mean = sum / count
            var squares = 0.0
            for (y in rows) {
                for (x in columns) {
                    val d = values[y * w + x] - mean
                    squares += d * d
                }
            }
            return squares / count
        }
    }
}
