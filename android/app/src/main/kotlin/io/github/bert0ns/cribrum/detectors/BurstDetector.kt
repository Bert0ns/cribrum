// BURST: shots of the same moment, taken seconds apart (docs/design.md §5). Mirrors
// lab/src/cribrum_lab/detectors/burst.py.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.imaging.loadGrayscale
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale

/** EXIF capture times have 1 s resolution; manual bursts are often 1-2 s apart. */
val MAX_GAP: Duration = Duration.ofSeconds(3)
/** Consecutive shots below this similarity are different subjects, whatever the time. */
const val BURST_MIN_COSINE = 0.80f

/**
 * Photos from the same camera whose capture times are at most MAX_GAP apart are chained
 * into a burst, as long as consecutive shots also look alike when both have an embedding.
 * The sharpest shot, by the BLURRY measure, is the one to keep.
 *
 * shortcut: sharpness only; add "eyes open" once a face detector exists.
 */
class BurstDetector : GroupDetector {
    override val id = "burst"
    override val version = 1
    override val category = Category.BURST

    override suspend fun analyze(images: List<ImageRef>, context: AnalysisContext): Map<ImageRef, List<Signal>> {
        val byCamera = LinkedHashMap<Pair<String?, String?>, MutableList<Pair<LocalDateTime, ImageRef>>>()
        for (image in images) {
            val meta = try {
                context.metadata(image)
            } catch (_: Exception) {
                null
            }
            val capturedAt = meta?.capturedAt ?: continue
            byCamera.getOrPut(meta.cameraMake to meta.cameraModel) { mutableListOf() }
                .add(capturedAt to image)
        }

        val result = LinkedHashMap<ImageRef, List<Signal>>()
        for (shots in byCamera.values) {
            shots.sortWith(compareBy({ it.first }, { it.second.path }))
            for (burst in bursts(shots, context)) {
                result.putAll(signals(burst))
            }
        }
        return result
    }

    private suspend fun bursts(
        shots: List<Pair<LocalDateTime, ImageRef>>,
        context: AnalysisContext,
    ): List<List<ImageRef>> {
        val bursts = mutableListOf<List<ImageRef>>()
        var current = mutableListOf(shots[0].second)
        for (i in 1 until shots.size) {
            val (previousTime, previous) = shots[i - 1]
            val (time, image) = shots[i]
            if (Duration.between(previousTime, time) <= MAX_GAP && alike(previous, image, context)) {
                current += image
            } else {
                bursts += current
                current = mutableListOf(image)
            }
        }
        bursts += current
        return bursts.filter { it.size > 1 }
    }

    /** Recall first: without both embeddings, time alone decides. */
    private suspend fun alike(a: ImageRef, b: ImageRef, context: AnalysisContext): Boolean {
        val va = try {
            context.embedding(a)
        } catch (_: MissingEmbedding) {
            return true
        }
        val vb = try {
            context.embedding(b)
        } catch (_: MissingEmbedding) {
            return true
        }
        return dot(va, vb) >= BURST_MIN_COSINE
    }

    private fun sharpness(image: ImageRef): Double {
        return try {
            val gray = loadGrayscale(File(image.path), BlurDetector.MAX_SIDE)
            BlurDetector.measure(gray)?.second ?: 0.0
        } catch (_: Exception) {
            0.0
        }
    }

    private fun signals(burst: List<ImageRef>): Map<ImageRef, List<Signal>> {
        val sharpness = burst.associateWith { sharpness(it) }
        val best = burst.maxBy { sharpness.getValue(it) }
        val groupId = groupId(burst[0].path)
        val bestSharpness = sharpness.getValue(best)

        val result = LinkedHashMap<ImageRef, List<Signal>>()
        for (image in burst) {
            val isBest = image == best
            val role = if (isBest) Role.BEST else Role.INFERIOR
            val score = if (isBest) {
                0.0f
            } else {
                // 0.5 when as sharp as the best shot, up to 1 when it has no detail at all.
                val ratio = if (bestSharpness > 0.0) sharpness.getValue(image) / bestSharpness else 1.0
                (1.0 - 0.5 * minOf(1.0, ratio)).toFloat()
            }
            val evidence = mapOf(
                GROUP_KEY to groupId,
                ROLE_KEY to role.value,
                "sharpness" to "%.1f".format(Locale.ROOT, sharpness.getValue(image)),
                "shots" to burst.size.toString(),
            )
            result[image] = listOf(Signal(id, version, Category.BURST, score, evidence))
        }
        return result
    }
}
