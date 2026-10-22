package io.github.bert0ns.cribrum.pipeline

import io.github.bert0ns.cribrum.imaging.TestImage
import io.github.bert0ns.cribrum.index.memoryIndex
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.sources.FilesystemImageSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val index = memoryIndex()
    private val root by lazy { File(folder.root, "device") }

    @After
    fun close() = index.close()

    private fun save(path: String, seed: Int): File = TestImage.texture(64, 48, seed).save(File(root, path).apply { parentFile!!.mkdirs() })

    private fun embedder() = object : Embedder {
        override val id = "test_model"
        override val version = 1

        override fun embedPixels(pixels: List<FloatArray>) = pixels.map { FloatArray(512) { 1f / 22.627417f } }
    }

    private fun scan() = runBlocking { Pipeline(index, embedder = embedder()).run(FilesystemImageSource(root)) }

    private fun coverage() = runBlocking { index.coverage(model = "test_model", modelVersion = 1) }.associateBy { it.id }

    private fun images() {
        save("DCIM/Camera/a.png", 0)
        save("DCIM/Camera/b.png", 1)
        save("WhatsApp/Media/WhatsApp Stickers/s.png", 2)
    }

    @Test
    fun `a sticker needs neither the embedding nor the detectors that read it`() {
        images()
        scan()

        val rows = coverage()

        assertEquals(Coverage("embedding", 1, analysed = 2, needed = 2), rows["embedding"])
        assertEquals(Coverage("document", 3, analysed = 2, needed = 2), rows["document"])
        assertEquals(Coverage("blur", 1, analysed = 3, needed = 3), rows["blur"])
        assertEquals(Coverage("near_duplicate", 2, analysed = 3, needed = 3), rows["near_duplicate"])
    }

    @Test
    fun `an old embedding of a sticker is not an analysed image`() {
        images()
        scan()
        val sticker = File(root, "WhatsApp/Media/WhatsApp Stickers/s.png").path
        runBlocking { index.setEmbeddings("test_model", 1, mapOf(sticker to FloatArray(512))) }
        runBlocking { index.setSignals(sticker, "document", 3, emptyList()) }

        val rows = coverage()

        assertEquals(2, rows.getValue("embedding").analysed)
        assertEquals(2, rows.getValue("document").analysed)
    }

    @Test
    fun `a trashed image is not counted as missing`() {
        images()
        val trashed = File(root, "DCIM/Camera/b.png").path
        runBlocking { Pipeline(index).run(FilesystemImageSource(root)) } // no embeddings yet
        runBlocking { index.setReviewState(trashed, ReviewState.TRASHED) }
        File(trashed).delete()
        scan()

        val rows = coverage()

        assertEquals(Coverage("embedding", 1, analysed = 1, needed = 1), rows["embedding"])
        assertEquals(Coverage("blur", 1, analysed = 2, needed = 2), rows["blur"])
    }

    @Test
    fun `a version other than the current one is not analysed`() {
        images()
        scan()
        val path = File(root, "DCIM/Camera/a.png").path
        runBlocking { index.setSignals(path, "blur", 0, emptyList()) }

        assertEquals(2, coverage().getValue("blur").analysed)
    }

    @Test
    fun `after a full run every row has analysed every image it needs, and the counts follow the pipeline's rules`() {
        save("DCIM/Camera/a.png", 0)
        save("WhatsApp/Media/WhatsApp Stickers/s.png", 1)
        save("Download/trashed.png", 2)
        save("WhatsApp/Media/WhatsApp Stickers/trashed-sticker.png", 3)
        File(root, "Download/broken.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(-1, -40, -1, -32) + ByteArray(16))
        }
        scan()
        for (name in listOf("Download/trashed.png", "WhatsApp/Media/WhatsApp Stickers/trashed-sticker.png")) {
            val file = File(root, name)
            runBlocking { index.setReviewState(file.path, ReviewState.TRASHED) }
            file.delete()
        }
        scan()

        val rows = coverage()

        val needed = mapOf(
            "embedding" to 1, "document" to 1, "forwarded" to 1, // the camera image only: not the sticker, the broken file or the trashed ones
            "blur" to 2, "exposure" to 2, "screenshot" to 2, "faces" to 2, "sticker" to 2, // readable images: the camera image and the sticker
            "exact_duplicate" to 3, "near_duplicate" to 3, "burst" to 3, // every image found, the broken file included
        )
        assertEquals(needed, rows.mapValues { it.value.needed })
        assertEquals(rows.mapValues { it.value.needed }, rows.mapValues { it.value.analysed })
    }
}
