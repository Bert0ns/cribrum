// Text coverage: the share of an image covered by text (docs/design.md §5). Mirrors
// lab/src/cribrum_lab/text.py.
//
// PP-OCRv4's text detector (Apache-2.0, 4.7 MB ONNX) gives a probability of text for every pixel;
// the coverage is the share of pixels above TEXT_PROBABILITY. Only detection runs, not
// recognition: whether there is a lot of text matters, not what it says.
package io.github.bert0ns.cribrum.text

import androidx.tracing.trace
import io.github.bert0ns.cribrum.imaging.Argb
import io.github.bert0ns.cribrum.imaging.BILINEAR
import io.github.bert0ns.cribrum.imaging.Gray
import io.github.bert0ns.cribrum.imaging.channel
import io.github.bert0ns.cribrum.imaging.decode
import io.github.bert0ns.cribrum.imaging.orientation
import io.github.bert0ns.cribrum.imaging.resample
import io.github.bert0ns.cribrum.runtime.ModelRuntime
import io.github.bert0ns.cribrum.runtime.OnnxRuntime
import io.github.bert0ns.cribrum.runtime.Tensor
import java.io.File
import kotlin.math.max

/** Longest side fed to the detector, rounded to a multiple of 32 like each side. */
const val INPUT_SIDE = 640
const val TEXT_PROBABILITY = 0.3f
private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

/** Where the build puts the detector among the APK's resources (downloadPpocr in build.gradle.kts). */
const val DETECTOR_RESOURCE = "/ppocr/ch_PP-OCRv4_det_infer.onnx"

/**
 * RGB normalized with ImageNet mean and deviation, shape (1, 3, H, W), H and W multiples of 32
 * with the longest near [INPUT_SIDE]. Each channel goes through Pillow's steps on its own, as in
 * [io.github.bert0ns.cribrum.mobileclip.preprocess].
 */
fun preprocess(file: File): Tensor = trace("Text.preprocess") {
    val decoded = decode(file, INPUT_SIDE)
    val orientation = orientation(file)
    val (width, height) = if (orientation in 5..8) decoded.height to decoded.width else decoded.width to decoded.height
    val scale = INPUT_SIDE.toDouble() / max(width, height)

    // Math.rint rounds halves to even, as Python's round does.
    fun side(pixels: Int) = max(32, Math.rint(pixels * scale / 32).toInt() * 32)
    val (outW, outH) = side(width) to side(height)
    val out = FloatArray(3 * outW * outH)
    for ((c, shift) in listOf(16, 8, 0).withIndex()) {
        val resized = resizedChannel(decoded, shift, orientation, outW, outH)
        for (i in 0 until outW * outH) out[c * outW * outH + i] = (resized.pixels[i] / 255f - MEAN[c]) / STD[c]
    }
    Tensor(longArrayOf(1, 3, outH.toLong(), outW.toLong()), out)
}

/** The channel turned and resized. Its own function so the full-size plane is out of scope before the next channel's. */
private fun resizedChannel(decoded: Argb, shift: Int, orientation: Int, outW: Int, outH: Int): Gray {
    val plane = channel(decoded, shift, orientation)
    return resample(plane, outW, outH, 0.0, 0.0, plane.width.toDouble(), plane.height.toDouble(), BILINEAR)
}

fun textCoverage(file: File, runtime: ModelRuntime = defaultRuntime): Float {
    val probability = runtime.run(mapOf("x" to preprocess(file))).values.single().values
    return probability.count { it > TEXT_PROBABILITY } / probability.size.toFloat()
}

private val defaultRuntime: ModelRuntime by lazy {
    val stream = object {}.javaClass.getResourceAsStream(DETECTOR_RESOURCE) ?: error("Resource $DETECTOR_RESOURCE not found on classpath")
    // One thread: every pipeline worker runs it at once.
    OnnxRuntime(stream.use { it.readBytes() }, threads = 1)
}
