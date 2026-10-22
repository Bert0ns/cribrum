package io.github.bert0ns.cribrum.pipeline

import io.github.bert0ns.cribrum.detectors.Detector
import io.github.bert0ns.cribrum.detectors.GroupDetector
import io.github.bert0ns.cribrum.folders.isStickerFolder
import io.github.bert0ns.cribrum.index.ImageIndex
import io.github.bert0ns.cribrum.mobileclip.MODEL_ID
import io.github.bert0ns.cribrum.mobileclip.MODEL_VERSION

/** The [Failure.step] of an image the model could not embed, and the id of its row. */
const val EMBEDDING_STEP = "embedding"

/** How many of the images that need [id] have its result at [version]. For the embedding, that is the model's version (docs/design.md §8). */
data class Coverage(val id: String, val version: Int, val analysed: Int, val needed: Int)

/**
 * The embedding first, then each detector. Images in the trash are left out, since the scan no longer
 * looks at them. A sticker needs neither the embedding nor the detectors that read it (§5), so an
 * embedding or a result it still has from an earlier scan counts for nothing. Group detectors see
 * every image the scan finds, unreadable ones too.
 */
suspend fun ImageIndex.coverage(
    detectors: List<Detector> = DETECTORS,
    groupDetectors: List<GroupDetector> = GROUP_DETECTORS,
    model: String = MODEL_ID,
    modelVersion: Int = MODEL_VERSION,
): List<Coverage> {
    val readable = readableNotTrashed().toSet()
    val embeddable = readable.filterNotTo(HashSet(), ::isStickerFolder)
    val everything = notTrashed().mapTo(HashSet()) { it.path }

    suspend fun row(id: String, version: Int, needed: Set<String>, analysed: Set<String>) = Coverage(id, version, needed.count { it in analysed }, needed.size)
    return listOf(row(EMBEDDING_STEP, modelVersion, embeddable, embeddedPaths(model, modelVersion))) +
        detectors.map { row(it.id, it.version, if (it.usesEmbeddings) embeddable else readable, analyzedPaths(it.id, it.version)) } +
        groupDetectors.map { row(it.id, it.version, everything, analyzedPaths(it.id, it.version)) }
}
