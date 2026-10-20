// The golden fixtures the lab exports (fixtures/README.md): synthetic images and what the lab's
// detectors say about them.
package io.github.bert0ns.cribrum.golden

import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.toImageRef
import org.json.JSONObject
import java.io.File

val GOLDEN_ROOT = File("../../fixtures/golden").canonicalFile

data class ExpectedSignal(val detector: String, val version: Int, val category: String, val score: Float, val evidence: Map<String, String>)

data class ExpectedImage(
    val relative: String,
    val image: ImageRef,
    val width: Int,
    val height: Int,
    val hasCameraExif: Boolean,
    val contentHash: String,
    val perceptualHash: String,
    val signals: List<ExpectedSignal>,
) {
    fun signal(detector: String): ExpectedSignal? = signals.singleOrNull { it.detector == detector }
}

object Golden {
    private val json = JSONObject(File(GOLDEN_ROOT, "expected.json").readText())

    /** How far a score may be from the lab's: decoders and resamplers differ between Pillow and Android. */
    val scoreTolerance: Float = json.getJSONObject("tolerance").getDouble("score").toFloat()

    val images: List<ExpectedImage> = json.getJSONObject("images").let { images ->
        images.keys().asSequence().sorted().map { relative ->
            val entry = images.getJSONObject(relative)
            val signals = entry.getJSONArray("signals")
            ExpectedImage(
                relative = relative,
                image = File(GOLDEN_ROOT, relative).toImageRef(),
                width = entry.getInt("width"),
                height = entry.getInt("height"),
                hasCameraExif = entry.getBoolean("has_camera_exif"),
                contentHash = entry.getString("content_hash"),
                perceptualHash = entry.getString("perceptual_hash"),
                signals = (0 until signals.length()).map { i ->
                    val signal = signals.getJSONObject(i)
                    val evidence = signal.getJSONObject("evidence")
                    ExpectedSignal(
                        signal.getString("detector"),
                        signal.getInt("version"),
                        signal.getString("category"),
                        signal.getDouble("score").toFloat(),
                        evidence.keys().asSequence().associateWith(evidence::getString),
                    )
                },
            )
        }.toList()
    }
}
