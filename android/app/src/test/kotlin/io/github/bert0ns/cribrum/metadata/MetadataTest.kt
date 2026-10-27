// The lab's tests/test_metadata.py, translated where Android can write the test images.
package io.github.bert0ns.cribrum.metadata

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MetadataTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun save(
        name: String,
        width: Int = 40,
        height: Int = 30,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        exif: Map<String, String> = emptyMap(),
    ): File {
        val file = File(folder.root, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        file.outputStream().use { bitmap.compress(format, 90, it) }
        if (exif.isNotEmpty()) {
            ExifInterface(file).apply { exif.forEach(::setAttribute) }.saveAttributes()
        }
        return file
    }

    private fun cameraExif(captured: String = "2024:05:01 10:20:30") = mapOf(
        ExifInterface.TAG_MAKE to "Google",
        ExifInterface.TAG_MODEL to "Pixel 8",
        ExifInterface.TAG_ORIENTATION to "6",
        ExifInterface.TAG_DATETIME_ORIGINAL to captured,
    )

    @Test
    fun `reads size and mime`() {
        val meta = readMetadata(save("a.png", 1080, 2400, Bitmap.CompressFormat.PNG))

        assertEquals(Triple(1080, 2400, "image/png"), Triple(meta.width, meta.height, meta.mime))
    }

    @Test
    fun `reads camera exif`() {
        val meta = readMetadata(save("a.jpg", exif = cameraExif()))

        assertEquals("Google", meta.cameraMake)
        assertEquals("Pixel 8", meta.cameraModel)
        assertEquals(LocalDateTime.of(2024, 5, 1, 10, 20, 30), meta.capturedAt)
        assertTrue(meta.hasCameraExif)
    }

    @Test
    fun `image without exif`() {
        val meta = readMetadata(save("a.jpg"))

        assertNull(meta.cameraMake)
        assertNull(meta.capturedAt)
        assertFalse(meta.hasCameraExif)
    }

    @Test
    fun `size is as displayed`() {
        val meta = readMetadata(save("a.jpg", 40, 30, exif = cameraExif()))

        assertEquals(30 to 40, meta.width to meta.height) // orientation 6: rotated by 90 degrees
    }

    @Test
    fun `modification datetime is not a capture time`() {
        // editors and screenshot tools set this one
        val file = save("a.jpg", exif = mapOf(ExifInterface.TAG_DATETIME to "2024:05:01 10:20:30"))

        assertNull(readMetadata(file).capturedAt)
    }

    @Test
    fun `invalid capture time is ignored`() {
        // ExifInterface refuses to write the lab's other cases ("garbage", suffixes), so only this one is here.
        assertNull(readMetadata(save("a.jpg", exif = cameraExif("0000:00:00 00:00:00"))).capturedAt)
    }

    @Test
    fun `unreadable file throws`() {
        val file = File(folder.root, "a.jpg").apply { writeBytes(byteArrayOf(-1, -40, -1, -32) + " truncated".toByteArray()) }

        assertThrows(UnreadableImage::class.java) { readMetadata(file) }
    }

    private fun failing(times: Int, calls: () -> Int, count: () -> Unit): (File) -> BitmapFactory.Options = { file ->
        count()
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            if (calls() > times) BitmapFactory.decodeFile(file.path, this)
        }
    }

    @Test
    fun `a header that fails to decode once is read again`() {
        var calls = 0
        val decodeBounds = failing(1, { calls }) { calls++ }

        val meta = readMetadata(save("a.png", 40, 30, Bitmap.CompressFormat.PNG), decodeBounds)

        assertEquals(Triple(40, 30, 2), Triple(meta.width, meta.height, calls))
    }

    @Test
    fun `a header that fails to decode twice is unreadable, after two reads and no more`() {
        var calls = 0
        val decodeBounds = failing(2, { calls }) { calls++ }

        assertThrows(UnreadableImage::class.java) { readMetadata(save("a.png", 40, 30, Bitmap.CompressFormat.PNG), decodeBounds) }

        assertEquals(2, calls)
    }

    @Test
    fun `webp without exif`() {
        val meta = readMetadata(save("sticker.webp", 512, 512, Bitmap.CompressFormat.WEBP_LOSSLESS))

        assertEquals(Triple(512, 512, "image/webp"), Triple(meta.width, meta.height, meta.mime))
        assertFalse(meta.hasCameraExif)
    }
}
