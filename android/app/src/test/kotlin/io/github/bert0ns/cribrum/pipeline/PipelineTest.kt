// The lab's tests/test_pipeline.py, translated, minus the embedding cases.
package io.github.bert0ns.cribrum.pipeline

import io.github.bert0ns.cribrum.detectors.AnalysisContext
import io.github.bert0ns.cribrum.detectors.Detector
import io.github.bert0ns.cribrum.imaging.TestImage
import io.github.bert0ns.cribrum.index.memoryIndex
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Kind
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.sources.FilesystemImageSource
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipelineTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val index = memoryIndex()
    private val root by lazy { File(folder.root, "device") }
    private val source by lazy { FilesystemImageSource(root) }

    @After
    fun close() = index.close()

    private fun save(path: String, seed: Int = 0): File =
        TestImage.texture(64, 48, seed).save(File(root, path).apply { parentFile!!.mkdirs() })

    private fun photos() {
        save("DCIM/Camera/a.png")
        File(root, "DCIM/Camera/a.png").copyTo(File(root, "WhatsApp/Media/a-copy.png").apply { parentFile!!.mkdirs() })
        save("Pictures/Screenshots/Screenshot_1.png", seed = 1)
    }

    private fun run(pipeline: Pipeline = Pipeline(index)) = runBlocking { pipeline.run(source) }

    private fun kinds(path: String): Set<Kind> = runBlocking { index.signals(File(root, path).path) }.map { it.kind }.toSet()

    @Test
    fun `first run stores metadata and signals of every detector`() {
        photos()

        val stats = run()

        assertEquals(3, stats.images)
        assertEquals(emptyList<String>(), stats.failures)
        val screenshot = File(root, "Pictures/Screenshots/Screenshot_1.png").path
        assertEquals(64, runBlocking { index.metadata(screenshot) }!!.width)
        assertEquals(setOf(Category.BLURRY, Category.BAD_EXPOSURE, Category.SCREENSHOT, Category.STICKER), kinds("Pictures/Screenshots/Screenshot_1.png"))
        assertTrue(Category.EXACT_DUPLICATE in kinds("WhatsApp/Media/a-copy.png"))
    }

    @Test
    fun `second run on unchanged data does no detector work`() {
        photos()
        run()

        val stats = run()

        assertEquals(0, stats.newOrChanged)
        assertEquals(0, stats.detectorRuns)
    }

    @Test
    fun `changed image is analyzed again and groups are recomputed`() {
        photos()
        run()
        save("DCIM/Camera/a.png", seed = 2).setLastModified(1000)

        val stats = run()

        assertEquals(1, stats.newOrChanged)
        assertEquals(DETECTORS.size + GROUP_DETECTORS.size, stats.detectorRuns)
        assertFalse(Category.EXACT_DUPLICATE in kinds("WhatsApp/Media/a-copy.png"))
    }

    @Test
    fun `removed image leaves the index`() {
        photos()
        run()
        File(root, "DCIM/Camera/a.png").delete()

        val stats = run()

        assertEquals(1, stats.forgotten)
        assertEquals(2, runBlocking { index.images() }.size)
    }

    @Test
    fun `trashed image stays in the index while it is in the trash`() {
        photos()
        run()
        val path = File(root, "DCIM/Camera/a.png").path
        runBlocking { index.setReviewState(path, ReviewState.TRASHED) }
        File(path).delete()

        val stats = run()

        assertEquals(0, stats.forgotten)
        assertEquals(ReviewState.TRASHED, runBlocking { index.reviewState(path) })
        assertTrue(Category.BLURRY in kinds("DCIM/Camera/a.png"))
    }

    @Test
    fun `unreadable image gets no per-image analysis`() {
        photos()
        val broken = File(root, "Download/broken.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(-1, -40, -1, -32) + ByteArray(16)) }

        val stats = run()

        assertEquals(1, stats.unreadable)
        assertEquals(emptyList<String>(), stats.failures)
        assertEquals(emptyList<Signal>(), runBlocking { index.signals(broken.path) })
    }

    private class Failing(val fixed: Boolean) : Detector {
        override val id = "failing"
        override val version = 1

        override suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal> {
            if (!fixed) throw IOException("truncated")
            return listOf(Signal(id, version, Category.BLURRY, 0.5f))
        }
    }

    @Test
    fun `failed analysis is reported and retried on the next run`() {
        photos()

        var stats = run(Pipeline(index, listOf(Failing(fixed = false)), emptyList()))

        assertEquals(3, stats.failures.size)
        assertTrue(stats.failures[0], "IOException: truncated" in stats.failures[0])

        stats = run(Pipeline(index, listOf(Failing(fixed = true)), emptyList()))

        assertEquals(emptyList<String>(), stats.failures)
        assertEquals(3, stats.detectorRuns)
    }

    @Test
    fun `one image at a time gives the same signals as many`() {
        photos()
        run(Pipeline(index, parallelism = 1))
        val serial = runBlocking { index.indexedImages() }.associate { it.ref.path to it.signals.toSet() }
        val other = memoryIndex()

        runBlocking { Pipeline(other, parallelism = 8).run(source) }

        assertEquals(serial, runBlocking { other.indexedImages() }.associate { it.ref.path to it.signals.toSet() })
        other.close()
    }

    @Test
    fun `detector categories are derived from the detector lists`() {
        assertEquals(
            setOf(Category.BLURRY, Category.BAD_EXPOSURE, Category.SCREENSHOT, Category.STICKER, Category.EXACT_DUPLICATE),
            DETECTOR_CATEGORIES,
        )
    }
}
