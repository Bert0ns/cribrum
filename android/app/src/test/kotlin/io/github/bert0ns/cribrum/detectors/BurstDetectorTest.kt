// The lab's tests/test_burst.py, translated.
package io.github.bert0ns.cribrum.detectors

import android.graphics.Bitmap
import io.github.bert0ns.cribrum.imaging.TestImage
import io.github.bert0ns.cribrum.imaging.unit
import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
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
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BurstDetectorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val start = LocalDateTime.of(2024, 5, 1, 10, 0, 0)

    private class Shots(val root: File, val start: LocalDateTime) {
        val meta = mutableMapOf<ImageRef, ImageMetadata>()
        val vectors = mutableMapOf<ImageRef, FloatArray>()

        fun add(
            name: String,
            seconds: Long?,
            blur: Int = 0,
            camera: String = "Pixel 8",
            vector: FloatArray? = null,
        ): ImageRef {
            val file = File(root, "$name.jpg").apply { parentFile?.mkdirs() }
            TestImage.texture(640, 480, seed = 0).blurred(blur).save(file, Bitmap.CompressFormat.JPEG)
            val ref = file.toImageRef()
            val captured = seconds?.let { start.plusSeconds(it) }
            meta[ref] = ImageMetadata(640, 480, "image/jpeg", "Google", camera, captured)
            if (vector != null) {
                vectors[ref] = vector
            }
            return ref
        }

        fun run(): Map<ImageRef, List<Signal>> = runBlocking {
            val context = AnalysisContext(
                metadata = { meta.getValue(it) },
                embedding = { vectors[it] ?: throw MissingEmbedding(it) },
            )
            BurstDetector().analyze(meta.keys.toList(), context)
        }
    }

    private fun role(signals: Map<ImageRef, List<Signal>>, image: ImageRef): String = signals.getValue(image).single().evidence.getValue(ROLE_KEY)

    @Test
    fun `shots seconds apart form a burst with the sharpest as best`() {
        val shots = Shots(folder.root, start)
        val blurry = shots.add("1", 0, blur = 3)
        val sharp = shots.add("2", 1)
        val shaky = shots.add("3", 3, blur = 1)

        val signals = shots.run()

        assertEquals(Role.BEST.value, role(signals, sharp))
        assertEquals(0.0f, signals.getValue(sharp).single().score)
        assertEquals(Role.INFERIOR.value, role(signals, blurry))
        assertEquals(Role.INFERIOR.value, role(signals, shaky))
        assertTrue(signals.getValue(blurry).single().score > signals.getValue(shaky).single().score)
        assertTrue(signals.getValue(shaky).single().score >= 0.5f)
        assertEquals(1, signals.values.map { it.single().evidence.getValue(GROUP_KEY) }.toSet().size)
        assertTrue(signals.values.all { it.single().kind == Category.BURST })
    }

    @Test
    fun `the sharpness lookup picks the best shot, to one decimal as blur stores it`() = runBlocking {
        val photos = List(3) { ImageRef("/fake/$it.jpg", 1000, 0) }
        val sharpness = mapOf(photos[0] to 50.04, photos[1] to 50.01, photos[2] to 25.0)
        val context = AnalysisContext(
            metadata = { ImageMetadata(640, 480, "image/jpeg", "Google", "Pixel 8", start.plusSeconds(photos.indexOf(it).toLong())) },
            sharpness = { sharpness.getValue(it) },
        )

        val signals = BurstDetector().analyze(photos, context)

        // Both round to 50.0: a tie, which the earlier shot wins.
        assertEquals(Role.BEST.value, role(signals, photos[0]))
        assertEquals(0.5f, signals.getValue(photos[1]).single().score)
        assertEquals("50.0", signals.getValue(photos[1]).single().evidence["sharpness"])
        assertEquals(0.75f, signals.getValue(photos[2]).single().score)
    }

    @Test
    fun `a long pause splits bursts`() {
        val shots = Shots(folder.root, start)
        val first = listOf(shots.add("1", 0), shots.add("2", 2))
        val second = listOf(shots.add("3", 60), shots.add("4", 61))

        val signals = shots.run()

        val groups = (first + second).map { signals.getValue(it).single().evidence.getValue(GROUP_KEY) }
        assertEquals(groups[0], groups[1])
        assertEquals(groups[2], groups[3])
        assertTrue(groups[0] != groups[2])
    }

    @Test
    fun `lone shots and shots without a capture time are left out`() {
        val shots = Shots(folder.root, start)
        shots.add("lone", 0)
        shots.add("later", 30)
        shots.add("no-time-1", null)
        shots.add("no-time-2", null)

        assertTrue(shots.run().isEmpty())
    }

    @Test
    fun `different cameras never share a burst`() {
        val shots = Shots(folder.root, start)
        shots.add("mine", 0, camera = "Pixel 8")
        shots.add("friend", 1, camera = "iPhone 15")

        assertTrue(shots.run().isEmpty())
    }

    @Test
    fun `different subjects at the same moment are not a burst`() {
        val shots = Shots(folder.root, start)
        val a = shots.add("1", 0, vector = unit(1f, 0f))
        val b = shots.add("2", 1, vector = unit(0f, 1f))
        val c = shots.add("3", 2, vector = unit(0f, 1f))

        val signals = shots.run()

        assertEquals(setOf(b, c), signals.keys)
        assertFalse(a in signals)
    }

    @Test
    fun `undecodable image has sharpness zero and is never best shot`() {
        val shots = Shots(folder.root, start)
        val sharp = shots.add("sharp", 0)
        val brokenFile = File(folder.root, "broken.jpg").apply {
            val jpegStart = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())
            writeBytes(jpegStart + "corrupt".toByteArray())
        }
        val brokenRef = brokenFile.toImageRef()
        shots.meta[brokenRef] = ImageMetadata(640, 480, "image/jpeg", "Google", "Pixel 8", start.plusSeconds(1))

        val signals = shots.run()

        assertEquals(setOf(sharp, brokenRef), signals.keys)
        assertEquals(Role.BEST.value, role(signals, sharp))
        assertEquals(Role.INFERIOR.value, role(signals, brokenRef))
        assertEquals(1.0f, signals.getValue(brokenRef).single().score)
        assertEquals("0.0", signals.getValue(brokenRef).single().evidence["sharpness"])
    }

    @Test
    fun `one different shot inside a burst does not split it`() {
        val shots = Shots(folder.root, start)
        val a = shots.add("1", 0, vector = unit(1f, 0f))
        val odd = shots.add("2", 1, vector = unit(0f, 1f))
        val c = shots.add("3", 2, vector = unit(1f, 0f))
        val d = shots.add("4", 3, vector = unit(1f, 0f))

        val signals = shots.run()

        assertEquals(setOf(a, c, d), signals.keys)
        assertFalse(odd in signals)
        assertEquals("3", signals.getValue(a).single().evidence["shots"])
    }

    @Test
    fun `the skipped shot is still measured from the last shot of the burst`() {
        val shots = Shots(folder.root, start)
        shots.add("1", 0, vector = unit(1f, 0f))
        shots.add("2", 1, vector = unit(0f, 1f))
        shots.add("3", 4, vector = unit(1f, 0f)) // alike, but 4 s after the last shot of the burst

        assertTrue(shots.run().isEmpty())
    }

    @Test
    fun `shots 3 s apart join and 4 s apart split`() {
        val shots = Shots(folder.root, start)
        val joined = listOf(shots.add("1", 0), shots.add("2", 3))
        val split = listOf(shots.add("3", 100), shots.add("4", 104))

        val signals = shots.run()

        assertEquals(joined.toSet(), signals.keys)
        assertTrue(split.none { it in signals })
    }

    @Test
    fun `a chain longer than the gap is one burst`() {
        val shots = Shots(folder.root, start)
        val chain = (0 until 5).map { shots.add("$it", 2L * it) } // 8 s from first to last

        val signals = shots.run()

        assertEquals(chain.toSet(), signals.keys)
        assertEquals(1, signals.values.map { it.single().evidence.getValue(GROUP_KEY) }.toSet().size)
    }
}
