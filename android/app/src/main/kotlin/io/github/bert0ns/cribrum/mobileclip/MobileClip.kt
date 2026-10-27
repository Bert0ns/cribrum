// MobileCLIP2-S0 image embeddings (docs/design.md §5). Mirrors lab/src/cribrum_lab/mobileclip.py
// without the text encoder: the app ships the prompts' embeddings instead.
package io.github.bert0ns.cribrum.mobileclip

import io.github.bert0ns.cribrum.imaging.Argb
import io.github.bert0ns.cribrum.imaging.BILINEAR
import io.github.bert0ns.cribrum.imaging.channel
import io.github.bert0ns.cribrum.imaging.decode
import io.github.bert0ns.cribrum.imaging.orientation
import io.github.bert0ns.cribrum.imaging.resample
import io.github.bert0ns.cribrum.pipeline.Embedder
import io.github.bert0ns.cribrum.runtime.ModelRuntime
import io.github.bert0ns.cribrum.runtime.Tensor
import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val MODEL_ID = "mobileclip2_s0"
const val MODEL_VERSION = 1
const val DIMENSIONS = 512
const val INPUT_SIDE = 256

/** CLIP's learned temperature (`logit_scale.exp()` of the checkpoint): turns cosine similarities into softmax logits. */
const val LOGIT_SCALE = 64.33f
private const val PIXELS = 3 * INPUT_SIDE * INPUT_SIDE

/** Where the build puts the image encoder among the APK's assets (downloadMobileClip in build.gradle.kts). */
const val VISION_MODEL_ASSET = "mobileclip2_s0/vision_model.onnx"

/**
 * Probability of each prompt for an image embedding: softmax over the scaled cosine similarities.
 * Prompts and image embeddings are L2-normalized, so their dot product is the cosine similarity.
 */
fun zeroShot(imageEmbedding: FloatArray, promptEmbeddings: List<FloatArray>): FloatArray {
    val logits = promptEmbeddings.map { prompt ->
        val dot = prompt.indices.fold(0f) { sum, i -> sum + imageEmbedding[i] * prompt[i] }
        LOGIT_SCALE * dot
    }
    val maxLogit = logits.max()
    val weights = logits.map { exp(it - maxLogit) }
    val total = weights.sum()
    return FloatArray(weights.size) { weights[it] / total }
}

/**
 * RGB in [0, 1], CHW: shortest side resized to 256 (Pillow's bilinear), then center-cropped.
 * Each channel goes through Pillow's steps on its own, which gives the same numbers.
 */
fun preprocess(file: File): FloatArray {
    val decoded = decode(file, INPUT_SIDE)
    val orientation = orientation(file)
    val out = FloatArray(PIXELS)
    for ((c, shift) in listOf(16, 8, 0).withIndex()) cropChannel(decoded, shift, orientation, out, c * INPUT_SIDE * INPUT_SIDE)
    return out
}

/** Writes one channel's center crop into [out]. Its own function so the full-size plane is out of scope before the next channel's. */
private fun cropChannel(decoded: Argb, shift: Int, orientation: Int, out: FloatArray, offset: Int) {
    val plane = channel(decoded, shift, orientation)
    val scale = INPUT_SIDE.toDouble() / min(plane.width, plane.height)
    // Math.rint rounds halves to even, as Python's round does.
    val width = max(INPUT_SIDE, Math.rint(plane.width * scale).toInt())
    val height = max(INPUT_SIDE, Math.rint(plane.height * scale).toInt())
    val resized = resample(plane, width, height, 0.0, 0.0, plane.width.toDouble(), plane.height.toDouble(), BILINEAR)
    val left = (width - INPUT_SIDE) / 2
    val top = (height - INPUT_SIDE) / 2
    for (y in 0 until INPUT_SIDE) {
        for (x in 0 until INPUT_SIDE) {
            out[offset + y * INPUT_SIDE + x] = resized[left + x, top + y] / 255f
        }
    }
}

/** Embeddings are L2-normalized, so a dot product is the cosine similarity. */
class MobileClip(loadVision: () -> ModelRuntime) :
    Embedder,
    AutoCloseable {
    override val id = MODEL_ID
    override val version = MODEL_VERSION

    private val vision = lazy(loadVision)

    override fun load() {
        vision.value
    }

    /** Closes the model if it was loaded. */
    override fun close() {
        if (vision.isInitialized()) vision.value.close()
    }

    /** [pixels] are [preprocess] outputs. */
    override fun embedPixels(pixels: List<FloatArray>): List<FloatArray> {
        val batch = FloatArray(pixels.size * PIXELS)
        pixels.forEachIndexed { i, image -> image.copyInto(batch, i * PIXELS) }
        val shape = longArrayOf(pixels.size.toLong(), 3, INPUT_SIDE.toLong(), INPUT_SIDE.toLong())
        val embeds = vision.value.run(mapOf("pixel_values" to Tensor(shape, batch))).getValue("image_embeds").values
        return List(pixels.size) { i -> normalize(embeds.copyOfRange(i * DIMENSIONS, (i + 1) * DIMENSIONS)) }
    }

    fun embedImages(files: List<File>): List<FloatArray> = embedPixels(files.map(::preprocess))
}

private fun normalize(vector: FloatArray): FloatArray {
    val norm = sqrt(vector.fold(0f) { sum, x -> sum + x * x })
    return FloatArray(vector.size) { vector[it] / norm }
}
