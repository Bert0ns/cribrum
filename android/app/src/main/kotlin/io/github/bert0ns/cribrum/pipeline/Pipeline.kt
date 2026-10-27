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
import io.github.bert0ns.cribrum.trash.Trash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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
val GROUP_DETECTOR_IDS: Set<String> = GROUP_DETECTORS.map { it.id }.toSet()
val DETECTOR_CATEGORIES: Set<Category> =
    (DETECTORS.mapNotNull { it.category } + GROUP_DETECTORS.mapNotNull { it.category }).toSet()

/** Images embedded at once: bounds the memory held by decoded pixels. */
const val EMBEDDING_BATCH = 16

/** Embeds images preprocessed by [preprocess], as [io.github.bert0ns.cribrum.mobileclip.MobileClip] does. */
interface Embedder {
    val id: String
    val version: Int

    /** Loads the model, so a model that cannot load fails once, here, and not once per image. Does nothing when it is loaded. */
    fun load() {}

    fun embedPixels(pixels: List<FloatArray>): List<FloatArray>
}

/** Called with a step name, the items done and the items in total (0 while unknown). */
typealias Progress = suspend (step: String, done: Int, total: Int) -> Unit

/** An image that failed at [step]: "embedding", "perceptual hash" or a detector id. */
data class Failure(val path: String, val step: String, val error: String) {
    override fun toString() = "$path: $step: $error"
}

/** Format a Throwable as "ClassName: message" for [Failure]. */
internal fun failureMessage(error: Throwable): String = "${error.javaClass.simpleName}: ${error.message}"

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
    val failures: MutableList<Failure> = mutableListOf(),
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
    /** Lists what the trash holds, to reconcile TRASHED rows with it. Without one, TRASHED rows are left alone. */
    private val trash: Trash? = null,
    private val metadataReader: (File) -> ImageMetadata = ::readMetadata,
    private val perceptualHasher: (ImageRef) -> ULong = ::perceptualHash,
    private val contentHasher: (ImageRef) -> String = ::contentHash,
) {
    /**
     * Analyzes every image the source finds. Images it no longer finds leave the index, except
     * the trashed ones the trash still holds: restoring them must find their review state and
     * signals again (docs/design.md §9). A trashed row whose file is back on disk with no item in
     * the trash becomes unreviewed, and one that is neither on disk nor in the trash leaves the index.
     * Only images under a root the source walked can be gone: an absent SD card or an unreadable
     * folder is not a deletion.
     */
    suspend fun run(source: ImageSource, progress: Progress = { _, _, _ -> }): Stats = withContext(Dispatchers.IO) {
        val stats = Stats()
        val found = LinkedHashSet<String>()
        val before = index.reviewStates()
        source.scan().collect { image ->
            found += image.path
            if (index.record(image)) stats.newOrChanged++
            if (found.size % 100 == 0) progress("scan", found.size, 0)
        }
        // A trash that cannot be listed is a failure of this step. Its rows stay as they are until a scan can read it.
        val inTrash = try {
            trash?.trashed()?.mapTo(HashSet()) { it.original.path }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            stats.failures += Failure("", "trash", failureMessage(error))
            null
        }
        val states = index.reviewStates()
        val walked = source.walkedRoots
        // A review action may have run during the walk: only a row in the state the walk began with is judged by it.
        val judged = states.filter { (path, state) -> before[path] == state }
        val gone = judged.filter { (path, state) ->
            path !in found &&
                walked.any { root -> File(path).startsWith(root) } &&
                !File(path).exists() &&
                // a file that went and came back while the walk was in its folder is not gone
                (state != ReviewState.TRASHED || (inTrash != null && path !in inTrash))
        }.keys
        index.forget(gone)
        if (inTrash != null) {
            // The file is back on disk and the trash does not hold it: someone put it back by hand.
            judged.filter { (path, state) -> state == ReviewState.TRASHED && path in found && path !in inTrash }
                .keys.forEach { index.restoreReviewState(it, ReviewState.UNREVIEWED, emptySet(), emptySet()) }
        }
        stats.forgotten = gone.size
        stats.images = found.size

        val pending = index.withoutMetadata()
        for ((done, image) in pending.withIndex()) {
            storeMetadata(image.path)
            progress("metadata", done + 1, pending.size)
        }
        val unreadable = index.unreadable().toSet()
        stats.unreadable = found.count { it in unreadable }

        val images = index.images().filter { it.path in found }
        val readable = images.filter { it.path !in unreadable } // group detectors still see the unreadable files
        // The home screen lists candidates as soon as these are stored; the embeddings come after.
        analyzeEach(readable, detectors.filter { !it.usesEmbeddings }, emptyMap(), "detectors", beforeEmbeddings = true, stats, progress)
        // A sticker is listed under STICKER only (docs/design.md §8), so neither MobileCLIP nor text coverage runs on it.
        val (stickers, embeddable) = readable.partition { isStickerFolder(it.path) }
        // A sticker scanned before that rule keeps results the rule no longer produces: drop them so a library gives the same result either way.
        index.forgetAnalyses(stickers.map { it.path }, detectors.filter { it.usesEmbeddings }.map { it.id })
        val embeddings = if (embedder == null) {
            emptyMap()
        } else {
            embed(embedder, embeddable, stats, progress)
            val kept = embeddable.mapTo(HashSet()) { it.path }
            index.embeddings(embedder.id, embedder.version).filterKeys { it in kept }
        }
        analyzeEach(embeddable, detectors.filter { it.usesEmbeddings }, embeddings, "embedded_detectors", beforeEmbeddings = false, stats, progress)

        // The exact detector skips an image whose content hash fails: that run must not count as done for the same files.
        var contentHashFailed = false
        val context = AnalysisContext(
            contentHash = {
                try {
                    cachedContentHash(it)
                } catch (error: IOException) {
                    contentHashFailed = true
                    throw error
                }
            },
            perceptualHash = ::storedPerceptualHash,
            embedding = { embeddings[it.path] ?: throw MissingEmbedding(it) },
            metadata = ::storedMetadata,
        )
        // Embeddings change what group detectors can see: a run with them is a different run.
        val model = embedder?.let { "${it.id}:${it.version}:${embeddings.size}" }.orEmpty()
        // A hash that failed on an earlier run is a result a group detector lacked.
        val hashed = index.hashedPaths()
        val fingerprint = fingerprint(images, "$model:${images.count { it.path in hashed }}")
        for (detector in groupDetectors) {
            if (index.groupFingerprint(detector.id, detector.version) == fingerprint) continue
            progress(detector.id, 0, 1)
            contentHashFailed = false
            try {
                val signals = detector.analyze(images, context)
                index.setSignalsOfMany(detector.id, detector.version, images.associate { it.path to signals[it].orEmpty() })
                if (!contentHashFailed) index.setGroupFingerprint(detector.id, detector.version, fingerprint)
                stats.detectorRuns++
                progress(detector.id, 1, 1)
            } catch (error: Exception) {
                if (error is CancellationException || error is MissingEmbedding) throw error
                stats.failures += Failure("", detector.id, failureMessage(error))
            }
        }
        // An image forgotten, trashed or moved since the walk failed because of that, and the user can do nothing about it.
        val statesNow = index.reviewStates()
        stats.failures.removeAll { it.path.isNotEmpty() && (!File(it.path).isFile || statesNow[it.path].let { state -> state == null || state == ReviewState.TRASHED }) }
        stats
    }

    private class AnalysisTask(
        val image: ImageRef,
        val metadata: ImageMetadata,
        val embedding: FloatArray?,
        val detectors: List<Detector>,
        val needsPhash: Boolean,
        val beforeEmbeddings: Boolean,
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
        if (todo.isEmpty()) return
        val loaded = withContext(Dispatchers.Default) { attempt { embedder.load() } }
        loaded.exceptionOrNull()?.let { error ->
            // The same model fails the same way for every image: one failure for the step, and no embedding this run.
            stats.failures += Failure("", EMBEDDING_STEP, failureMessage(error))
            return
        }
        var done = 0
        fun fail(image: ImageRef, error: Throwable) {
            stats.failures += Failure(image.path, EMBEDDING_STEP, failureMessage(error))
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
     * Runs the [selected] per-image detectors on [parallelism] images at a time, storing results here,
     * in order of completion. What ran out of memory meanwhile may have lost to another worker, so it
     * runs again alone once the queue is empty. The pass [beforeEmbeddings] also computes the perceptual
     * hashes, and a detector in it that asks for an embedding fails: it should have said `usesEmbeddings`.
     */
    private suspend fun analyzeEach(
        images: List<ImageRef>,
        selected: List<Detector>,
        embeddings: Map<String, FloatArray>,
        step: String,
        beforeEmbeddings: Boolean,
        stats: Stats,
        progress: Progress,
    ) = coroutineScope {
        val analyzed = selected.associateWith { index.analyzedPaths(it.id, it.version) }
        val hashed = if (beforeEmbeddings) index.hashedPaths() else emptySet()
        val tasks = images.mapNotNull { image ->
            val todo = selected.filter { image.path !in analyzed.getValue(it) }
            val needsPhash = beforeEmbeddings && image.path !in hashed
            // No metadata: the image was trashed before its metadata was read, and a restore brings it back to the next run.
            val metadata = index.metadata(image.path)
            if ((todo.isEmpty() && !needsPhash) || metadata == null) {
                null
            } else {
                AnalysisTask(image, metadata, embeddings[image.path], todo, needsPhash, beforeEmbeddings)
            }
        }
        progress(step, 0, tasks.size)
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
            embedding = {
                check(!task.beforeEmbeddings) { "${task.image.path}: reads an embedding without usesEmbeddings" }
                task.embedding ?: throw MissingEmbedding(it)
            },
        )
        val results = task.detectors.map { detector -> detector to analyze(detector, task.image, context) }
        return AnalysisOutcome(task, results, if (task.needsPhash) computePhash(task.image) else null)
    }

    /** Stores what [outcome] has. Unless [final], what ran out of memory is not stored but returned as a task to run again. */
    private suspend fun store(outcome: AnalysisOutcome, stats: Stats, final: Boolean): AnalysisTask? = try {
        storeOutcome(outcome, stats, final)
    } catch (_: NoSuchElementException) {
        null // the row went while the scan ran: the image is gone
    }

    private suspend fun storeOutcome(outcome: AnalysisOutcome, stats: Stats, final: Boolean): AnalysisTask? {
        val path = outcome.task.image.path
        fun again(result: Result<*>) = !final && result.exceptionOrNull() is OutOfMemoryError
        for ((detector, result) in outcome.detectorResults) {
            val error = result.exceptionOrNull()
            val signals = result.getOrNull()
            when {
                again(result) -> Unit
                error != null -> stats.failures += Failure(path, detector.id, failureMessage(error))
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
                onFailure = { stats.failures += Failure(path, "perceptual hash", failureMessage(it)) },
            )
        }
        val detectorsAgain = outcome.detectorResults.filter { again(it.second) }.map { it.first }
        val phashAgain = phash != null && again(phash)
        return if (detectorsAgain.isEmpty() && !phashAgain) {
            null
        } else {
            AnalysisTask(outcome.task.image, outcome.task.metadata, outcome.task.embedding, detectorsAgain, phashAgain, outcome.task.beforeEmbeddings)
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

    private fun computePhash(image: ImageRef): Result<ULong> = attempt { perceptualHasher(image) }

    private suspend fun cachedContentHash(image: ImageRef): String = index.contentHash(image.path) ?: contentHasher(image).also { index.setContentHash(image.path, it) }

    private suspend fun storedPerceptualHash(image: ImageRef): ULong = index.perceptualHash(image.path) ?: throw NoSuchElementException(image.path)

    private suspend fun storedMetadata(image: ImageRef): ImageMetadata = index.metadata(image.path) ?: throw UnreadableImage(File(image.path))

    /** A file that is gone, like one trashed since the walk, is not broken: it gets its metadata when it is back. */
    private suspend fun storeMetadata(path: String) {
        val file = File(path)
        if (!file.isFile) return
        val metadata = try {
            metadataReader(file)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return markUnreadable(path) // decoders throw more than IOExceptions on broken files
        }
        try {
            index.setMetadata(path, metadata)
        } catch (_: NoSuchElementException) {
            // the row went while the scan ran
        }
    }

    private suspend fun markUnreadable(path: String) {
        try {
            index.setUnreadable(path)
        } catch (_: NoSuchElementException) {
            // the row went while the scan ran
        }
    }

    /** As the lab's: the same image set and inputs (embedding model, hashes) give the same text. */
    private fun fingerprint(images: List<ImageRef>, inputs: String): String {
        val digest = Algorithm.XXH3_64().createDigest()
        digest.update(inputs.toByteArray())
        for (image in images.sortedBy { it.path }) digest.update("${image.path}\u0000${image.size}\u0000${image.mtimeNs}\n".toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
