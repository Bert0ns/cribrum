// Detector interfaces. Every analysis technique implements one of them. Mirrors
// lab/src/cribrum_lab/detectors/base.py and docs/design.md §7.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.hashing.contentHash as hashFile
import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.metadata.readMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Signal
import java.io.File

/**
 * Shared, read-only information available to detectors.
 *
 * Per-image lookups compute their value from the file by default; the pipeline replaces them
 * with lookups backed by the index, so each value is computed once. Grows as detectors need it
 * (perceptual hashes, embeddings).
 */
data class AnalysisContext(
    val contentHash: suspend (ImageRef) -> String = { hashFile(it) },
    /** Throws [io.github.bert0ns.cribrum.metadata.UnreadableImage] when the header can't be decoded. */
    val metadata: suspend (ImageRef) -> ImageMetadata = { readMetadata(File(it.path)) },
    /** Known screen resolutions as (short side, long side). */
    val screenSizes: Set<Pair<Int, Int>> = SCREEN_SIZES,
)

/**
 * Analyzes one image and emits zero or more signals.
 *
 * Bump [version] whenever the output for the same input can change, so stored signals from older
 * versions get recomputed. Keep it equal to the lab's version of the same detector.
 */
interface Detector {
    val id: String
    val version: Int
    val category: Category? get() = null

    suspend fun analyze(image: ImageRef, context: AnalysisContext): List<Signal>
}

/**
 * Analyzes all images at once and emits signals for groups of them (duplicates, bursts).
 *
 * Every member of a group gets a signal, so the review screen can show the whole group. Its
 * evidence holds the group id under `GROUP_KEY` and the member's role under `ROLE_KEY`: the copy
 * to keep is `Role.BEST` with score 0, so no threshold ever suggests it; the others are
 * `Role.INFERIOR`. Images in no group are left out of the result.
 */
interface GroupDetector {
    val id: String
    val version: Int
    val category: Category? get() = null

    suspend fun analyze(images: List<ImageRef>, context: AnalysisContext): Map<ImageRef, List<Signal>>
}
