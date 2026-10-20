// Analysis pipeline: scan → metadata → per-image detectors → group detectors. Mirrors
// lab/src/cribrum_lab/pipeline.py, without the embedding step until MobileCLIP is ported.
package io.github.bert0ns.cribrum.pipeline

import com.appmattus.crypto.Algorithm
import io.github.bert0ns.cribrum.detectors.AnalysisContext
import io.github.bert0ns.cribrum.detectors.BlurDetector
import io.github.bert0ns.cribrum.detectors.Detector
import io.github.bert0ns.cribrum.detectors.ExactDuplicateDetector
import io.github.bert0ns.cribrum.detectors.ExposureDetector
import io.github.bert0ns.cribrum.detectors.GroupDetector
import io.github.bert0ns.cribrum.detectors.ScreenshotDetector
import io.github.bert0ns.cribrum.detectors.StickerDetector
import io.github.bert0ns.cribrum.hashing.contentHash
import io.github.bert0ns.cribrum.hashing.perceptualHash
import io.github.bert0ns.cribrum.index.ImageIndex
import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.metadata.UnreadableImage
import io.github.bert0ns.cribrum.metadata.readMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.sources.ImageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

val DETECTORS: List<Detector> = listOf(BlurDetector(), ExposureDetector(), ScreenshotDetector(), StickerDetector())
val GROUP_DETECTORS: List<GroupDetector> = listOf(ExactDuplicateDetector())
val DETECTOR_CATEGORIES: Set<Category> =
    (DETECTORS.mapNotNull { it.category } + GROUP_DETECTORS.mapNotNull { it.category }).toSet()

/** Called with a step name, the items done and the items in total (0 while unknown). */
typealias Progress = suspend (step: String, done: Int, total: Int) -> Unit

data class Stats(
    var images: Int = 0,
    var newOrChanged: Int = 0,
    var forgotten: Int = 0,
    var unreadable: Int = 0,
    /** Per-image analyses, perceptual hashes, and group detector runs. */
    var detectorRuns: Int = 0,
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
    /** Images analyzed at once: decoding is the slow part, and it runs on every core. */
    private val parallelism: Int = Runtime.getRuntime().availableProcessors(),
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
        analyzeEach(images.filter { it.path !in unreadable }, stats, progress)

        val context = AnalysisContext(
            contentHash = ::cachedContentHash,
            perceptualHash = ::storedPerceptualHash,
            metadata = ::storedMetadata,
        )
        val fingerprint = fingerprint(images)
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
        val detectors: List<Detector>,
        val needsPhash: Boolean,
    )

    private class AnalysisOutcome(
        val image: ImageRef,
        val detectorResults: List<Triple<Detector, List<Signal>?, String?>>,
        val phash: Result<ULong>?,
    )

    /** Runs the per-image detectors on [parallelism] images at a time, storing results here, in order of completion. */
    private suspend fun analyzeEach(images: List<ImageRef>, stats: Stats, progress: Progress) = coroutineScope {
        val tasks = images.mapNotNull { image ->
            val todo = detectors.filter { !index.isAnalyzed(image.path, it.id, it.version) }
            val needsPhash = index.perceptualHash(image.path) == null
            if (todo.isEmpty() && !needsPhash) null else AnalysisTask(image, index.metadata(image.path)!!, todo, needsPhash)
        }
        val queue = Channel<AnalysisTask>(Channel.UNLIMITED)
        tasks.forEach(queue::trySend)
        queue.close()
        val results = Channel<AnalysisOutcome>(parallelism)
        val workers = List(parallelism) {
            launch(Dispatchers.Default) {
                for (task in queue) {
                    val context = AnalysisContext(metadata = { task.metadata })
                    val outcomes = task.detectors.map { detector -> analyze(detector, task.image, context) }
                    val phash = if (task.needsPhash) computePhash(task.image) else null
                    results.send(AnalysisOutcome(task.image, outcomes, phash))
                }
            }
        }
        launch {
            workers.forEach { it.join() }
            results.close()
        }
        var done = 0
        for (outcome in results) {
            for ((detector, signals, failure) in outcome.detectorResults) {
                if (signals != null) {
                    index.setSignals(outcome.image.path, detector.id, detector.version, signals)
                    stats.detectorRuns++
                } else {
                    stats.failures += "${outcome.image.path}: ${detector.id}: $failure"
                }
            }
            if (outcome.phash != null) {
                outcome.phash.fold(
                    onSuccess = {
                        index.setPerceptualHash(outcome.image.path, it)
                        stats.detectorRuns++
                    },
                    onFailure = {
                        stats.failures += "${outcome.image.path}: perceptual hash: ${it.javaClass.simpleName}: ${it.message}"
                    }
                )
            }
            progress("detectors", ++done, tasks.size)
        }
    }

    /** One broken file must not stop a run over thousands: errors come back as text. */
    private suspend fun analyze(detector: Detector, image: ImageRef, context: AnalysisContext) = try {
        Triple(detector, detector.analyze(image, context), null)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Triple(detector, null, "${error::class.simpleName}: ${error.message}")
    }

    private fun computePhash(image: ImageRef): Result<ULong> = try {
        Result.success(perceptualHash(image))
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Result.failure(error)
    }

    private suspend fun cachedContentHash(image: ImageRef): String =
        index.contentHash(image.path) ?: contentHash(image).also { index.setContentHash(image.path, it) }

    private suspend fun storedPerceptualHash(image: ImageRef): ULong =
        index.perceptualHash(image.path) ?: throw NoSuchElementException(image.path)

    private suspend fun storedMetadata(image: ImageRef): ImageMetadata =
        index.metadata(image.path) ?: throw UnreadableImage(File(image.path))

    /** The lab's fingerprint without a model: the same image set gives the same text. */
    private fun fingerprint(images: List<ImageRef>): String {
        val digest = Algorithm.XXH3_64().createDigest()
        for (image in images.sortedBy { it.path }) digest.update("${image.path}\u0000${image.size}\u0000${image.mtimeNs}\n".toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
