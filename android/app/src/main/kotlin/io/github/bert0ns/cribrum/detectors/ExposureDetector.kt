// BAD_EXPOSURE: pocket shots, black frames, blown-out and flat frames (docs/design.md §5). Mirrors
// lab/src/cribrum_lab/detectors/exposure.py.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.imaging.loadGrayscale
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Signal
import java.io.File
import java.util.Locale

/**
 * Each case looks at the opposite end of the histogram too, so a night shot with a lit subject is
 * not "dark" and a page of text is not "bright". All thresholds are starting points, to be tuned
 * for recall in the lab.
 */
class ExposureDetector : Detector {
    override val id = "exposure"
    override val version = 1

    override suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal> {
        val sorted = loadGrayscale(File(image.path), MAX_SIDE).pixels.sortedArray()
        val (p1, p5, p95, p99) = listOf(1.0, 5.0, 95.0, 99.0).map { percentile(sorted, it) }
        val cases = linkedMapOf(
            "dark" to fade(p95 - DARK_LEVEL),
            "bright" to fade(BRIGHT_LEVEL - p5),
            "flat" to fade(p99 - p1),
        )
        // The strongest case, whether or not it is bad enough: the ranker decides that.
        val case = cases.maxBy { it.value }.key
        val evidence = mapOf("case" to case) +
            cases.map { (name, value) -> "${name}_score" to "%.3f".format(Locale.ROOT, value) }
        return listOf(Signal(id, version, Category.BAD_EXPOSURE, cases.getValue(case).toFloat(), evidence))
    }

    companion object {
        const val MAX_SIDE = 256

        /** Dark scores 1 when 95% of the pixels are at or below this luminance. */
        const val DARK_LEVEL = 30.0

        /** Bright scores 1 when 95% of the pixels are at or above this luminance. */
        const val BRIGHT_LEVEL = 235.0

        /** Luminance distance over which each score fades from 1 to 0. */
        const val RAMP = 64.0

        /** numpy's default percentile: linear between the two nearest ranks. */
        private fun percentile(sorted: FloatArray, q: Double): Double {
            val rank = q / 100 * (sorted.size - 1)
            val low = rank.toInt()
            val high = minOf(low + 1, sorted.size - 1)
            return sorted[low] + (sorted[high] - sorted[low]) * (rank - low)
        }

        /** 1 at distance 0 or less, 0 at RAMP or more, linear in between. */
        private fun fade(distance: Double): Double = (1.0 - distance / RAMP).coerceIn(0.0, 1.0)
    }
}
