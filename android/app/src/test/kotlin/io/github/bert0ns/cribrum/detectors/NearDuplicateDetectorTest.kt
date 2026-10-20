// Tests for NearDuplicateDetector translated from lab/tests/test_near_duplicate.py.
package io.github.bert0ns.cribrum.detectors

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import io.github.bert0ns.cribrum.imaging.TestImage
import io.github.bert0ns.cribrum.imaging.unit
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.toImageRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class NearDuplicateDetectorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun save(
        image: TestImage,
        path: String,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 95,
    ): ImageRef {
        val file = File(folder.root, path).apply { parentFile!!.mkdirs() }
        val pixels = IntArray(image.values.size) { image.values[it].let { v -> (0xff shl 24) or (v shl 16) or (v shl 8) or v } }
        val bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(format, quality, it) }
        return file.toImageRef()
    }

    private fun downsample(image: TestImage, outW: Int, outH: Int): TestImage {
        val out = TestImage(outW, outH)
        for (y in 0 until outH) {
            val srcY = (y.toDouble() * image.height / outH).toInt().coerceIn(0, image.height - 1)
            for (x in 0 until outW) {
                val srcX = (x.toDouble() * image.width / outW).toInt().coerceIn(0, image.width - 1)
                out[x, y] = image[srcX, srcY]
            }
        }
        return out
    }

    private fun crop(image: TestImage, left: Int, top: Int, width: Int, height: Int): TestImage {
        val out = TestImage(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[x, y] = image[left + x, top + y]
            }
        }
        return out
    }

    private fun run(
        images: List<ImageRef>,
        embedding: suspend (ImageRef) -> FloatArray = { throw MissingEmbedding(it) },
    ) = runBlocking {
        NearDuplicateDetector().analyze(images, AnalysisContext(embedding = embedding))
    }

    @Test
    fun `resized and recompressed copies group with the original`() {
        val scene1 = TestImage.texture(800, 600, seed = 1)
        val original = save(scene1, "DCIM/Camera/a.jpg", quality = 95)
        val resized = save(downsample(scene1, 400, 300), "WhatsApp/a.jpg", quality = 95)
        val recompressed = save(scene1, "Download/a.jpg", quality = 40)
        val other = save(TestImage.texture(800, 600, seed = 2), "DCIM/Camera/b.jpg", quality = 95)

        val signals = run(listOf(original, resized, recompressed, other))

        assertFalse(other in signals)
        val groups = listOf(original, resized, recompressed).map { signals[it]!![0].evidence[GROUP_KEY] }.toSet()
        assertEquals(1, groups.size)
        assertTrue(signals.values.all { it[0].kind == Category.NEAR_DUPLICATE })
    }

    @Test
    fun `highest resolution then least compression is the best copy`() {
        val scene1 = TestImage.texture(800, 600, seed = 1)
        val original = save(scene1, "DCIM/Camera/a.jpg", quality = 95)
        val resized = save(downsample(scene1, 400, 300), "WhatsApp/a.jpg", quality = 95)
        val recompressed = save(scene1, "Download/a.jpg", quality = 40)

        val signals = run(listOf(original, resized, recompressed))

        assertEquals(Role.BEST.value, signals[original]!![0].evidence[ROLE_KEY])
        assertEquals(0.0f, signals[original]!![0].score)
        for (copy in listOf(resized, recompressed)) {
            assertEquals(Role.INFERIOR.value, signals[copy]!![0].evidence[ROLE_KEY])
            assertTrue(signals[copy]!![0].score >= 0.5f)
        }
    }

    @Test
    fun `camera exif wins between equal copies`() {
        val scene1 = TestImage.texture(800, 600, seed = 1)
        val plain = save(scene1, "Pictures/a.jpg")
        val withExif = save(scene1, "Pictures/b.jpg")
        val exif = ExifInterface(withExif.path)
        exif.setAttribute(ExifInterface.TAG_MODEL, "Pixel 8")
        exif.saveAttributes()

        val signals = run(listOf(plain, withExif))

        assertEquals(Role.BEST.value, signals[withExif]!![0].evidence[ROLE_KEY])
    }

    @Test
    fun `different images are left out`() {
        val images = (0 until 5).map { seed ->
            save(TestImage.texture(800, 600, seed = seed), "$seed.jpg")
        }

        val signals = run(images)

        assertTrue(signals.isEmpty())
    }

    @Test
    fun `undecodable image is skipped`() {
        val scene1 = TestImage.texture(800, 600, seed = 1)
        val original = save(scene1, "DCIM/Camera/a.jpg", quality = 95)
        val resized = save(downsample(scene1, 400, 300), "WhatsApp/a.jpg", quality = 95)
        val recompressed = save(scene1, "Download/a.jpg", quality = 40)

        val brokenFile = File(folder.root, "broken.jpg").apply {
            writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()) + "not really a jpeg".toByteArray())
        }
        val broken = brokenFile.toImageRef()

        val signals = run(listOf(original, resized, recompressed, broken))

        assertFalse(broken in signals)
        assertEquals(3, signals.size)
    }

    @Test
    fun `close embeddings link images the hash misses`() {
        val scene1 = TestImage.texture(800, 600, seed = 1)
        val original = save(scene1, "DCIM/Camera/a.jpg")
        val crop = save(crop(scene1, 100, 75, 600, 450), "Pictures/a-crop.jpg")
        val other = save(TestImage.texture(800, 600, seed = 2), "DCIM/Camera/b.jpg")
        val vectors = mapOf(
            original to unit(1f, 0f),
            crop to unit(1f, 0.4f),
            other to unit(0f, 1f),
        )

        assertTrue(run(listOf(original, crop, other)).isEmpty())

        val signals = run(listOf(original, crop, other), embedding = { vectors.getValue(it) })

        assertEquals(setOf(original, crop), signals.keys)
        assertEquals(Role.INFERIOR.value, signals[crop]!![0].evidence[ROLE_KEY])
        assertEquals(0.928f, signals[crop]!![0].evidence["cosine"]!!.toFloat(), 1e-3f)
        assertTrue(signals[crop]!![0].score > 0.5f)
    }

    @Test
    fun `images without an embedding still link by hash`() {
        val scene1 = TestImage.texture(800, 600, seed = 1)
        val original = save(scene1, "DCIM/Camera/a.jpg", quality = 95)
        val resized = save(downsample(scene1, 400, 300), "WhatsApp/a.jpg", quality = 95)
        val recompressed = save(scene1, "Download/a.jpg", quality = 40)
        val other = save(TestImage.texture(800, 600, seed = 2), "DCIM/Camera/b.jpg", quality = 95)
        val vectors = mapOf(original to unit(1f), other to unit(0f, 1f))

        val signals = run(
            listOf(original, resized, recompressed, other),
            embedding = { vectors[it] ?: throw MissingEmbedding(it) },
        )

        assertEquals(setOf(original, resized, recompressed), signals.keys)
    }
}
