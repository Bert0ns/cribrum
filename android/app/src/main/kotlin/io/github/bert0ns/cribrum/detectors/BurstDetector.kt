// BURST: shots of the same moment, taken seconds apart (docs/design.md §5). Mirrors
// lab/src/cribrum_lab/detectors/burst.py.
package io.github.bert0ns.cribrum.detectors

import com.appmattus.crypto.Algorithm
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

// shortcut: sharpness only; add "eyes open" once a face detector exists.
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
        return dot(va, vb) >= MIN_COSINE
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            sum += a[i] * b[i]
        }
        return sum
    }

    private fun sharpness(image: ImageRef): Double {
        return try {
            val gray = loadGrayscale(File(image.path), BlurDetector.MAX_SIDE)
            BlurDetector.measure(gray)?.second ?: 0.0
        } catch (_: Exception) {
            0.0
        }
    }

    private fun groupId(path: String): String {
        val digest = Algorithm.XXH3_64().createDigest()
        val bytes = path.toByteArray()
        digest.update(bytes, 0, bytes.size)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun signals(burst: List<ImageRef>): Map<ImageRef, List<Signal>> {
        val sharpness = burst.associateWith { sharpness(it) }
        val best = burst.maxByOrNull { sharpness.getValue(it) }!!
        val groupId = groupId(burst[0].path)
        val bestSharpness = sharpness.getValue(best)

        val result = LinkedHashMap<ImageRef, List<Signal>>()
        for (image in burst) {
            val isBest = image == best
            val role = if (isBest) Role.BEST else Role.INFERIOR
            val score = if (isBest) {
                0.0f
            } else {
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

    companion object {
        val MAX_GAP: Duration = Duration.ofSeconds(3)
        /** Consecutive shots below this similarity are different subjects, whatever the time. */
        const val MIN_COSINE = 0.80f
    }
}
