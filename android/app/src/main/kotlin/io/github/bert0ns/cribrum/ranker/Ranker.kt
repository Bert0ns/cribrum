// Turns signals into candidates and a review order. Mirrors lab/src/cribrum_lab/ranker.py and
// docs/design.md §6.
package io.github.bert0ns.cribrum.ranker

import io.github.bert0ns.cribrum.folders.isExcluded
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.IndexedImage
import io.github.bert0ns.cribrum.model.Protection
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Signal

/**
 * Score a signal needs to make an image a candidate. Starting points: the lab's evaluation
 * report picks them from a labeled sample, for recall. Keep them equal to the lab's.
 */
val THRESHOLDS: Map<Category, Float> = mapOf(
    Category.EXACT_DUPLICATE to 0.5f,
    Category.NEAR_DUPLICATE to 0.5f,
    Category.BURST to 0.5f,
    Category.BLURRY to 0.4f,
    Category.BAD_EXPOSURE to 0.5f,
    Category.SCREENSHOT to 0.5f,
    Category.DOCUMENT to 0.5f,
    Category.FORWARDED to 0.5f,
    Category.STICKER to 0.5f,
)

/** How much a protection signal at score 1 lowers an image's likelihood. */
val PROTECTION_WEIGHTS: Map<Protection, Float> =
    mapOf(Protection.FACES to 0.5f, Protection.FAVORITE to 0.8f, Protection.ALBUM to 0.6f)

/** Lowers images in no duplicate or burst group: nothing else keeps the same memory. */
const val UNIQUE_WEIGHT = 0.2f

val GROUP_CATEGORIES = setOf(Category.EXACT_DUPLICATE, Category.NEAR_DUPLICATE, Category.BURST)

data class Candidate(
    val image: IndexedImage,
    /**
     * Signals past their threshold, strongest first. Empty when the image is listed only for
     * "review everything".
     */
    val reasons: List<Signal>,
    /** How likely the image is deletable, comparable across categories, after protection. */
    val likelihood: Float,
    /** Factor protection signals applied to the likelihood: 1 when nothing protects it. */
    val protection: Float = 1f,
) {
    val isCandidate: Boolean get() = reasons.isNotEmpty()
}

class Ranker(private val thresholds: Map<Category, Float> = THRESHOLDS) {
    /**
     * The "review everything" order: every unreviewed image, most likely deletable first.
     *
     * Candidates always come before the other images. Protection signals lower an image within
     * its part of the order but never move a candidate behind a non-candidate. A kept image is
     * listed again only for a signal it did not have when the user kept it, such as a new
     * duplicate group or a new role in its group. A newer detector version or a higher score is
     * not new. Trashed images are never listed, nor are those in an excluded folder.
     */
    fun rank(images: List<IndexedImage>, excludedFolders: Collection<String> = emptyList()): List<Candidate> {
        val ranked = mutableListOf<Candidate>()
        for (image in images) {
            if (image.reviewState == ReviewState.TRASHED || isExcluded(image.ref.path, excludedFolders)) continue
            var reasons = image.signals.filter(::passes)
            if (image.reviewState == ReviewState.KEPT) {
                reasons = reasons.filter { it.identity !in image.seenWhenKept }
                if (reasons.isEmpty()) continue
            }
            val protection = protection(image.signals)
            val likelihood = protection * (image.signals.maxOfOrNull(::likelihood) ?: 0f)
            ranked += Candidate(image, reasons.sortedByDescending(::likelihood), likelihood, protection)
        }
        return ranked.sortedWith(
            compareBy<Candidate> { !it.isCandidate }
                .thenByDescending { it.likelihood }
                .thenBy { it.image.ref.path },
        )
    }

    /** Protection signals have no threshold, so they never pass. */
    private fun passes(signal: Signal): Boolean {
        val threshold = thresholds[signal.kind] ?: return false
        return signal.score >= threshold
    }

    /** Score / threshold below the threshold, 1 + the margin over it above, 0 for protection signals. */
    private fun likelihood(signal: Signal): Float {
        val threshold = thresholds[signal.kind] ?: return 0f
        return when {
            signal.score < threshold -> signal.score / threshold
            threshold == 1f -> 1f
            else -> 1f + (signal.score - threshold) / (1f - threshold)
        }
    }

    companion object {
        /**
         * Candidates under each category they have a reason for, highest score first, each score
         * lowered by the candidate's protection. A sticker is listed under STICKER only.
         */
        fun byCategory(ranked: List<Candidate>): Map<Category, List<Candidate>> {
            val groups = mutableMapOf<Category, MutableList<Candidate>>()
            for (candidate in ranked) {
                var categories = candidate.reasons.mapNotNull { it.kind as? Category }.toSet()
                if (Category.STICKER in categories) categories = setOf(Category.STICKER) // they would crowd every other category
                for (category in categories) {
                    groups.getOrPut(category) { mutableListOf() } += candidate
                }
            }
            return groups.mapValues { (category, candidates) ->
                candidates.sortedByDescending { c ->
                    c.protection * c.reasons.filter { it.kind == category }.maxOf { it.score }
                }
            }
        }
    }
}

private fun protection(signals: List<Signal>): Float {
    var factor = 1f
    for (signal in signals) {
        val kind = signal.kind
        if (kind is Protection) factor *= 1f - (PROTECTION_WEIGHTS[kind] ?: 0f) * signal.score
    }
    if (signals.none { it.kind in GROUP_CATEGORIES }) factor *= 1f - UNIQUE_WEIGHT // members always get one
    return factor
}
