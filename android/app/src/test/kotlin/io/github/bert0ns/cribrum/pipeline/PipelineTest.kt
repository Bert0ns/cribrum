// The lab's tests/test_pipeline.py, translated.
package io.github.bert0ns.cribrum.pipeline

import io.github.bert0ns.cribrum.detectors.AnalysisContext
import io.github.bert0ns.cribrum.detectors.BlurDetector
import io.github.bert0ns.cribrum.detectors.BurstDetector
import io.github.bert0ns.cribrum.detectors.Detector
import io.github.bert0ns.cribrum.detectors.GroupDetector
import io.github.bert0ns.cribrum.detectors.MissingEmbedding
import io.github.bert0ns.cribrum.folders.isStickerFolder
import io.github.bert0ns.cribrum.hashing.contentHash
import io.github.bert0ns.cribrum.hashing.perceptualHash
import io.github.bert0ns.cribrum.imaging.TestImage
import io.github.bert0ns.cribrum.index.ImageIndex
import io.github.bert0ns.cribrum.index.memoryIndex
import io.github.bert0ns.cribrum.metadata.UnreadableImage
import io.github.bert0ns.cribrum.metadata.readMetadata
import io.github.bert0ns.cribrum.mobileclip.preprocess
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Kind
import io.github.bert0ns.cribrum.model.Protection
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.model.toImageRef
import io.github.bert0ns.cribrum.review.Review
import io.github.bert0ns.cribrum.sources.FilesystemImageSource
import io.github.bert0ns.cribrum.sources.ImageSource
import io.github.bert0ns.cribrum.sources.MergedImageSource
import io.github.bert0ns.cribrum.trash.AppTrash
import io.github.bert0ns.cribrum.trash.Trash
import io.github.bert0ns.cribrum.trash.TrashedImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    private fun save(path: String, seed: Int = 0): File = TestImage.texture(64, 48, seed).save(File(root, path).apply { parentFile!!.mkdirs() })

    private fun photos() {
        save("DCIM/Camera/a.png")
        File(root, "DCIM/Camera/a.png").copyTo(File(root, "WhatsApp/Media/a-copy.png").apply { parentFile!!.mkdirs() })
        save("Pictures/Screenshots/Screenshot_1.png", seed = 1)
    }

    private fun run(pipeline: Pipeline = Pipeline(index)) = runBlocking { pipeline.run(source) }

    private fun kinds(path: String): Set<Kind> = runBlocking { index.signals(File(root, path).path) }.map { it.kind }.toSet()

    @Test
    fun `a walk longer than the record batch records every file and counts the new and changed ones`() {
        val files = (0 until 5).map { save("DCIM/Camera/$it.png", seed = it) }
        fun batched() = Pipeline(index, detectors = emptyList(), groupDetectors = emptyList(), recordBatch = 2)

        assertEquals(5, run(batched()).newOrChanged)
        assertEquals(files.map { it.path }.toSet(), runBlocking { index.images() }.map { it.path }.toSet())

        assertEquals(0, run(batched()).newOrChanged)
        files[3].setLastModified(files[3].lastModified() + 5_000)
        assertEquals(1, run(batched()).newOrChanged)
    }

    @Test
    fun `first run stores metadata and signals of every detector`() {
        photos()

        val stats = run()

        assertEquals(3, stats.images)
        assertEquals(emptyList<Failure>(), stats.failures)
        val screenshot = File(root, "Pictures/Screenshots/Screenshot_1.png").path
        assertEquals(64, runBlocking { index.metadata(screenshot) }!!.width)
        assertNotNull(runBlocking { index.perceptualHash(screenshot) })
        assertEquals(setOf(Category.BLURRY, Category.BAD_EXPOSURE, Category.SCREENSHOT, Protection.FACES, Category.STICKER), kinds("Pictures/Screenshots/Screenshot_1.png"))
        assertTrue(Category.EXACT_DUPLICATE in kinds("WhatsApp/Media/a-copy.png"))
    }

    @Test
    fun `a run times each phase on the clock it is given`() {
        photos()
        var now = 0L
        val clock = { now.also { now += 10 } }

        val stats = run(Pipeline(index, embedder = MeanColorEmbedder(), clock = clock))

        val phases = listOf("walk", "metadata", "detectors", "embeddings", "embeddedDetectors", "groupDetectors")
        assertEquals(phases.associateWith { 10L }, stats.phaseMs)
        assertEquals(phases, stats.phaseMs.keys.toList())
    }

    @Test
    fun `a run without an embedder still times every phase, the embeddings one included`() {
        photos()

        val stats = run(Pipeline(index, clock = { 0 }))

        assertEquals(listOf("walk", "metadata", "detectors", "embeddings", "embeddedDetectors", "groupDetectors"), stats.phaseMs.keys.toList())
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
    fun `two scans of the same library run each group detector once`() {
        photos()
        val ids = listOf("exact_duplicate", "near_duplicate", "burst")

        val first = run(Pipeline(index, embedder = MeanColorEmbedder()))
        val second = run(Pipeline(index, embedder = MeanColorEmbedder()))

        assertEquals(ids to emptyList<String>(), first.ranGroupDetectors to first.skippedGroupDetectors)
        assertEquals(emptyList<String>() to ids, second.ranGroupDetectors to second.skippedGroupDetectors)
    }

    @Test
    fun `changed image is analyzed again and groups are recomputed`() {
        photos()
        run(Pipeline(index, embedder = MeanColorEmbedder()))
        save("DCIM/Camera/a.png", seed = 2).setLastModified(1000)

        val stats = run(Pipeline(index, embedder = MeanColorEmbedder()))

        assertEquals(1, stats.newOrChanged)
        assertEquals(DETECTORS.size + 2 + GROUP_DETECTORS.size, stats.detectorRuns) // + hash, embedding
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
    fun `a row whose file is still on disk is not forgotten, though the walk missed it`() {
        photos()
        run()
        val missed = refOf("DCIM/Camera/a.png").path
        val blind = object : ImageSource {
            override val walkedRoots get() = source.walkedRoots

            override fun scan() = source.scan().filter { it.path != missed } // moved away and back while the walk was in its folder
        }

        val stats = runBlocking { Pipeline(index).run(blind) }

        assertEquals(0, stats.forgotten)
        assertEquals(3, runBlocking { index.images() }.size)
    }

    @Test
    fun `a volume the scan did not walk keeps its rows and their state`() {
        val card = File(folder.root, "card")
        save("DCIM/Camera/a.png")
        TestImage.texture(64, 48, 1).save(File(card, "DCIM/b.png").apply { parentFile!!.mkdirs() })
        val both = MergedImageSource(listOf(FilesystemImageSource(root), FilesystemImageSource(card)))
        runBlocking { Pipeline(index).run(both) }
        val onCard = File(card, "DCIM/b.png").path
        runBlocking { index.setReviewState(onCard, ReviewState.KEPT) }
        card.deleteRecursively() // the card is out

        val stats = runBlocking { Pipeline(index).run(both) }

        assertEquals(0, stats.forgotten)
        assertEquals(ReviewState.KEPT, runBlocking { index.reviewState(onCard) })
        assertEquals(2, runBlocking { index.images() }.size)
    }

    @Test
    fun `a volume the scan walked still forgets its removed images`() {
        val card = File(folder.root, "card")
        save("DCIM/Camera/a.png")
        TestImage.texture(64, 48, 1).save(File(card, "DCIM/b.png").apply { parentFile!!.mkdirs() })
        val both = MergedImageSource(listOf(FilesystemImageSource(root), FilesystemImageSource(card)))
        runBlocking { Pipeline(index).run(both) }
        File(card, "DCIM/b.png").delete()

        val stats = runBlocking { Pipeline(index).run(both) }

        assertEquals(1, stats.forgotten)
    }

    @Test
    fun `a folder whose listing fails keeps its rows`() {
        photos()
        run()
        val path = File(root, "DCIM/Camera/a.png").path
        runBlocking { index.setReviewState(path, ReviewState.KEPT) }
        val locked = File(root, "DCIM/Camera").apply { setReadable(false) }
        assumeTrue("the folder is still listable (running as root?)", locked.listFiles() == null)

        val stats = try {
            run()
        } finally {
            locked.setReadable(true)
        }

        assertEquals(0, stats.forgotten)
        assertEquals(ReviewState.KEPT, runBlocking { index.reviewState(path) })
        assertEquals(3, runBlocking { index.images() }.size)
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

    private val trash by lazy { AppTrash({ listOf(root) }) }

    private fun refOf(path: String) = File(root, path).toImageRef()

    /** The trash holds [path]'s file and the index says so, as after an apply. */
    private fun trashed(path: String): ImageRef {
        val ref = refOf(path)
        runBlocking {
            assertEquals(listOf(ref), trash.moveToTrash(listOf(ref)))
            index.setReviewState(ref.path, ReviewState.TRASHED)
        }
        return ref
    }

    /** A source that runs [action] once the walk is over, like a review action that comes after the scan passed the folder. */
    private fun afterWalk(action: suspend () -> Unit) = object : ImageSource {
        override val walkedRoots get() = source.walkedRoots

        override fun scan() = flow {
            emitAll(source.scan())
            action()
        }
    }

    @Test
    fun `a trashed row whose file is back on disk with no trash item becomes unreviewed`() {
        photos()
        run()
        val image = trashed("DCIM/Camera/a.png")
        val item = runBlocking { trash.trashed() }.single()
        Files.move(item.file.toPath(), File(image.path).toPath()) // put back by hand
        item.file.parentFile!!.deleteRecursively()

        run(Pipeline(index, trash = trash))

        assertEquals(ReviewState.UNREVIEWED, runBlocking { index.reviewState(image.path) })
    }

    @Test
    fun `a trashed row stays while its item is in the trash, and goes when it is neither on disk nor in the trash`() {
        photos()
        run()
        val kept = trashed("DCIM/Camera/a.png")
        val lost = trashed("WhatsApp/Media/a-copy.png")
        runBlocking { trash.trashed() }.single { it.original == lost }.file.parentFile!!.deleteRecursively()

        val stats = run(Pipeline(index, trash = trash))

        assertEquals(1, stats.forgotten)
        assertEquals(ReviewState.TRASHED, runBlocking { index.reviewState(kept.path) })
        assertEquals(setOf(kept.path, refOf("Pictures/Screenshots/Screenshot_1.png").path), runBlocking { index.images() }.map { it.path }.toSet())
    }

    @Test
    fun `a trashed row on a volume the scan did not walk is kept even if the trash lost its item`() {
        val card = File(folder.root, "card")
        TestImage.texture(64, 48, 1).save(File(card, "DCIM/b.png").apply { parentFile!!.mkdirs() })
        val both = MergedImageSource(listOf(FilesystemImageSource(root), FilesystemImageSource(card)))
        save("DCIM/Camera/a.png")
        runBlocking { Pipeline(index).run(both) }
        val onCard = File(card, "DCIM/b.png").toImageRef()
        runBlocking { index.setReviewState(onCard.path, ReviewState.TRASHED) }
        card.deleteRecursively() // the card is out, and so is its trash

        val stats = runBlocking { Pipeline(index, trash = trash).run(both) }

        assertEquals(0, stats.forgotten)
        assertEquals(ReviewState.TRASHED, runBlocking { index.reviewState(onCard.path) })
    }

    @Test
    fun `a trash that cannot be listed is a failure of the scan, not its end, and leaves TRASHED rows alone`() {
        photos()
        run()
        val image = trashed("DCIM/Camera/a.png")
        val unlistable = object : Trash by trash {
            override suspend fun trashed(): List<TrashedImage> = throw IOException("volume gone")
        }

        val stats = run(Pipeline(index, trash = unlistable))

        assertEquals(listOf("trash"), stats.failures.map { it.step })
        assertEquals("", stats.failures.single().path)
        assertEquals(0, stats.forgotten)
        assertEquals(ReviewState.TRASHED, runBlocking { index.reviewState(image.path) })
        assertEquals(2, stats.images) // the scan went on over what is on disk
    }

    @Test
    fun `an image restored after the walk passed its folder keeps the KEPT row the restore wrote`() {
        photos()
        run()
        val image = trashed("DCIM/Camera/a.png")
        val review = Review(index, trash)

        val stats = runBlocking { Pipeline(index, trash = trash).run(afterWalk { review.restore(listOf(image)) }) }

        assertEquals(0, stats.forgotten)
        assertEquals(ReviewState.KEPT, runBlocking { index.reviewState(image.path) })
    }

    /** On [target], does what a review action does while a scan runs, then ends as [then] says. */
    private inner class Interrupting(private val target: String, private val purge: Boolean, private val then: () -> Unit = {}) : Detector {
        override val id = "interrupting"
        override val version = 1

        override suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal> {
            if (image.path == target) {
                val review = Review(index, trash)
                review.apply(listOf(image), emptyList())
                if (purge) review.purge(listOf(image))
                then()
            }
            return listOf(Signal(id, version, Category.BLURRY, 0.5f))
        }
    }

    @Test
    fun `emptying the trash while a scan analyzes an image does not end the scan`() {
        photos()
        val target = refOf("DCIM/Camera/a.png").path

        val stats = run(Pipeline(index, listOf(Interrupting(target, purge = true)), emptyList(), trash = trash))

        assertEquals(emptyList<Failure>(), stats.failures)
        assertEquals(setOf(refOf("WhatsApp/Media/a-copy.png").path, refOf("Pictures/Screenshots/Screenshot_1.png").path), runBlocking { index.images() }.map { it.path }.toSet())
    }

    @Test
    fun `a failure on an image trashed since the walk is not listed`() {
        photos()
        val target = refOf("DCIM/Camera/a.png").path

        val stats = run(Pipeline(index, listOf(Interrupting(target, purge = false) { throw FileNotFoundException(target) }), emptyList(), trash = trash))

        assertEquals(emptyList<Failure>(), stats.failures)
        assertEquals(ReviewState.TRASHED, runBlocking { index.reviewState(target) })
    }

    @Test
    fun `an image trashed after the walk is not marked unreadable, so a restore brings it back analyzed`() {
        photos()
        val image = refOf("DCIM/Camera/a.png")
        runBlocking { index.record(image) } // a scan that stopped before the metadata step: the user can only act on an indexed image
        val review = Review(index, trash)

        val stats = runBlocking { Pipeline(index, trash = trash).run(afterWalk { review.apply(listOf(image), emptyList()) }) }

        assertEquals(emptyList<Failure>(), stats.failures)
        assertEquals(emptyList<String>(), runBlocking { index.unreadable() })
        runBlocking { review.restore(listOf(image)) }
        run(Pipeline(index, trash = trash))
        assertNotNull(runBlocking { index.metadata(image.path) })
        assertTrue(Category.BLURRY in kinds("DCIM/Camera/a.png"))
    }

    @Test
    fun `a metadata read that fails once leaves the image readable on the next scan`() {
        photos()
        val flaky = refOf("DCIM/Camera/a.png").path
        var failing = true
        val reader = { file: File -> if (failing && file.path == flaky) throw IOException("read error") else readMetadata(file) }

        val first = run(Pipeline(index, metadataReader = reader))

        assertEquals(listOf(flaky), first.failures.filter { it.step == "metadata" }.map { it.path })
        assertEquals(0, first.unreadable)
        assertEquals(emptyList<String>(), runBlocking { index.unreadable() })
        failing = false
        val second = run(Pipeline(index, metadataReader = reader))
        assertEquals(emptyList<Failure>(), second.failures)
        assertNotNull(runBlocking { index.metadata(flaky) })
        assertTrue(Category.BLURRY in kinds("DCIM/Camera/a.png"))
    }

    @Test
    fun `a metadata read that runs out of memory fails that image only`() {
        photos()
        val big = refOf("DCIM/Camera/a.png").path
        val reader = { file: File -> if (file.path == big) throw OutOfMemoryError("heap") else readMetadata(file) }

        val stats = run(Pipeline(index, metadataReader = reader))

        assertEquals(listOf(Failure(big, "metadata", "OutOfMemoryError: heap")), stats.failures)
        assertEquals(emptyList<String>(), runBlocking { index.unreadable() })
        assertNotNull(runBlocking { index.metadata(refOf("WhatsApp/Media/a-copy.png").path) })
    }

    @Test
    fun `only an UnreadableImage marks a file unreadable`() {
        photos()
        val broken = refOf("DCIM/Camera/a.png").path
        val reader = { file: File -> if (file.path == broken) throw UnreadableImage(file) else readMetadata(file) }

        val stats = run(Pipeline(index, metadataReader = reader))

        assertEquals(listOf(broken), runBlocking { index.unreadable() })
        assertEquals(1, stats.unreadable)
        assertEquals(emptyList<Failure>(), stats.failures)
    }

    @Test
    fun `a row forgotten while its metadata is read does not end the scan`() {
        photos()
        val target = refOf("DCIM/Camera/a.png").path
        val reader = { file: File ->
            if (file.path == target) runBlocking { index.forget(listOf(target)) }
            readMetadata(file)
        }

        val stats = run(Pipeline(index, metadataReader = reader))

        assertEquals(emptyList<Failure>(), stats.failures)
        assertEquals(2, runBlocking { index.images() }.size)
    }

    @Test
    fun `a row forgotten while its file fails to read does not end the scan`() {
        photos()
        val target = refOf("DCIM/Camera/a.png").path
        val reader = { file: File ->
            if (file.path == target) runBlocking { index.forget(listOf(target)) }
            readMetadata(file).takeIf { file.path != target } ?: throw IOException("broken")
        }

        val stats = run(Pipeline(index, metadataReader = reader))

        assertEquals(emptyList<Failure>(), stats.failures)
        assertEquals(2, runBlocking { index.images() }.size)
    }

    @Test
    fun `unreadable image gets no per-image analysis`() {
        photos()
        val broken = File(root, "Download/broken.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(-1, -40, -1, -32) + ByteArray(16))
        }

        val stats = run()

        assertEquals(1, stats.unreadable)
        assertEquals(emptyList<Failure>(), stats.failures)
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
        assertTrue(stats.failures[0].toString(), "IOException: truncated" in stats.failures[0].toString())

        stats = run(Pipeline(index, listOf(Failing(fixed = true)), emptyList()))

        assertEquals(emptyList<Failure>(), stats.failures)
        assertEquals(3, stats.detectorRuns)
    }

    @Test
    fun `a perceptual hash that failed once and works now gets its image grouped again`() {
        photos()
        val copy = File(root, "WhatsApp/Media/a-copy.png").path

        var stats = run(Pipeline(index, perceptualHasher = { if (it.path == copy) throw IOException("busy") else perceptualHash(it) }))

        assertEquals(1, stats.failures.size)
        assertFalse(Category.NEAR_DUPLICATE in kinds("WhatsApp/Media/a-copy.png"))

        stats = run()

        assertEquals(emptyList<Failure>(), stats.failures)
        assertTrue(Category.NEAR_DUPLICATE in kinds("WhatsApp/Media/a-copy.png"))
    }

    @Test
    fun `a content hash that failed once and works now gets its image grouped again`() {
        photos()
        val copy = File(root, "WhatsApp/Media/a-copy.png").path

        run(Pipeline(index, contentHasher = { if (it.path == copy) throw IOException("busy") else contentHash(it) }))

        assertFalse(Category.EXACT_DUPLICATE in kinds("WhatsApp/Media/a-copy.png"))

        run()

        assertTrue(Category.EXACT_DUPLICATE in kinds("WhatsApp/Media/a-copy.png"))
    }

    @Test
    fun `a content hash that fails on every scan does not run the exact detector on every scan, and it runs once the file reads`() {
        photos()
        val copy = File(root, "WhatsApp/Media/a-copy.png").path
        val failing = Pipeline(index, contentHasher = { if (it.path == copy) throw IOException("busy") else contentHash(it) })
        run(failing)

        val again = run(failing)

        assertEquals(emptyList<String>(), again.ranGroupDetectors)

        val readable = run()

        assertEquals(listOf("exact_duplicate"), readable.ranGroupDetectors)
        assertTrue(Category.EXACT_DUPLICATE in kinds("WhatsApp/Media/a-copy.png"))
        assertEquals(emptyList<String>(), run().ranGroupDetectors)
    }

    /** Stores a BLURRY signal with the given peak variance for the files it names, and none for the others. */
    private class StoredBlur(private val peaks: Map<String, String>, override val version: Int = 1) : Detector {
        override val id = "blur"
        override val category = Category.BLURRY

        override suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal> = peaks[File(image.path).name]?.let { listOf(Signal(id, version, Category.BLURRY, 0.5f, mapOf("peak_variance" to it))) }.orEmpty()
    }

    @Test
    fun `burst reads the sharpness blur stored, and measures a shot blur left without one`() {
        val shots = (1..3).map { save("DCIM/Camera/$it.png", seed = it) }
        val start = LocalDateTime.of(2024, 5, 1, 10, 0, 0)
        val reader = { file: File -> readMetadata(file).copy(cameraMake = "Google", cameraModel = "Pixel 8", capturedAt = start.plusSeconds(file.nameWithoutExtension.toLong())) }
        val blur = StoredBlur(mapOf("1.png" to "12.3", "2.png" to "45.6"))

        run(Pipeline(index, listOf(blur), listOf(BurstDetector()), metadataReader = reader))

        val sharpness = shots.map { shot -> runBlocking { index.signals(shot.path) }.single { it.kind == Category.BURST }.evidence["sharpness"] }
        val measured = runBlocking { BlurDetector().analyze(shots[2].toImageRef(), AnalysisContext()) }.single().evidence["peak_variance"]
        assertEquals(listOf("12.3", "45.6", measured), sharpness)
    }

    @Test
    fun `a new blur version reruns burst on an unchanged library`() {
        photos()
        fun withBlur(version: Int) = Pipeline(index, listOf(StoredBlur(emptyMap(), version)), listOf(BurstDetector()))
        run(withBlur(1))

        assertEquals(emptyList<String>(), run(withBlur(1)).ranGroupDetectors)
        assertEquals(listOf("burst"), run(withBlur(2)).ranGroupDetectors)
    }

    private class Probe : Detector {
        override val id = "probe"
        override val version = 1
        val seen = java.util.concurrent.ConcurrentLinkedQueue<String>()

        override suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal> {
            seen += image.path
            return listOf(Signal(id, version, Category.BLURRY, 0.5f))
        }
    }

    @Test
    fun `a run analyzes the new, the forgotten and the changed, and nothing else, trashed images included`() {
        photos()
        val probe = Probe()
        run(Pipeline(index, listOf(probe), emptyList()))
        val paths = listOf("DCIM/Camera/a.png", "WhatsApp/Media/a-copy.png", "Pictures/Screenshots/Screenshot_1.png").map { File(root, it).path }
        runBlocking {
            index.setReviewState(paths[0], ReviewState.TRASHED)
            index.forgetAnalyses(listOf(paths[0]), listOf("probe")) // trashed, no analysis: analyzed again
        }
        val added = save("DCIM/Camera/new.png", seed = 3).path
        probe.seen.clear()

        val stats = run(Pipeline(index, listOf(probe), emptyList()))

        assertEquals(setOf(paths[0], added), probe.seen.toSet())
        assertEquals(2, probe.seen.size)
        assertEquals(3, stats.detectorRuns) // two analyses and the new image's hash
        assertNotNull(runBlocking { index.perceptualHash(added) })
    }

    @Test
    fun `progress reports how many images the pass has to analyze before it analyzes the first`() {
        photos()
        val calls = mutableListOf<Triple<String, Int, Int>>()

        runBlocking { Pipeline(index, listOf(Probe()), emptyList()).run(source) { step, done, total -> calls += Triple(step, done, total) } }

        assertEquals(Triple("detectors", 0, 3), calls.first { it.first == "detectors" })
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
            setOf(
                Category.BLURRY, Category.BAD_EXPOSURE, Category.SCREENSHOT, Category.STICKER,
                Category.DOCUMENT, Category.FORWARDED,
                Category.EXACT_DUPLICATE, Category.NEAR_DUPLICATE, Category.BURST,
            ),
            DETECTOR_CATEGORIES,
        )
    }

    /** Embeds an image as its mean color, padded to MobileCLIP's 512 values. */
    private class MeanColorEmbedder(override val version: Int = 1, private val scale: Float = 1f) : Embedder {
        override val id = "mean_color"

        override fun embedPixels(pixels: List<FloatArray>): List<FloatArray> = pixels.map { image ->
            val plane = image.size / 3
            val vector = FloatArray(512) { 1f }
            for (c in 0 until 3) vector[c] = scale * (0 until plane).sumOf { image[c * plane + it].toDouble() }.toFloat() / plane
            val norm = kotlin.math.sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
            FloatArray(512) { vector[it] / norm }
        }
    }

    /** Group detector that records the embeddings it can look up. */
    private class EmbeddingReader : GroupDetector {
        override val id = "embedding_reader"
        override val version = 1
        var seen = mapOf<String, FloatArray>()

        override suspend fun analyze(images: List<ImageRef>, context: AnalysisContext): Map<ImageRef, List<Signal>> {
            seen = images.associate { File(it.path).name to context.embedding(it) }
            return emptyMap()
        }
    }

    @Test
    fun `embeddings are computed once and reach group detectors`() {
        photos()
        val reader = EmbeddingReader()

        val first = run(Pipeline(index, groupDetectors = listOf(reader), embedder = MeanColorEmbedder()))
        val second = run(Pipeline(index, groupDetectors = emptyList(), embedder = MeanColorEmbedder()))

        assertEquals(3 * DETECTORS.size + 3 * 2 + 1, first.detectorRuns) // + hashes, embeddings
        assertEquals(0, second.detectorRuns)
        assertEquals(setOf("a.png", "a-copy.png", "Screenshot_1.png"), reader.seen.keys)
        assertEquals(3, runBlocking { index.embeddings("mean_color", 1) }.size)
    }

    @Test
    fun `without an embedder the lookup throws`() {
        photos()

        assertThrows(MissingEmbedding::class.java) { run(Pipeline(index, groupDetectors = listOf(EmbeddingReader()))) }
    }

    private class EmbeddingDetector : Detector {
        override val id = "embedding_user"
        override val version = 1
        override val usesEmbeddings = true

        override suspend fun analyze(image: ImageRef, context: AnalysisContext) = listOf(Signal(id, version, Category.DOCUMENT, context.embedding(image)[0]))
    }

    @Test
    fun `detector needing embeddings waits for a run with them`() {
        photos()

        var stats = run(Pipeline(index, detectors = listOf(EmbeddingDetector()), groupDetectors = emptyList()))

        assertEquals(3, stats.skipped)
        assertEquals(emptyList<Failure>(), stats.failures)

        stats = run(Pipeline(index, detectors = listOf(EmbeddingDetector()), groupDetectors = emptyList(), embedder = MeanColorEmbedder()))

        assertEquals(0, stats.skipped)
        assertEquals(3 + 3, stats.detectorRuns) // embeddings, then the detector
    }

    @Test
    fun `the next run after deleting the embeddings computes them again and reruns the detectors reading them`() {
        photos()
        val pipeline = { Pipeline(index, detectors = listOf(EmbeddingDetector()), groupDetectors = emptyList(), embedder = MeanColorEmbedder()) }
        run(pipeline())
        val before = runBlocking { index.signals(File(root, "DCIM/Camera/a.png").path) }

        runBlocking { index.deleteEmbeddings() }

        // The candidates stay until a scan replaces them.
        assertEquals(before, runBlocking { index.signals(File(root, "DCIM/Camera/a.png").path) })

        val stats = run(pipeline())

        assertEquals(3 + 3, stats.detectorRuns) // embeddings, then the detector
        assertEquals(3, runBlocking { index.embeddings("mean_color", 1) }.size)
        assertEquals(before, runBlocking { index.signals(File(root, "DCIM/Camera/a.png").path) })
    }

    private fun everySignal() = runBlocking { index.images().associate { it.path to index.signals(it.path).sortedBy { s -> s.detectorId } } }

    @Test
    fun `after deleting the embeddings the detectors reading them run again and the group detectors, with the same vectors, do not`() {
        photos()
        run(Pipeline(index, embedder = MeanColorEmbedder()))
        val before = everySignal()

        runBlocking { index.deleteEmbeddings() }
        val stats = run(Pipeline(index, embedder = MeanColorEmbedder()))

        val readers = DETECTORS.count { it.usesEmbeddings }
        assertEquals(3 + 3 * readers, stats.detectorRuns) // embeddings, then document and forwarded; no group run
        assertEquals(before, everySignal())
    }

    @Test
    fun `a new embedder version reruns the detectors reading embeddings and the group detectors`() {
        photos()
        run(Pipeline(index, embedder = MeanColorEmbedder()))

        val stats = run(Pipeline(index, embedder = MeanColorEmbedder(version = 2)))

        val readers = DETECTORS.count { it.usesEmbeddings }
        assertEquals(3 + 3 * readers + GROUP_DETECTORS.size, stats.detectorRuns)
    }

    @Test
    fun `a detector's signal follows the embedding it read`() {
        photos()
        val detectors = listOf(EmbeddingDetector())
        run(Pipeline(index, detectors = detectors, groupDetectors = emptyList(), embedder = MeanColorEmbedder()))
        val path = File(root, "DCIM/Camera/a.png").path
        val before = runBlocking { index.signals(path) }.single().score

        run(Pipeline(index, detectors = detectors, groupDetectors = emptyList(), embedder = MeanColorEmbedder(version = 2, scale = 4f)))

        val after = runBlocking { index.signals(path) }.single().score
        assertEquals(runBlocking { index.embeddings("mean_color", 2) }.getValue(path)[0], after, 0f)
        assertNotEquals(before, after)
    }

    @Test
    fun `only the document and forwarded detectors read embeddings`() {
        assertEquals(listOf("document", "forwarded"), DETECTORS.filter { it.usesEmbeddings }.map { it.id })
    }

    @Test
    fun `the detectors that read no embedding store their signals before the first embedding is computed`() {
        photos()
        val screenshot = "Pictures/Screenshots/Screenshot_1.png"
        var before = emptySet<Kind>()
        val embedder = object : Embedder {
            private val inner = MeanColorEmbedder()
            override val id = inner.id
            override val version = inner.version

            override fun embedPixels(pixels: List<FloatArray>): List<FloatArray> {
                if (before.isEmpty()) before = kinds(screenshot)
                return inner.embedPixels(pixels)
            }
        }

        run(Pipeline(index, embedder = embedder))

        assertEquals(setOf(Category.BLURRY, Category.BAD_EXPOSURE, Category.SCREENSHOT, Protection.FACES, Category.STICKER), before)
    }

    private class UnflaggedEmbeddingDetector : Detector {
        override val id = "unflagged"
        override val version = 1

        override suspend fun analyze(image: ImageRef, context: AnalysisContext) = listOf(Signal(id, version, Category.DOCUMENT, context.embedding(image)[0]))
    }

    @Test
    fun `a detector that reads an embedding without saying so fails on every run`() {
        photos()
        val pipeline = { Pipeline(index, detectors = listOf(UnflaggedEmbeddingDetector()), groupDetectors = emptyList(), embedder = MeanColorEmbedder()) }

        val first = run(pipeline())
        val second = run(pipeline())

        assertEquals(0, first.skipped)
        assertEquals(3, first.failures.size)
        assertTrue(first.failures.all { "without usesEmbeddings" in it.error })
        assertEquals(3, second.failures.size)
    }

    @Test
    fun `a run stopped after the fast detectors resumes with the embeddings and does not repeat them`() {
        photos()
        val stopped = object : Embedder {
            override val id = "mean_color"
            override val version = 1

            override fun embedPixels(pixels: List<FloatArray>): List<FloatArray> = throw kotlinx.coroutines.CancellationException("stopped")
        }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { run(Pipeline(index, embedder = stopped)) }
        assertEquals(setOf(Category.BLURRY, Category.BAD_EXPOSURE, Category.SCREENSHOT, Protection.FACES, Category.STICKER), kinds("Pictures/Screenshots/Screenshot_1.png"))

        val stats = run(Pipeline(index, embedder = MeanColorEmbedder()))

        val readers = DETECTORS.count { it.usesEmbeddings }
        assertEquals(3 + 3 * readers + GROUP_DETECTORS.size, stats.detectorRuns) // embeddings, then the readers; no fast detector, no hash
    }

    @Test
    fun `the steps run in order, the detectors that read embeddings last`() {
        photos()
        val steps = mutableListOf<String>()

        runBlocking { Pipeline(index, embedder = MeanColorEmbedder()).run(source) { step, _, _ -> if (steps.lastOrNull() != step) steps += step } }

        assertEquals(listOf("metadata", "detectors", "embeddings", "embedded_detectors", "exact_duplicate", "near_duplicate", "burst"), steps)
    }

    private val stickerFolder = "WhatsApp/Media/WhatsApp Stickers"

    @Test
    fun `a sticker is not embedded and the detectors reading embeddings leave it alone, but it stays a candidate`() {
        photos()
        save("$stickerFolder/s.png", seed = 3)

        val stats = run(Pipeline(index, embedder = MeanColorEmbedder()))

        assertEquals(emptyList<Failure>(), stats.failures)
        assertEquals(0, stats.skipped)
        assertEquals(3, runBlocking { index.embeddings("mean_color", 1) }.size)
        val signals = runBlocking { index.signals(File(root, "$stickerFolder/s.png").path) }
        assertEquals(setOf(Category.BLURRY, Category.BAD_EXPOSURE, Category.SCREENSHOT, Protection.FACES, Category.STICKER), signals.map { it.kind }.toSet())
        assertEquals(1f, signals.single { it.kind == Category.STICKER }.score, 0f)
    }

    @Test
    fun `a sticker scanned before the rule loses its document and forwarded signals`() {
        save("$stickerFolder/s.png", seed = 3)
        val path = File(root, "$stickerFolder/s.png").path
        run(Pipeline(index, detectors = emptyList(), groupDetectors = emptyList()))
        runBlocking {
            index.setSignals(path, "document", 1, listOf(Signal("document", 1, Category.DOCUMENT, 0.9f)))
            index.setSignals(path, "forwarded", 1, listOf(Signal("forwarded", 1, Category.FORWARDED, 0.9f)))
        }

        run(Pipeline(index, embedder = MeanColorEmbedder()))

        val kinds = runBlocking { index.signals(path) }.map { it.kind }.toSet()
        assertFalse(Category.DOCUMENT in kinds || Category.FORWARDED in kinds)
        assertEquals(setOf(Category.BLURRY, Category.BAD_EXPOSURE, Category.SCREENSHOT, Protection.FACES, Category.STICKER), kinds)
    }

    @Test
    fun `a sticker with no embedding is still in a near duplicate group, through its perceptual hash`() {
        save("$stickerFolder/s.png", seed = 3)
        File(root, "$stickerFolder/s.png").copyTo(File(root, "$stickerFolder/s-copy.png"))

        run(Pipeline(index, embedder = MeanColorEmbedder()))

        assertTrue(Category.NEAR_DUPLICATE in kinds("$stickerFolder/s-copy.png"))
    }

    @Test
    fun `an embedding a sticker got from an earlier scan is not used`() {
        save("$stickerFolder/s.png", seed = 3)
        val path = File(root, "$stickerFolder/s.png").path
        run(Pipeline(index, detectors = emptyList(), groupDetectors = emptyList()))
        runBlocking { index.setEmbeddings("mean_color", 1, mapOf(path to FloatArray(512) { 1f })) }
        var seen = true
        val probe = object : GroupDetector {
            override val id = "probe"
            override val version = 1

            override suspend fun analyze(images: List<ImageRef>, context: AnalysisContext): Map<ImageRef, List<Signal>> {
                seen = runCatching { context.embedding(images.single()) }.isSuccess
                return emptyMap()
            }
        }

        run(Pipeline(index, detectors = emptyList(), groupDetectors = listOf(probe), embedder = MeanColorEmbedder()))

        assertFalse(seen)
    }

    /** In index order the stickers fall between the real images, so an order by path alone fails the tests below. */
    private fun stickersAmongPhotos() {
        save("DCIM/Camera/a.png")
        save("$stickerFolder/s1.png", seed = 3)
        save("$stickerFolder/s2.png", seed = 4)
        save("WhatsApp/Media/x.png", seed = 5)
        save("Zoo/z.png", seed = 6)
    }

    private val realImagesThenStickers = listOf("a.png", "x.png", "z.png", "s1.png", "s2.png")

    @Test
    fun `the metadata step reads every real image before the stickers, and reads every sticker`() {
        stickersAmongPhotos()
        val read = mutableListOf<String>()

        run(Pipeline(index, detectors = emptyList(), groupDetectors = emptyList(), metadataReader = { file -> readMetadata(file).also { read += file.name } }))

        assertEquals(realImagesThenStickers, read)
    }

    @Test
    fun `the detectors step analyzes every real image before the stickers, and analyzes every sticker`() {
        stickersAmongPhotos()
        val probe = Probe()

        run(Pipeline(index, listOf(probe), emptyList(), parallelism = 1)) // one worker: with more, the two ends of the queue overlap

        assertEquals(realImagesThenStickers, probe.seen.map { File(it).name })
    }

    private fun storedResults(index: ImageIndex) = runBlocking {
        listOf(
            index.indexedImages().associate { it.ref.path to it.signals.toSet() },
            index.allMetadata(),
            index.perceptualHashes(),
            index.embeddings("mean_color", 1).mapValues { it.value.toList() },
        )
    }

    @Test
    fun `stickers analyzed before the real images leave the same stored results as stickers analyzed after them`() {
        stickersAmongPhotos()
        File(root, "$stickerFolder/s1.png").copyTo(File(root, "WhatsApp/Media/s1-copy.png")) // a group across the two
        val onlyStickers = object : ImageSource {
            override val walkedRoots get() = source.walkedRoots

            override fun scan() = source.scan().filter { isStickerFolder(it.path) }
        }
        val other = memoryIndex()

        // Two scans: the first takes the stickers through every step, so the second reaches the real images after them.
        val stickersFirst = runBlocking {
            Pipeline(other, embedder = MeanColorEmbedder()).run(onlyStickers).failures + Pipeline(other, embedder = MeanColorEmbedder()).run(source).failures
        }
        val stats = run(Pipeline(index, embedder = MeanColorEmbedder()))

        assertEquals(emptyList<Failure>(), stickersFirst)
        assertEquals(emptyList<Failure>(), stats.failures)
        assertEquals(6, stats.images)
        assertEquals(storedResults(other), storedResults(index))
        other.close()
    }

    /** Runs out of memory whenever the image at [poison] is in the batch, as a very large decode would. */
    private inner class OutOfMemoryEmbedder(poison: String) : Embedder {
        private val inner = MeanColorEmbedder()
        private val poisoned = preprocess(File(root, poison))
        var batches = 0

        override val id = inner.id
        override val version = inner.version

        override fun embedPixels(pixels: List<FloatArray>): List<FloatArray> {
            batches++
            if (pixels.any { it.contentEquals(poisoned) }) throw OutOfMemoryError("Failed to allocate")
            return inner.embedPixels(pixels)
        }
    }

    private val poison = "Pictures/Screenshots/Screenshot_1.png"

    @Test
    fun `an image that runs out of memory does not take its batch or the scan with it`() {
        photos()

        val stats = run(Pipeline(index, detectors = listOf(EmbeddingDetector()), groupDetectors = emptyList(), embedder = OutOfMemoryEmbedder(poison)))

        assertEquals(1, stats.failures.size)
        assertTrue(stats.failures[0].toString(), stats.failures[0].path == File(root, poison).path && "OutOfMemoryError" in stats.failures[0].error)
        assertEquals(setOf("a.png", "a-copy.png"), runBlocking { index.embeddings("mean_color", 1) }.keys.map { File(it).name }.toSet())
        assertEquals(setOf("embedding_user"), runBlocking { index.signals(File(root, "DCIM/Camera/a.png").path) }.map { it.detectorId }.toSet())
        assertEquals(1, stats.skipped) // the unembedded image waits for a run that embeds it
    }

    @Test
    fun `after deleting the embeddings an image that runs out of memory leaves the others their detectors`() {
        photos()
        val detectors = listOf(EmbeddingDetector())
        run(Pipeline(index, detectors = detectors, groupDetectors = emptyList(), embedder = MeanColorEmbedder()))
        runBlocking { index.deleteEmbeddings() }

        val stats = run(Pipeline(index, detectors = detectors, groupDetectors = emptyList(), embedder = OutOfMemoryEmbedder(poison)))

        assertEquals(1, stats.failures.size)
        assertEquals(2 + 2, stats.detectorRuns) // two embeddings, then the detector on those two
        for (name in listOf("DCIM/Camera/a.png", "WhatsApp/Media/a-copy.png")) {
            assertTrue(name, runBlocking { index.isAnalyzed(File(root, name).path, "embedding_user", 1) })
        }
    }

    @Test
    fun `an image that runs out of memory while decoding gets one more try alone, after its batch`() {
        photos()
        val attempts = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val decoder = { file: File ->
            attempts += file.name
            if (file.name == "Screenshot_1.png" && attempts.count { it == file.name } == 1) throw OutOfMemoryError("Failed to allocate")
            preprocess(file)
        }

        val stats = run(Pipeline(index, detectors = emptyList(), groupDetectors = emptyList(), embedder = MeanColorEmbedder(), preprocessing = decoder))

        assertEquals(emptyList<Failure>(), stats.failures)
        assertEquals(2, attempts.count { it == "Screenshot_1.png" })
        assertEquals(3, runBlocking { index.embeddings("mean_color", 1) }.size)
    }

    @Test
    fun `a decode retry after running out of memory runs alone, before any decode of the next batch`() {
        repeat(EMBEDDING_BATCH + 1) { save("DCIM/Camera/shot-$it.png", seed = it) }
        val victim = java.util.concurrent.atomic.AtomicReference<String>() // the first image decoded, so one of the first batch
        val started = AtomicInteger()
        val inFlight = AtomicInteger()
        var retry: Pair<Int, Int>? = null // decodes started, and decodes running, as the victim's retry ended
        val decoder = { file: File ->
            started.incrementAndGet()
            inFlight.incrementAndGet()
            try {
                if (victim.compareAndSet(null, file.name)) throw OutOfMemoryError("Failed to allocate")
                if (file.name == victim.get()) {
                    // Long enough for a decode of the next batch to start, if one could run beside the retry.
                    Thread.sleep(300)
                    retry = started.get() to inFlight.get()
                }
                preprocess(file)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        val stats = run(Pipeline(index, detectors = emptyList(), groupDetectors = emptyList(), embedder = MeanColorEmbedder(), preprocessing = decoder))

        assertEquals(emptyList<Failure>(), stats.failures)
        // The first batch's decodes, then the retry alone: the next batch's one image has not started yet.
        assertEquals(EMBEDDING_BATCH + 1 to 1, retry)
        assertEquals(EMBEDDING_BATCH + 2, started.get())
        assertEquals(EMBEDDING_BATCH + 1, runBlocking { index.embeddings("mean_color", 1) }.size)
    }

    @Test
    fun `an image that always runs out of memory while decoding fails alone`() {
        photos()
        val decoder = { file: File -> if (file.name == "Screenshot_1.png") throw OutOfMemoryError("Failed to allocate") else preprocess(file) }

        val stats = run(Pipeline(index, detectors = listOf(EmbeddingDetector()), groupDetectors = emptyList(), embedder = MeanColorEmbedder(), preprocessing = decoder))

        assertEquals(1, stats.failures.size)
        assertTrue(stats.failures[0].toString(), "Screenshot_1.png: embedding: OutOfMemoryError" in stats.failures[0].toString())
        assertEquals(2, runBlocking { index.embeddings("mean_color", 1) }.size)
        assertEquals(2, runBlocking { index.images() }.count { runBlocking { index.isAnalyzed(it.path, "embedding_user", 1) } })
    }

    /** Runs out of memory on [victims]' first attempt, as a healthy image does when another worker took the memory. */
    private class OutOfMemoryDetector(private val victims: Set<String>, private val always: Set<String> = emptySet()) : Detector {
        override val id = "oom_detector"
        override val version = 1
        private val inFlight = java.util.concurrent.atomic.AtomicInteger()
        private val attempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val alone = java.util.concurrent.ConcurrentLinkedQueue<String>()

        override suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal> {
            val name = File(image.path).name
            val concurrent = inFlight.incrementAndGet()
            try {
                val attempt = attempts.merge(name, 1, Int::plus)!!
                if (attempt > 1 && concurrent == 1) alone += name
                if (name in always || (name in victims && attempt == 1)) throw OutOfMemoryError("Failed to allocate")
                return listOf(Signal(id, version, Category.BLURRY, 0.5f))
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    @Test
    fun `an image that ran out of memory in a detector is not failed, it runs again alone after the others`() {
        photos()
        val detector = OutOfMemoryDetector(victims = setOf("a.png", "Screenshot_1.png"))

        val stats = run(Pipeline(index, detectors = listOf(detector), groupDetectors = emptyList()))

        assertEquals(emptyList<Failure>(), stats.failures)
        assertEquals(setOf("a.png", "Screenshot_1.png"), detector.alone.toSet())
        for (name in listOf("DCIM/Camera/a.png", "WhatsApp/Media/a-copy.png", "Pictures/Screenshots/Screenshot_1.png")) {
            assertTrue(name, runBlocking { index.isAnalyzed(File(root, name).path, "oom_detector", 1) })
        }
    }

    @Test
    fun `an image that always runs out of memory in a detector fails alone`() {
        photos()
        val detector = OutOfMemoryDetector(victims = setOf("a.png"), always = setOf("Screenshot_1.png"))

        val stats = run(Pipeline(index, detectors = listOf(detector), groupDetectors = emptyList()))

        assertEquals(1, stats.failures.size)
        assertTrue(stats.failures[0].toString(), "Screenshot_1.png: oom_detector: OutOfMemoryError" in stats.failures[0].toString())
        assertEquals(2, runBlocking { index.images() }.count { runBlocking { index.isAnalyzed(it.path, "oom_detector", 1) } })
    }

    @Test
    fun `a group detector that throws an exception records a failure and does not abort other group detectors`() {
        photos()
        val throwingDetector = object : GroupDetector {
            override val id = "throwing_group"
            override val version = 1
            override suspend fun analyze(images: List<ImageRef>, context: AnalysisContext): Map<ImageRef, List<Signal>> = throw IllegalStateException("detector exploded")
        }
        var nextRan = false
        val followingDetector = object : GroupDetector {
            override val id = "following_group"
            override val version = 1
            override suspend fun analyze(images: List<ImageRef>, context: AnalysisContext): Map<ImageRef, List<Signal>> {
                nextRan = true
                return emptyMap()
            }
        }

        val stats = run(Pipeline(index, detectors = emptyList(), groupDetectors = listOf(throwingDetector, followingDetector)))

        assertTrue(nextRan)
        assertEquals(1, stats.failures.size)
        assertEquals(Failure("", "throwing_group", "IllegalStateException: detector exploded"), stats.failures[0])
    }

    @Test
    fun `a group detector that throws CancellationException is not caught`() {
        photos()
        val cancellingDetector = object : GroupDetector {
            override val id = "cancelling_group"
            override val version = 1
            override suspend fun analyze(images: List<ImageRef>, context: AnalysisContext): Map<ImageRef, List<Signal>> = throw kotlinx.coroutines.CancellationException("job cancelled")
        }

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            run(Pipeline(index, detectors = emptyList(), groupDetectors = listOf(cancellingDetector)))
        }
    }

    /** Group detector that says when it starts and then waits for [gate]. */
    private class GatedGroupDetector(private val started: CompletableDeferred<Unit>, private val gate: CompletableDeferred<Unit>) : GroupDetector {
        override val id = "gated_group"
        override val version = 1

        override suspend fun analyze(images: List<ImageRef>, context: AnalysisContext): Map<ImageRef, List<Signal>> {
            started.complete(Unit)
            gate.await()
            return emptyMap()
        }
    }

    @Test
    fun `a stop during a group detector ends the run promptly, and the next run does only the group detector`() {
        photos()
        val started = CompletableDeferred<Unit>()
        val gated = GatedGroupDetector(started, CompletableDeferred())

        runBlocking {
            val job = launch { Pipeline(index, detectors = emptyList(), groupDetectors = listOf(gated)).run(source) }
            withTimeout(10_000) { started.await() }
            job.cancel()
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
        }
        assertNull(runBlocking { index.groupFingerprint(gated.id, gated.version) })

        val open = GatedGroupDetector(CompletableDeferred(), CompletableDeferred<Unit>().apply { complete(Unit) })
        val stats = run(Pipeline(index, detectors = emptyList(), groupDetectors = listOf(open)))

        assertEquals(1, stats.detectorRuns) // the hashes were stored by the stopped run
        assertEquals(emptyList<Failure>(), stats.failures)
        assertNotNull(runBlocking { index.groupFingerprint(open.id, open.version) })
    }

    /** An embedder whose model does not load. */
    private class BrokenModelEmbedder : Embedder {
        override val id = "broken"
        override val version = 1
        var loads = 0
        var embedded = 0

        override fun load() {
            loads++
            throw IllegalStateException("model damaged")
        }

        override fun embedPixels(pixels: List<FloatArray>): List<FloatArray> {
            embedded++
            return pixels.map { FloatArray(512) }
        }
    }

    @Test
    fun `a model that does not load is one failure of the embedding step, with no attempt per image`() {
        photos()
        val embedder = BrokenModelEmbedder()

        val stats = run(Pipeline(index, embedder = embedder))

        assertEquals(1, embedder.loads)
        assertEquals(0, embedder.embedded)
        assertEquals(listOf(Failure("", EMBEDDING_STEP, "IllegalStateException: model damaged")), stats.failures)
        assertEquals(3, stats.images)
        assertTrue(Category.EXACT_DUPLICATE in kinds("WhatsApp/Media/a-copy.png"))
        assertEquals(0, runBlocking { index.embeddings(embedder.id, embedder.version) }.size)
    }

    @Test
    fun `the model loads once for a run, not once per batch`() {
        repeat(EMBEDDING_BATCH + 4) { save("DCIM/Camera/shot-$it.png", seed = it) } // more than one batch
        var loads = 0
        val inner = MeanColorEmbedder()
        val embedder = object : Embedder by inner {
            override fun load() {
                loads++
            }
        }

        run(Pipeline(index, embedder = embedder))

        assertEquals(EMBEDDING_BATCH + 4, runBlocking { index.embeddings(embedder.id, embedder.version) }.size)
        assertEquals(1, loads)
    }

    @Test
    fun `a run with every embedding stored does not load the model`() {
        photos()
        run(Pipeline(index, embedder = MeanColorEmbedder()))
        val embedder = BrokenModelEmbedder()
        val stored = object : Embedder by MeanColorEmbedder() {
            override fun load() = embedder.load()
        }

        val stats = run(Pipeline(index, embedder = stored))

        assertEquals(0, embedder.loads)
        assertEquals(emptyList<Failure>(), stats.failures)
    }

    @Test
    fun `the next batch decodes while the model runs on the current one`() {
        repeat(2 * EMBEDDING_BATCH) { save("DCIM/Camera/shot-$it.png", seed = it) } // a whole batch ahead, more decodes than most machines have cores
        val decodes = AtomicInteger()
        val nextBatchDecoding = CountDownLatch(1)
        val modelRunning = CountDownLatch(1)
        val decodesSawModel = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
        val decoder = { file: File ->
            if (decodes.incrementAndGet() > EMBEDDING_BATCH) {
                nextBatchDecoding.countDown()
                decodesSawModel += modelRunning.await(5, TimeUnit.SECONDS)
            }
            preprocess(file)
        }
        val inner = MeanColorEmbedder()
        val modelSawDecode = mutableListOf<Boolean>()
        val embedder = object : Embedder by inner {
            override fun embedPixels(pixels: List<FloatArray>): List<FloatArray> {
                if (modelSawDecode.isEmpty()) {
                    modelRunning.countDown()
                    modelSawDecode += nextBatchDecoding.await(5, TimeUnit.SECONDS)
                }
                return inner.embedPixels(pixels)
            }
        }

        run(Pipeline(index, detectors = emptyList(), groupDetectors = emptyList(), embedder = embedder, preprocessing = decoder))

        assertEquals(listOf(true), modelSawDecode)
        assertEquals(List(EMBEDDING_BATCH) { true }, decodesSawModel.toList())
        assertEquals(2 * EMBEDDING_BATCH, runBlocking { index.embeddings(embedder.id, embedder.version) }.size)
    }

    @Test
    fun `decoding a batch ahead gives each image the embedding and signal it gets alone, in batches in index order`() {
        repeat(2 * EMBEDDING_BATCH + 4) { save("DCIM/Camera/shot-$it.png", seed = it) }
        val decodedFrom = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<FloatArray, String>())
        val decoder = { file: File -> preprocess(file).also { decodedFrom[it] = file.path } }
        val inner = MeanColorEmbedder()
        val batches = mutableListOf<List<String>>()
        val embedder = object : Embedder by inner {
            override fun embedPixels(pixels: List<FloatArray>): List<FloatArray> {
                batches += pixels.map { decodedFrom.getValue(it) }
                return inner.embedPixels(pixels)
            }
        }

        val stats = run(Pipeline(index, detectors = listOf(EmbeddingDetector()), groupDetectors = emptyList(), embedder = embedder, preprocessing = decoder))

        assertEquals(emptyList<Failure>(), stats.failures)
        val paths = runBlocking { index.images() }.map { it.path }
        assertEquals(paths.chunked(EMBEDDING_BATCH), batches)
        val stored = runBlocking { index.embeddings(embedder.id, embedder.version) }
        for (path in paths) {
            val alone = MeanColorEmbedder().embedPixels(listOf(preprocess(File(path)))).single()
            assertTrue(path, alone.contentEquals(stored.getValue(path)))
            assertEquals(listOf(alone[0]), runBlocking { index.signals(path) }.filter { it.detectorId == "embedding_user" }.map { it.score })
        }
    }

    @Test
    fun `a decode that fails in the batch ahead is a failure of that image only`() {
        repeat(2 * EMBEDDING_BATCH) { save("DCIM/Camera/shot-$it.png", seed = it) }
        val decodes = AtomicInteger()
        val broken = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val decoder = { file: File ->
            // the first decode of the second batch, which runs while the model embeds the first
            if (decodes.incrementAndGet() == EMBEDDING_BATCH + 1) {
                broken += file.path
                throw IOException("truncated")
            }
            preprocess(file)
        }

        val stats = run(Pipeline(index, detectors = emptyList(), groupDetectors = emptyList(), embedder = MeanColorEmbedder(), preprocessing = decoder))

        assertEquals(listOf(Failure(broken.single(), EMBEDDING_STEP, "IOException: truncated")), stats.failures)
        val stored = runBlocking { index.embeddings("mean_color", 1) }
        assertEquals(2 * EMBEDDING_BATCH - 1, stored.size)
        assertFalse(broken.single() in stored)
    }

    @Test
    fun `a stop while the model runs stops the decoding ahead and the model`() {
        repeat(3 * EMBEDDING_BATCH) { save("DCIM/Camera/shot-$it.png", seed = it) }
        val modelRunning = CountDownLatch(1)
        val release = CountDownLatch(1)
        val decodes = AtomicInteger()
        val decoder = { file: File ->
            if (decodes.incrementAndGet() > EMBEDDING_BATCH) release.await(5, TimeUnit.SECONDS)
            preprocess(file)
        }
        val inner = MeanColorEmbedder()
        val runs = AtomicInteger()
        val embedder = object : Embedder by inner {
            override fun embedPixels(pixels: List<FloatArray>): List<FloatArray> {
                runs.incrementAndGet()
                modelRunning.countDown()
                release.await(5, TimeUnit.SECONDS)
                return inner.embedPixels(pixels)
            }
        }

        runBlocking {
            val scan = launch(Dispatchers.Default) {
                Pipeline(index, detectors = emptyList(), groupDetectors = emptyList(), embedder = embedder, preprocessing = decoder).run(source)
            }
            assertTrue(modelRunning.await(5, TimeUnit.SECONDS))
            scan.cancel()
            release.countDown()
            withTimeout(5_000) { scan.join() }
        }

        assertEquals(1, runs.get())
        assertTrue("${decodes.get()} decodes", decodes.get() <= 2 * EMBEDDING_BATCH) // the batch after the one ahead never starts
        assertEquals(0, runBlocking { index.embeddings(embedder.id, embedder.version) }.size)
    }
}
