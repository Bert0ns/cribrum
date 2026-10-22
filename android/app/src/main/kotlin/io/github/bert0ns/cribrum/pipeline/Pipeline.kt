// Analysis pipeline: scan → metadata → detectors that read no embedding → embeddings → detectors that
// read one → group detectors. Mirrors lab/src/cribrum_lab/pipeline.py.
package io.github.bert0ns.cribrum.pipeline

import com.appmattus.crypto.Algorithm
import io.github.bert0ns.cribrum.detectors.AnalysisContext
import io.github.bert0ns.cribrum.detectors.BlurDetector
import io.github.bert0ns.cribrum.detectors.BurstDetector
import io.github.bert0ns.cribrum.detectors.Detector
import io.github.bert0ns.cribrum.detectors.DocumentDetector
import io.github.bert0ns.cribrum.detectors.ExactDuplicateDetector
import io.github.bert0ns.cribrum.detectors.ExposureDetector
import io.github.bert0ns.cribrum.detectors.FaceDetector
import io.github.bert0ns.cribrum.detectors.ForwardedDetector
import io.github.bert0ns.cribrum.detectors.GroupDetector
import io.github.bert0ns.cribrum.detectors.MissingEmbedding
import io.github.bert0ns.cribrum.detectors.NearDuplicateDetector
import io.github.bert0ns.cribrum.detectors.ScreenshotDetector
import io.github.bert0ns.cribrum.detectors.StickerDetector
import io.github.bert0ns.cribrum.folders.isStickerFolder
import io.github.bert0ns.cribrum.hashing.contentHash
import io.github.bert0ns.cribrum.hashing.perceptualHash
import io.github.bert0ns.cribrum.index.ImageIndex
import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.metadata.UnreadableImage
import io.github.bert0ns.cribrum.metadata.readMetadata
import io.github.bert0ns.cribrum.mobileclip.preprocess
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.sources.ImageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

val DETECTORS: List<Detector> = listOf(
    BlurDetector(),
    ExposureDetector(),
    ScreenshotDetector(),
    DocumentDetector(),
    ForwardedDetector(),
    FaceDetector(),
    StickerDetector(),
)
val GROUP_DETECTORS: List<GroupDetector> = listOf(ExactDuplicateDetector(), NearDuplicateDetector(), BurstDetector())
val DETECTOR_CATEGORIES: Set<Category> =
    (DETECTORS.mapNotNull { it.category } + GROUP_DETECTORS.mapNotNull { it.category }).toSet()

/** Images embedded at once: bounds the memory held by decoded pixels. */
const val EMBEDDING_BATCH = 16

/** Embeds images preprocessed by [preprocess], as [io.github.bert0ns.cribrum.mobileclip.MobileClip] does. */
interface Embedder {
    val id: String
    val version: Int

    fun embedPixels(pixels: List<FloatArray>): List<FloatArray>
}

/** Called with a step name, the items done and the items in total (0 while unknown). */
typealias Progress = suspend (step: String, done: Int, total: Int) -> Unit

data class Stats(
    var images: Int = 0,
    var newOrChanged: Int = 0,
    var forgotten: Int = 0,
    var unreadable: Int = 0,
    /** Per-image analyses, perceptual hashes and embeddings computed, plus group detector runs. */
    var detectorRuns: Int = 0,
    /** Per-image analyses left for a run with embeddings. */
    var skipped: Int = 0,
    /** Analyses that threw. They are not stored, so the next run retries them. */
    val failures: MutableList<String> = mutableListOf(),
)

/**
 * Every step stores its results as it goes and skips what the index already holds for the same
 * detector version, so an interrupted run resumes where it stopped and a second run on unchanged
 * data does no detector work.
 */
class Pipeline(
    private val index: ImageIndex,
    private val detectors: List<Detector> = DETECTORS,
    private val groupDetectors: List<GroupDetector> = GROUP_DETECTORS,
    /** Without one no embeddings are computed, and detectors that need one wait for a run with it. */
    private val embedder: Embedder? = null,
    /** Images analyzed at once: decoding is the slow part, and it runs on every core. */
    private val parallelism: Int = Runtime.getRuntime().availableProcessors(),
    private val preprocessing: (File) -> FloatArray = ::preprocess,
) {
    /**
     * Analyzes every image the source finds. Images it no longer finds leave the index, except
     * the trashed ones: they are in the app trash, and restoring them must find their review
     * state and signals again (docs/design.md §9).
     */
    suspend fun run(source: ImageSource, progress: Progress = { _, _, _ -> }): Stats = withContext(Dispatchers.IO) {
        val stats = Stats()
        val found = LinkedHashSet<String>()
        source.scan().collect { image ->
            found += image.path
            if (index.record(image)) stats.newOrChanged++
            if (found.size % 100 == 0) progress("scan", found.size, 0)
        }
        val trashed = index.indexedImages().filter { it.reviewState == ReviewState.TRASHED }.map { it.ref.path }.toSet()
        val gone = index.images().map { it.path }.filter { it !in found && it !in trashed }
        index.forget(gone)
        stats.forgotten = gone.size
        stats.images = found.size

        val pending = index.withoutMetadata()
        for ((done, image) in pending.withIndex()) {
            try {
                index.setMetadata(image.path, readMetadata(File(image.path)))
            } catch (_: UnreadableImage) {
                index.setUnreadable(image.path)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                index.setUnreadable(image.path) // decoders throw more than IOExceptions on broken files
            }
            progress("metadata", done + 1, pending.size)
        }
        val unreadable = index.unreadable().toSet()
        stats.unreadable = found.count { it in unreadable }

        val images = index.images().filter { it.path in found }
        val readable = images.filter { it.path !in unreadable } // group detectors still see the unreadable files
        // The home screen lists candidates as soon as these are stored; the embeddings come after.
        analyzeEach(readable, detectors.filter { !it.usesEmbeddings }, emptyMap(), "detectors", withPhash = true, stats, progress)
        // A sticker is listed under STICKER only (docs/design.md §8), so no model runs on it.
        val embeddable = readable.filter { !isStickerFolder(it.path) }
        val embeddings = if (embedder == null) {
            emptyMap()
        } else {
            embed(embedder, embeddable, stats, progress)
            val kept = embeddable.mapTo(HashSet()) { it.path }
            index.embeddings(embedder.id, embedder.version).filterKeys { it in kept }
        }
        analyzeEach(embeddable, detectors.filter { it.usesEmbeddings }, embeddings, "embedded_detectors", withPhash = false, stats, progress)

        val context = AnalysisContext(
            contentHash = ::cachedContentHash,
            perceptualHash = ::storedPerceptualHash,
            embedding = { embeddings[it.path] ?: throw MissingEmbedding(it) },
            metadata = ::storedMetadata,
        )
        // Embeddings change what group detectors can see: a run with them is a different run.
        val model = embedder?.let { "${it.id}:${it.version}:${embeddings.size}" }.orEmpty()
        val fingerprint = fingerprint(images, model)
        for (detector in groupDetectors) {
            if (index.groupFingerprint(detector.id, detector.version) == fingerprint) continue
            progress(detector.id, 0, 1)
            val signals = detector.analyze(images, context)
            index.setSignalsOfMany(detector.id, detector.version, images.associate { it.path to signals[it].orEmpty() })
            index.setGroupFingerprint(detector.id, detector.version, fingerprint)
            stats.detectorRuns++
            progress(detector.id, 1, 1)
        }
        stats
    }

    private class AnalysisTask(
        val image: ImageRef,
        val metadata: ImageMetadata,
        val embedding: FloatArray?,
        val detectors: List<Detector>,
        val needsPhash: Boolean,
    )

    private class AnalysisOutcome(
        val task: AnalysisTask,
        val detectorResults: List<Pair<Detector, Result<List<Signal>?>>>,
        val phash: Result<ULong>?,
    )

    /**
     * Decodes on every core, then runs the model on a batch at a time. An image that fails is a
     * failure of that image only: it gets no embedding and the run goes on.
     */
    private suspend fun embed(embedder: Embedder, images: List<ImageRef>, stats: Stats, progress: Progress) {
        val stored = index.embeddings(embedder.id, embedder.version).keys
        val todo = images.filter { it.path !in stored }
        var done = 0
        fun fail(image: ImageRef, error: Throwable) {
            stats.failures += "${image.path}: embedding: ${error.javaClass.simpleName}: ${error.message}"
        }
        for (batch in todo.chunked(EMBEDDING_BATCH)) {
            val decoded = coroutineScope {
                batch.map { image -> async(Dispatchers.Default) { image to attempt { preprocessing(File(image.path)) } } }.awaitAll()
            }
            val pixels = mutableListOf<Pair<ImageRef, FloatArray>>()
            val outOfMemory = mutableListOf<ImageRef>()
            for ((image, outcome) in decoded) {
                val error = outcome.exceptionOrNull()
                when {
                    error == null -> pixels += image to outcome.getOrThrow()
                    error is OutOfMemoryError -> outOfMemory += image
                    else -> fail(image, error)
                }
            }
            // Other decodes may have taken the memory this one needed, so it gets one more go, alone.
            for (image in outOfMemory) {
                attempt { preprocessing(File(image.path)) }.fold({ pixels += image to it }, { fail(image, it) })
            }
            val vectors = withContext(Dispatchers.Default) { embedBatch(embedder, pixels, ::fail) }
            if (vectors.isNotEmpty()) {
                // A detector's result belongs to the embedding it read: a new one means analyzing again.
                index.forgetAnalyses(vectors.keys.toList(), detectors.filter { it.usesEmbeddings }.map { it.id })
                index.setEmbeddings(embedder.id, embedder.version, vectors)
                stats.detectorRuns += vectors.size
            }
            done += batch.size
            progress("embeddings", done, todo.size)
        }
    }

    /** One call for the batch. If it throws, one call per image finds which images it throws for. */
    private fun embedBatch(embedder: Embedder, pixels: List<Pair<ImageRef, FloatArray>>, fail: (ImageRef, Throwable) -> Unit): Map<String, FloatArray> {
        if (pixels.isEmpty()) return emptyMap()
        attempt { embedder.embedPixels(pixels.map { it.second }) }.onSuccess { return pixels.map { it.first.path }.zip(it).toMap() }
        return pixels.mapNotNull { (image, plane) ->
            val vector = attempt { embedder.embedPixels(listOf(plane)).single() }
            vector.exceptionOrNull()?.let { fail(image, it) }
            vector.getOrNull()?.let { image.path to it }
        }.toMap()
    }

    /** An image's failure, [OutOfMemoryError] included, comes back as a value, so it never stops the run. */
    private inline fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Result.failure(error)
    } catch (error: OutOfMemoryError) {
        Result.failure(error)
    }

    /**
     * Runs the [selected] per-image detectors, and the perceptual hash if [withPhash], on [parallelism]
     * images at a time, storing results here, in order of completion. What ran out of memory meanwhile may have lost to another worker, so it runs
     * again alone once the queue is empty.
     */
    private suspend fun analyzeEach(
        images: List<ImageRef>,
        selected: List<Detector>,
        embeddings: Map<String, FloatArray>,
        step: String,
        withPhash: Boolean,
        stats: Stats,
        progress: Progress,
    ) = coroutineScope {
        val tasks = images.mapNotNull { image ->
            val todo = selected.filter { !index.isAnalyzed(image.path, it.id, it.version) }
            val needsPhash = withPhash && index.perceptualHash(image.path) == null
            if (todo.isEmpty() && !needsPhash) {
                null
            } else {
                AnalysisTask(image, index.metadata(image.path)!!, embeddings[image.path], todo, needsPhash)
            }
        }
        val queue = Channel<AnalysisTask>(Channel.UNLIMITED)
        tasks.forEach(queue::trySend)
        queue.close()
        val results = Channel<AnalysisOutcome>(parallelism)
        val workers = List(parallelism) {
            launch(Dispatchers.Default) {
                for (task in queue) results.send(analyze(task))
            }
        }
        launch {
            workers.forEach { it.join() }
            results.close()
        }
        var done = 0
        val again = mutableListOf<AnalysisTask>()
        for (outcome in results) {
            store(outcome, stats, final = false)?.let(again::add)
            progress(step, ++done, tasks.size)
        }
        for (task in again) store(withContext(Dispatchers.Default) { analyze(task) }, stats, final = true)
    }

    private suspend fun analyze(task: AnalysisTask): AnalysisOutcome {
        val context = AnalysisContext(
            metadata = { task.metadata },
            embedding = { task.embedding ?: throw MissingEmbedding(it) },
        )
        val results = task.detectors.map { detector -> detector to analyze(detector, task.image, context) }
        return AnalysisOutcome(task, results, if (task.needsPhash) computePhash(task.image) else null)
    }

    /** Stores what [outcome] has. Unless [final], what ran out of memory is not stored but returned as a task to run again. */
    private suspend fun store(outcome: AnalysisOutcome, stats: Stats, final: Boolean): AnalysisTask? {
        val path = outcome.task.image.path
        fun again(result: Result<*>) = !final && result.exceptionOrNull() is OutOfMemoryError
        for ((detector, result) in outcome.detectorResults) {
            val error = result.exceptionOrNull()
            val signals = result.getOrNull()
            when {
                again(result) -> Unit
                error != null -> stats.failures += "$path: ${detector.id}: ${error::class.simpleName}: ${error.message}"
                signals == null -> stats.skipped++ // the embedding it needs is missing
                else -> {
                    index.setSignals(path, detector.id, detector.version, signals)
                    stats.detectorRuns++
                }
            }
        }
        val phash = outcome.phash
        if (phash != null && !again(phash)) {
            phash.fold(
                onSuccess = {
                    index.setPerceptualHash(path, it)
                    stats.detectorRuns++
                },
                onFailure = { stats.failures += "$path: perceptual hash: ${it.javaClass.simpleName}: ${it.message}" },
            )
        }
        val detectorsAgain = outcome.detectorResults.filter { again(it.second) }.map { it.first }
        val phashAgain = phash != null && again(phash)
        return if (detectorsAgain.isEmpty() && !phashAgain) {
            null
        } else {
            AnalysisTask(outcome.task.image, outcome.task.metadata, outcome.task.embedding, detectorsAgain, phashAgain)
        }
    }

    /**
     * One broken file must not stop a run over thousands: errors come back as values. A detector
     * that needs the missing embedding comes back with null signals and no error.
     */
    private suspend fun analyze(detector: Detector, image: ImageRef, context: AnalysisContext): Result<List<Signal>?> {
        val result = attempt<List<Signal>?> { detector.analyze(image, context) }
        return if (result.exceptionOrNull() is MissingEmbedding) Result.success(null) else result
    }

    private fun computePhash(image: ImageRef): Result<ULong> = attempt { perceptualHash(image) }

    private suspend fun cachedContentHash(image: ImageRef): String = index.contentHash(image.path) ?: contentHash(image).also { index.setContentHash(image.path, it) }

    private suspend fun storedPerceptualHash(image: ImageRef): ULong = index.perceptualHash(image.path) ?: throw NoSuchElementException(image.path)

    private suspend fun storedMetadata(image: ImageRef): ImageMetadata = index.metadata(image.path) ?: throw UnreadableImage(File(image.path))

    /** As the lab's: the same image set and model give the same text. */
    private fun fingerprint(images: List<ImageRef>, model: String): String {
        val digest = Algorithm.XXH3_64().createDigest()
        digest.update(model.toByteArray())
        for (image in images.sortedBy { it.path }) digest.update("${image.path}\u0000${image.size}\u0000${image.mtimeNs}\n".toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
