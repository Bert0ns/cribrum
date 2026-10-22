package io.github.bert0ns.cribrum.imaging

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

private fun gray(width: Int, height: Int, vararg values: Int) = Gray(width, height, FloatArray(values.size) { values[it].toFloat() })

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagingTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `jpeg decodes at the largest power of two that keeps both sides at least max side`() {
        assertEquals(1, draftScale(640, 480, 512))
        assertEquals(2, draftScale(1080, 2400, 512))
        assertEquals(4, draftScale(4000, 3000, 512))
        assertEquals(8, draftScale(8000, 6000, 256))
    }

    @Test
    fun `thumbnail sizes are rounded like pillow's`() {
        assertEquals(512 to 384, fitted(640, 480, 512))
        assertEquals(230 to 512, fitted(1080, 2400, 512))
        assertEquals(512 to 1, fitted(10000, 20, 512))
        assertEquals(1 to 512, fitted(3, 10000, 512))
    }

    @Test
    fun `reduce rounds the mean of each block, partial ones too`() {
        val reduced = reduce(gray(3, 2, 0, 1, 10, 2, 3, 11), 2, 2)

        assertArrayEquals(floatArrayOf(2f, 11f), reduced.pixels, 0f) // (0+1+2+3)/4 = 1.5 rounds up
    }

    @Test
    fun `transpose turns the pixels as the orientation says`() {
        val image = gray(2, 3, 1, 2, 3, 4, 5, 6) // 1 2 / 3 4 / 5 6
        fun turned(orientation: Int) = transpose(image, orientation).let { listOf(it.width, it.height) + it.pixels.map(Float::toInt) }

        assertEquals(listOf(2, 3, 1, 2, 3, 4, 5, 6), turned(1))
        assertEquals(listOf(2, 3, 2, 1, 4, 3, 6, 5), turned(2))
        assertEquals(listOf(2, 3, 6, 5, 4, 3, 2, 1), turned(3))
        assertEquals(listOf(2, 3, 5, 6, 3, 4, 1, 2), turned(4))
        assertEquals(listOf(3, 2, 1, 3, 5, 2, 4, 6), turned(5))
        assertEquals(listOf(3, 2, 5, 3, 1, 6, 4, 2), turned(6))
        assertEquals(listOf(3, 2, 6, 4, 2, 5, 3, 1), turned(7))
        assertEquals(listOf(3, 2, 2, 4, 6, 1, 3, 5), turned(8))
    }

    @Test
    fun `a channel is read already turned, with no plane of the other orientation in between`() {
        val argb = Argb(2, 3, IntArray(6) { (0xff shl 24) or ((10 + it) shl 16) or ((20 + it) shl 8) or (30 + it) })

        for ((shift, first) in listOf(16 to 10, 8 to 20, 0 to 30)) {
            val plane = gray(2, 3, *IntArray(6) { first + it })
            for (orientation in 1..8) {
                val expected = transpose(plane, orientation)
                val got = channel(argb, shift, orientation)
                assertEquals("shift $shift, orientation $orientation", listOf(expected.width, expected.height), listOf(got.width, got.height))
                assertArrayEquals("shift $shift, orientation $orientation", expected.pixels, got.pixels, 0f)
            }
        }
    }

    @Test
    fun `resampling a flat image keeps it flat`() {
        val flat = Gray(100, 50, FloatArray(5000) { 77f })

        assertEquals(setOf(77f), resample(flat, 33, 17, 0.0, 0.0, 100.0, 50.0).pixels.toSet())
    }

    @Test
    fun `loaded image fits max side and is as displayed`() {
        val file = TestImage.texture(800, 600).save(File(folder.root, "a.jpg"), Bitmap.CompressFormat.JPEG)
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6") }.saveAttributes()

        val loaded = loadGrayscale(file, 256)

        assertEquals(192 to 256, loaded.width to loaded.height)
    }
}
