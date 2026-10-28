package io.github.bert0ns.cribrum.imaging

import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

private fun gray(width: Int, height: Int, vararg values: Int) = Gray(width, height, FloatArray(values.size) { values[it].toFloat() })

private val DECODE_FIXTURES = File("../../fixtures/decode").canonicalFile

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
    fun `a non-jpeg image above the pixel limit decodes at a sample size, one below it at full size`() {
        val file = TestImage.texture(64, 48).save(File(folder.root, "a.png"))

        val full = decode(file, 8)
        val sampled = decode(file, 8, maxPixels = 1000)

        assertEquals(64 to 48, full.width to full.height)
        assertEquals(32 to 24, sampled.width to sampled.height)
    }

    @Test
    fun `a Display P3 image decodes to the values it stores, as Pillow reads it`() {
        val stored = ByteArray(8 * 4 * 4) { i -> if (i % 4 == 3) -1 else (i * 37 % 256).toByte() }
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(stored))
        val file = File(folder.root, "p3.png").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val expected = IntArray(32) { i -> (0xff shl 24) or ((stored[i * 4].toInt() and 0xff) shl 16) or ((stored[i * 4 + 1].toInt() and 0xff) shl 8) or (stored[i * 4 + 2].toInt() and 0xff) }

        assertArrayEquals(expected, decode(file, 2).pixels)
    }

    /** Skia decodes a 16-bit PNG to RGBA_F16 whatever config is asked for: eight bytes a pixel, not four. */
    @Test
    fun `a 16-bit png decodes to its values at 8 bits`() {
        val rows = ByteArrayOutputStream()
        for (y in 0 until 4) {
            rows.write(0) // filter: none
            for (x in 0 until 8) {
                listOf(x * 30, y * 60, 255 - x * 30).forEach { v ->
                    rows.write(v)
                    rows.write(v)
                } // 16 bits: the high byte twice
            }
        }
        val file = File(folder.root, "deep.png").apply { writeBytes(png16(8, 4, rows.toByteArray())) }

        val decoded = decode(file, 2)

        val expected = IntArray(32) { i -> (0xff shl 24) or ((i % 8 * 30) shl 16) or ((i / 8 * 60) shl 8) or (255 - i % 8 * 30) }
        assertArrayEquals(expected, decoded.pixels)
    }

    private fun png16(width: Int, height: Int, rows: ByteArray): ByteArray {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val body = type.toByteArray() + data
            val crc = CRC32().apply { update(body) }.value.toInt()
            return ByteBuffer.allocate(8 + body.size).putInt(data.size).put(body).putInt(crc).array()
        }
        val header = ByteBuffer.allocate(13).putInt(width).putInt(height).put(16).put(2).put(0).put(0).put(0).array() // 16 bits, RGB
        val deflated = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { out -> out.write(rows) } }.toByteArray()
        return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", header) + chunk("IDAT", deflated) + chunk("IEND", ByteArray(0))
    }

    /** The synthetic animated images of fixtures/decode: the first frame has red, green, blue and yellow quadrants. */
    @Test
    fun `an animated gif or webp decodes to its first frame`() {
        val first = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffff00.toInt())
        for (name in listOf("animated.gif", "animated.webp")) {
            val decoded = decode(File(DECODE_FIXTURES, name), 8)

            assertEquals(name, 64 to 48, decoded.width to decoded.height)
            val quadrants = listOf(16 to 12, 48 to 12, 16 to 36, 48 to 36).map { (x, y) -> decoded.pixels[y * 64 + x] }
            assertArrayEquals(name, first, quadrants.toIntArray())
        }
    }

    @Test
    fun `the sample size of a big image keeps both sides at least the draft side`() {
        val file = TestImage.texture(64, 48).save(File(folder.root, "a.webp"), Bitmap.CompressFormat.WEBP_LOSSLESS)

        val sampled = decode(file, 40, maxPixels = 1000)

        assertEquals(64 to 48, sampled.width to sampled.height)
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
