// The lab's tests/test_faces.py, translated.
package io.github.bert0ns.cribrum.detectors

import android.graphics.Bitmap
import android.graphics.Color
import io.github.bert0ns.cribrum.imaging.TestImage
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Protection
import io.github.bert0ns.cribrum.runtime.ModelRuntime
import io.github.bert0ns.cribrum.runtime.Tensor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.exp

/** Outputs of YuNet with chosen anchors lit: (stride, row, column, confidence, log size). */
private class FakeYuNet(private vararg val anchors: Anchor) : ModelRuntime {
    class Anchor(val stride: Int, val row: Int, val col: Int, val confidence: Float, val logSize: Float)

    override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
        assertEquals(listOf(1L, 3L, 640L, 640L), inputs.getValue("input").shape.toList())
        val outputs = mutableMapOf<String, Tensor>()
        for (stride in YuNet.STRIDES) {
            val n = (YuNet.INPUT_SIDE / stride) * (YuNet.INPUT_SIDE / stride)
            val cls = FloatArray(n)
            val obj = FloatArray(n)
            val bbox = FloatArray(n * 4)
            for (anchor in anchors.filter { it.stride == stride }) {
                val i = anchor.row * (YuNet.INPUT_SIDE / stride) + anchor.col
                cls[i] = anchor.confidence
                obj[i] = anchor.confidence
                floatArrayOf(0.5f, 0.5f, anchor.logSize, anchor.logSize).copyInto(bbox, i * 4)
            }
            outputs["cls_$stride"] = Tensor(longArrayOf(1, n.toLong(), 1), cls)
            outputs["obj_$stride"] = Tensor(longArrayOf(1, n.toLong(), 1), obj)
            outputs["bbox_$stride"] = Tensor(longArrayOf(1, n.toLong(), 4), bbox)
        }
        return outputs
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FaceDetectorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun analyze(runtime: ModelRuntime, image: TestImage = TestImage(640, 640)) = runBlocking {
        val file = image.save(File(folder.root, "a.png"))
        FaceDetector(runtime).analyze(ImageRef(file.path, 0, 0), AnalysisContext()).single()
    }

    @Test
    fun `letterbox scales the longest side and pads in BGR`() {
        val file = File(folder.root, "red.png")
        val bitmap = Bitmap.createBitmap(1280, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val boxed = YuNet.letterbox(file)

        assertEquals(640 to 320, boxed.width to boxed.height)
        assertEquals(3 * 640 * 640, boxed.pixels.size)
        fun at(channel: Int, x: Int, y: Int) = boxed.pixels[(channel * 640 + y) * 640 + x]
        assertEquals(listOf(0f, 0f, 255f), (0..2).map { at(it, 100, 100) }) // red, as blue-green-red
        assertEquals(listOf(0f, 0f, 0f), (0..2).map { at(it, 100, 400) }) // padding below the image
    }

    @Test
    fun `detect decodes anchor offsets and sizes`() {
        // Anchor at row 2, column 3 of the stride-32 grid, box of exp(1) * 32 px.
        val faces = YuNet.detect(FakeYuNet(FakeYuNet.Anchor(32, 2, 3, 0.9f, 1f)), FloatArray(3 * 640 * 640))

        val size = exp(1.0) * 32
        val face = faces.single()
        assertEquals(0.9f, face.confidence, 1e-5f)
        assertEquals(3.5 * 32 - size / 2, face.x, 1e-3)
        assertEquals(2.5 * 32 - size / 2, face.y, 1e-3)
        assertEquals(size, face.width, 1e-3)
        assertEquals(size, face.height, 1e-3)
    }

    @Test
    fun `detect drops weak and overlapping faces`() {
        val runtime = FakeYuNet(
            FakeYuNet.Anchor(8, 10, 10, 0.9f, 1f),
            FakeYuNet.Anchor(8, 10, 11, 0.8f, 1f),
            FakeYuNet.Anchor(8, 50, 50, 0.3f, 1f),
        )

        val faces = YuNet.detect(runtime, FloatArray(3 * 640 * 640))

        assertEquals(listOf(0.9f), faces.map { it.confidence })
    }

    @Test
    fun `detector emits a faces protection`() {
        val signal = analyze(FakeYuNet(FakeYuNet.Anchor(32, 5, 5, 0.95f, 2f), FakeYuNet.Anchor(16, 30, 30, 0.7f, 1f)))

        assertEquals(Protection.FACES, signal.kind)
        assertEquals(0.95f, signal.score, 1e-5f)
        assertEquals("2", signal.evidence["faces"])
        assertEquals((exp(2f) * 32) * (exp(2f) * 32) / (640f * 640f), signal.evidence.getValue("largest_face").toFloat(), 1e-3f)
    }

    @Test
    fun `no face scores zero`() {
        val signal = analyze(FakeYuNet(), TestImage(300, 200))

        assertEquals(0f, signal.score, 0f)
        assertEquals(mapOf("faces" to "0"), signal.evidence)
    }

    @Test
    fun `it has no category`() {
        assertNull(FaceDetector(FakeYuNet()).category)
    }
}
