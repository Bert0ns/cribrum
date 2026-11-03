package io.github.bert0ns.cribrum.compress

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QualityTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun encoded(quality: Int): ByteArray {
        val random = Random(quality)
        val bitmap = Bitmap.createBitmap(IntArray(64 * 48) { 0xFF shl 24 or random.nextInt(0xFFFFFF) }, 64, 48, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }

    @Test
    fun `the quality estimate gives back the quality Android encoded at`() {
        for (quality in listOf(30, 50, 75, 80, 85, 90, 95, 100)) {
            val info = readJpeg(encoded(quality))

            assertEquals(JpegStatus.ELIGIBLE, info.status)
            assertEquals(quality, info.quality)
        }
    }

    /** Prints the cost of a header read, for docs/design.md §9. The bound only catches a read of the image data. */
    @Test
    fun `reading the headers of a file costs well under a millisecond`() {
        val files = (0 until 200).map { i ->
            File(folder.root, "$i.jpg").apply { writeBytes(withSef(ultraHdr(gainMapBytes = jpeg(entropy = 200_000)), "Image_UTC_Data", "MCC_Data")) }
        }
        files.forEach(::readJpeg) // warm up the JIT
        val start = System.nanoTime()
        files.forEach(::readJpeg)
        val micros = (System.nanoTime() - start) / 1_000 / files.size

        println("jpeg header read: $micros µs per file, ${files.size} files of ${files.first().length() / 1000} kB")
        assertTrue("$micros µs", micros < 5_000)
    }
}
