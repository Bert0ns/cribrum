// Tests for Text translated from lab/tests/test_text.py.
package io.github.bert0ns.cribrum.text

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import io.github.bert0ns.cribrum.golden.Golden
import io.github.bert0ns.cribrum.imaging.TestImage
import io.github.bert0ns.cribrum.imaging.decode
import io.github.bert0ns.cribrum.imaging.orientation
import io.github.bert0ns.cribrum.imaging.resampleChannels
import io.github.bert0ns.cribrum.imaging.turnedSize
import io.github.bert0ns.cribrum.runtime.ModelRuntime
import io.github.bert0ns.cribrum.runtime.Tensor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteOrder
import kotlin.math.max

/** preprocess as it was before #353, into an array of the whole input. Do not optimize it. */
private fun referencePreprocess(file: File): FloatArray {
    val decoded = decode(file, INPUT_SIDE)
    val orientation = orientation(file)
    val (width, height) = turnedSize(decoded, orientation)
    val scale = INPUT_SIDE.toDouble() / max(width, height)
    fun side(pixels: Int) = max(32, Math.rint(pixels * scale / 32).toInt() * 32)
    val (outW, outH) = side(width) to side(height)
    val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    val std = floatArrayOf(0.229f, 0.224f, 0.225f)
    val out = FloatArray(3 * outW * outH)
    for ((c, resized) in resampleChannels(decoded, orientation, outW, outH).withIndex()) {
        for (i in 0 until outW * outH) out[c * outW * outH + i] = (resized.pixels[i] / 255f - mean[c]) / std[c]
    }
    return out
}

/** Marks the top quarter of the image as text. */
private class FakeDetector : ModelRuntime {
    override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
        val (height, width) = inputs.getValue("x").shape.takeLast(2)
        val probability = FloatArray((height * width).toInt()) { if (it < height / 4 * width) 0.9f else 0f }
        return mapOf("sigmoid_0.tmp_0" to Tensor(longArrayOf(1, 1, height, width), probability))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `preprocess keeps the aspect in multiples of 32`() {
        val file = TestImage.filled(3000, 1000, 255).save(folder.newFile("a.png"))

        val pixels = preprocess(file)

        assertArrayEquals(longArrayOf(1, 3, 224, INPUT_SIDE.toLong()), pixels.shape)
        assertEquals((1f - 0.485f) / 0.229f, pixels.values[0], 1e-4f)
    }

    @Test
    fun `an orientation outside 1 to 8 leaves the image as it is`() {
        val file = TestImage.filled(800, 200, 255).save(folder.newFile("a.jpg"), Bitmap.CompressFormat.JPEG)
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "9") }.saveAttributes()

        assertArrayEquals(longArrayOf(1, 3, 160, INPUT_SIDE.toLong()), preprocess(file).shape)
    }

    @Test
    fun `coverage is the share of text pixels`() {
        val file = TestImage.filled(640, 640, 0).save(folder.newFile("a.png"))

        assertEquals(0.25f, textCoverage(file, FakeDetector()), 1e-6f)
    }

    @Test
    fun `preprocess writes the same values as before into a direct buffer in native order`() {
        val turned = TestImage.texture(300, 200).save(folder.newFile("turned.jpg"), Bitmap.CompressFormat.JPEG)
        ExifInterface(turned).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6") }.saveAttributes()
        for (file in Golden.images.map { File(it.image.path) } + turned) {
            val values = preprocess(file).values
            assertTrue(file.name, values.isDirect && values.order() == ByteOrder.nativeOrder())
            assertEquals(file.name, 0, values.position())
            assertArrayEquals(file.name, referencePreprocess(file), FloatArray(values.limit()) { values[it] }, 0f)
        }
    }
}
