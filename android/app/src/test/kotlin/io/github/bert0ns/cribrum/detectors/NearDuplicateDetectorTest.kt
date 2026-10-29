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
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
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
import kotlin.math.sqrt

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

    // Fake images with chosen hashes and embeddings, so the linking thresholds are hit exactly.
    private val base = 0x5555555555555555uL

    private fun fake(name: String, size: Long) = ImageRef("/fake/$name.jpg", size, 0)

    private fun runFakes(
        hashes: Map<ImageRef, ULong>,
        vectors: Map<ImageRef, FloatArray> = emptyMap(),
    ) = runBlocking {
        val context = AnalysisContext(
            perceptualHash = { hashes.getValue(it) },
            embedding = { vectors[it] ?: throw MissingEmbedding(it) },
        )
        NearDuplicateDetector().analyze(hashes.keys.toList(), context)
    }

    @Test
    fun `a copy linked only through another copy joins the group but scores under the threshold`() {
        // The largest file is the best copy: no metadata, so every image has the same pixels.
        val a = fake("a", 3000)
        val b = fake("b", 2000)
        val c = fake("c", 1000)
        val hashes = mapOf(a to base, b to (base xor 0x3FFuL), c to (base xor 0x3FFuL xor 0xFFC00uL))

        val signals = runFakes(hashes)

        assertEquals(setOf(a, b, c), signals.keys)
        assertEquals(1, signals.values.map { it[0].evidence[GROUP_KEY] }.toSet().size)
        assertEquals(Role.BEST.value, signals[a]!![0].evidence[ROLE_KEY])
        assertEquals("20", signals[c]!![0].evidence["distance"])
        // Scored against the best copy, as in the lab: 1 - 20 / 22.
        assertEquals(0.091f, signals[c]!![0].score, 1e-3f)
        assertTrue(signals[c]!![0].score < THRESHOLDS.getValue(Category.NEAR_DUPLICATE))
        assertTrue(signals[b]!![0].score >= THRESHOLDS.getValue(Category.NEAR_DUPLICATE))
    }

    @Test
    fun `hashes 10 bits apart link and 11 bits apart do not`() {
        val a = fake("a", 2000)
        val b = fake("b", 1000)

        val linked = runFakes(mapOf(a to base, b to (base xor 0x3FFuL)))
        val apart = runFakes(mapOf(a to base, b to (base xor 0x7FFuL)))

        assertEquals(setOf(a, b), linked.keys)
        assertEquals(0.545f, linked[b]!![0].score, 1e-3f)
        assertTrue(apart.isEmpty())
    }

    @Test
    fun `embeddings with cosine 0 90 link and 0 89 do not`() {
        val a = fake("a", 2000)
        val b = fake("b", 1000)
        val hashes = mapOf(a to base, b to base.inv()) // 64 bits apart: only the embedding can link

        fun cosine(value: Float) = runFakes(
            hashes,
            mapOf(
                a to unit(1f),
                b to unit(value, sqrt(1f - value * value)), // unit length, cosine `value` with a
            ),
        )

        assertEquals(setOf(a, b), cosine(0.90f).keys)
        assertTrue(cosine(0.89f).isEmpty())
    }

    @Test
    fun `flat images do not link by hash`() {
        val black = fake("black", 3000)
        val gray = fake("gray", 2000)
        val white = fake("white", 1000)
        val photo = fake("photo", 500)

        // Black hashes to 0 and any other flat image to its DC bit, so they are 1 bit apart.
        val signals = runFakes(mapOf(black to 0uL, gray to (1uL shl 63), white to (1uL shl 63), photo to base))

        assertTrue(signals.isEmpty())
    }

    @Test
    fun `a flat image that an embedding links is marked flat in the evidence`() {
        val flat = fake("flat", 2000)
        val photo = fake("photo", 1000)
        val vectors = mapOf(flat to unit(1f), photo to unit(1f))

        val signals = runFakes(mapOf(flat to (1uL shl 63), photo to base), vectors)

        assertEquals("true", signals[flat]!![0].evidence["flat"])
        assertFalse("flat" in signals[photo]!![0].evidence)
    }

    @Test
    fun `pairs anywhere in a large library link, and only they do`() {
        val photos = List(300) { fake("$it", 1000L + it) }
        // Flat hashes never link, so only the planted hash pairs can.
        val hashes = photos.associateWith { 0uL }.toMutableMap()
        hashes[photos[0]] = base
        hashes[photos[299]] = base xor 0b111uL
        hashes[photos[127]] = base.inv()
        hashes[photos[128]] = base.inv() xor 1uL
        // One axis per image, so every two embeddings are orthogonal unless planted equal.
        val vectors = photos.withIndex().filter { it.index % 10 != 9 }.associate { (i, photo) -> photo to FloatArray(512).also { v -> v[i] = 1f } }.toMutableMap()
        for ((a, b) in listOf(3 to 250, 63 to 64, 200 to 201, 200 to 202)) vectors[photos[b]] = vectors.getValue(photos[a])

        val groups = runFakes(hashes, vectors).entries
            .groupBy({ it.value.single().evidence.getValue(GROUP_KEY) }, { photos.indexOf(it.key) })
            .values.map { it.toSet() }.toSet()

        assertEquals(setOf(setOf(0, 299), setOf(127, 128), setOf(3, 250), setOf(63, 64), setOf(200, 201, 202)), groups)
    }
}
