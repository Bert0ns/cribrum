package io.github.bert0ns.cribrum.hashing

import android.graphics.Bitmap
import io.github.bert0ns.cribrum.imaging.TestImage
import io.github.bert0ns.cribrum.model.toImageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HashingTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun distance(a: ULong, b: ULong): Int = (a xor b).countOneBits()

    private fun phash(image: TestImage, name: String, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG): ULong {
        val file = File(folder.root, name)
        image.save(file, format)
        return perceptualHash(file.toImageRef())
    }

    @Test
    fun `content hash depends on every byte`() {
        val a = File(folder.root, "a").apply { writeBytes(ByteArray(3_000_000) { 'x'.code.toByte() }) }
        val b = File(folder.root, "b").apply { writeBytes(ByteArray(3_000_000) { 'x'.code.toByte() }) }
        val c = File(folder.root, "c").apply {
            val bytes = ByteArray(3_000_000) { 'x'.code.toByte() }
            bytes[bytes.lastIndex] = 'y'.code.toByte()
            writeBytes(bytes)
        }

        val ha = contentHash(a.toImageRef())
        val hb = contentHash(b.toImageRef())
        val hc = contentHash(c.toImageRef())

        assertEquals(ha, hb)
        assertNotEquals(ha, hc)
    }

    @Test
    fun `perceptual hash survives recompression and light blur`() {
        val scene = TestImage.texture(800, 600, seed = 1)
        val original = phash(scene, "a.jpg", Bitmap.CompressFormat.JPEG)
        val png = phash(scene, "a.png", Bitmap.CompressFormat.PNG)
        val blur = phash(scene.blurred(2), "blur.jpg", Bitmap.CompressFormat.JPEG)

        assertTrue(distance(original, png) <= 4)
        assertTrue(distance(original, blur) <= 4)
    }

    @Test
    fun `perceptual hash tells different images apart`() {
        val hashes = (0 until 5).map { seed ->
            phash(TestImage.texture(800, 600, seed = seed), "$seed.jpg")
        }

        val minDistance = hashes.indices.flatMap { i ->
            ((i + 1) until hashes.size).map { j -> distance(hashes[i], hashes[j]) }
        }.minOrNull() ?: 0

        assertTrue("min distance was $minDistance", minDistance > 16)
    }
}
