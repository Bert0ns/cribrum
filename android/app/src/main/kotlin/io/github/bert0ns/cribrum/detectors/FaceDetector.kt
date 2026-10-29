// FACES protection: images with faces are more likely memories (docs/design.md §6.2). Mirrors
// lab/src/cribrum_lab/detectors/faces.py.
//
// YuNet (OpenCV Zoo, MIT license) is a 230 KB ONNX face detector. The build copies the lab's file
// into the app, so both run the same model on ONNX Runtime, like MobileCLIP. The decoding below
// follows OpenCV's FaceDetectorYN and gives the same boxes.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.imaging.Argb
import io.github.bert0ns.cribrum.imaging.decode
import io.github.bert0ns.cribrum.imaging.orientation
import io.github.bert0ns.cribrum.imaging.resampleChannels
import io.github.bert0ns.cribrum.imaging.turnedSize
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Protection
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.runtime.ModelRuntime
import io.github.bert0ns.cribrum.runtime.OnnxRuntime
import io.github.bert0ns.cribrum.runtime.Tensor
import java.io.File
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Where the build puts the model among the app's resources (copyYuNet in build.gradle.kts). From opencv/opencv_zoo at f12e12798e8314f7c074a6656816c048dcc95b7a. */
private const val MODEL = "/face_detection_yunet_2023mar.onnx"

class FaceDetector(private val injected: ModelRuntime? = null) : Detector {
    override val id = "faces"
    override val version = 1

    /** It emits a protection signal, which is no category to review. */
    override val category: Category? = null

    private val runtime by lazy { injected ?: defaultRuntime }

    override suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal> {
        val boxed = YuNet.letterbox(File(image.path))
        val faces = YuNet.detect(runtime, boxed.pixels)
        val evidence = mutableMapOf("faces" to faces.size.toString())
        var score = 0f
        if (faces.isNotEmpty()) {
            score = faces.maxOf { it.confidence }
            val area = faces.maxOf { it.width * it.height }
            evidence["largest_face"] = "%.4f".format(Locale.ROOT, area / (boxed.width.toDouble() * boxed.height)) // fraction of the image
        }
        return listOf(Signal(id, version, Protection.FACES, score, evidence))
    }
}

/** YuNet's input and output handling. Boxes use doubles, as the lab's do. */
internal object YuNet {
    const val INPUT_SIDE = 640
    val STRIDES = intArrayOf(8, 16, 32)

    /** Lower than OpenCV's 0.9: a missed face costs more than a protected landscape. */
    private const val MIN_CONFIDENCE = 0.6f
    private const val NMS_OVERLAP = 0.3

    /** BGR float pixels in [0, 255], CHW with a batch of 1, and the image's size in them. */
    class Letterbox(val pixels: FloatArray, val width: Int, val height: Int)

    /**
     * The image is scaled so its longest side is 640 px (Pillow's bilinear), then padded with black
     * at the bottom and right.
     */
    fun letterbox(file: File): Letterbox = letterbox(decode(file, INPUT_SIDE), orientation(file))

    /** The letterbox of pixels already decoded, before their EXIF [orientation]. */
    fun letterbox(decoded: Argb, orientation: Int): Letterbox {
        val (turnedW, turnedH) = turnedSize(decoded, orientation)
        val scale = INPUT_SIDE.toDouble() / max(turnedW, turnedH)
        // Math.rint rounds halves to even, as Python's round does.
        val width = max(1, Math.rint(turnedW * scale).toInt())
        val height = max(1, Math.rint(turnedH * scale).toInt())
        val (red, green, blue) = resampleChannels(decoded, orientation, width, height)
        val canvas = FloatArray(3 * INPUT_SIDE * INPUT_SIDE)
        for ((c, plane) in listOf(blue, green, red).withIndex()) {
            for (y in 0 until height) plane.pixels.copyInto(canvas, (c * INPUT_SIDE + y) * INPUT_SIDE, y * width, (y + 1) * width)
        }
        return Letterbox(canvas, width, height)
    }

    /** A face in input pixels. */
    class Face(val confidence: Float, val x: Double, val y: Double, val width: Double, val height: Double)

    /** Faces above [MIN_CONFIDENCE] after non-maximum suppression, most confident first. */
    fun detect(runtime: ModelRuntime, pixels: FloatArray): List<Face> {
        val shape = longArrayOf(1, 3, INPUT_SIDE.toLong(), INPUT_SIDE.toLong())
        val outputs = runtime.run(mapOf("input" to Tensor(shape, pixels)))
        val candidates = mutableListOf<Face>()
        for (stride in STRIDES) {
            val cells = INPUT_SIDE / stride
            val cls = outputs.getValue("cls_$stride").values
            val obj = outputs.getValue("obj_$stride").values
            val bbox = outputs.getValue("bbox_$stride").values
            for (i in 0 until cells * cells) {
                val score = sqrt(cls[i].coerceIn(0f, 1f) * obj[i].coerceIn(0f, 1f))
                if (score < MIN_CONFIDENCE) continue
                val cx = (i % cells + bbox[i * 4].toDouble()) * stride
                val cy = (i / cells + bbox[i * 4 + 1].toDouble()) * stride
                val w = (exp(bbox[i * 4 + 2]) * stride).toDouble()
                val h = (exp(bbox[i * 4 + 3]) * stride).toDouble()
                candidates += Face(score, cx - w / 2, cy - h / 2, w, h)
            }
        }
        val kept = mutableListOf<Face>()
        for (face in candidates.sortedByDescending { it.confidence }) {
            if (kept.all { overlap(face, it) <= NMS_OVERLAP }) kept += face
        }
        return kept
    }

    /** Intersection over union of two boxes. */
    private fun overlap(a: Face, b: Face): Double {
        val w = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
        val h = min(a.y + a.height, b.y + b.height) - max(a.y, b.y)
        val intersection = max(0.0, w) * max(0.0, h)
        return intersection / (a.width * a.height + b.width * b.height - intersection)
    }
}

/** Lives for the process and is shared by every pipeline worker. One thread, as the lab's: they all run the detector at once. */
private val defaultRuntime: ModelRuntime by lazy {
    val stream = object {}.javaClass.getResourceAsStream(MODEL) ?: error("Resource $MODEL not found on classpath")
    OnnxRuntime(stream.use { it.readBytes() }, threads = 1)
}
