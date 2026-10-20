// What the review screens show and do (docs/design.md §8): candidates in pages per category, and
// every decision applied to the trash and the index together, so each one can be undone.
package io.github.bert0ns.cribrum.review

import io.github.bert0ns.cribrum.index.ImageIndex
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.IndexedImage
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.ranker.Candidate
import io.github.bert0ns.cribrum.ranker.GROUP_CATEGORIES
import io.github.bert0ns.cribrum.ranker.Ranker
import io.github.bert0ns.cribrum.trash.Trash
import io.github.bert0ns.cribrum.trash.TrashedImage

/** Images shown at once: a few screens of thumbnails, reviewed and applied together. */
const val PAGE_SIZE = 48

/**
 * Images reviewed together. A plain category has one image per group. A duplicate or burst group
 * also holds its best copy, which is never a candidate: it is shown, and kept, so the user can
 * compare the copies side by side.
 */
data class ReviewGroup(val id: String, val images: List<ReviewItem>)

data class ReviewItem(
    val image: ImageRef,
    /** What the screen preselects: trash the candidates, keep the best copy of a group. */
    val suggestTrash: Boolean,
    val isBest: Boolean = false,
    /** The candidate's score in this category, 0 for a best copy. */
    val score: Float = 0f,
)

data class CategorySummary(val category: Category, val count: Int)

/** The groups shown now, and how many candidates the category has left in all, these included. */
data class Page(val groups: List<ReviewGroup>, val remaining: Int)

/** What applying a page did, so it can be undone. */
data class Decision(val trashed: List<ImageRef>, val kept: List<ImageRef>, val failed: List<ImageRef>)

class Review(private val index: ImageIndex, private val trash: Trash, private val ranker: Ranker = Ranker()) {
    /** Categories with something to review, the ones with the most candidates first. */
    suspend fun categories(): List<CategorySummary> = Ranker.byCategory(ranker.rank(index.indexedImages()))
        .map { (category, candidates) -> CategorySummary(category, candidates.size) }
        .sortedByDescending { it.count }

    /** The first page of a category: groups in the ranker's order, never split across pages. */
    suspend fun page(category: Category): Page {
        val images = index.indexedImages()
        val candidates = Ranker.byCategory(ranker.rank(images))[category].orEmpty()
        val groups = if (category in GROUP_CATEGORIES) groups(category, candidates, images) else singles(category, candidates)
        val page = mutableListOf<ReviewGroup>()
        var size = 0
        for (group in groups) {
            if (page.isNotEmpty() && size + group.images.size > PAGE_SIZE) break
            page += group
            size += group.images.size
        }
        return Page(page, candidates.size)
    }

    /**
     * Moves [toTrash] to the trash and keeps [toKeep]. A file that changed or went missing since
     * its scan stays where it is and in the review: it is listed in [Decision.failed].
     */
    suspend fun apply(toTrash: List<ImageRef>, toKeep: List<ImageRef>): Decision {
        val moved = trash.moveToTrash(toTrash)
        moved.forEach { index.setReviewState(it.path, ReviewState.TRASHED) }
        toKeep.forEach { index.setReviewState(it.path, ReviewState.KEPT) }
        return Decision(moved, toKeep, toTrash - moved.toSet())
    }

    /** Puts every image of a decision back as it was before: in its folder, and unreviewed. */
    suspend fun undo(decision: Decision): List<ImageRef> {
        val restored = trash.restore(decision.trashed)
        (restored + decision.kept).forEach { index.setReviewState(it.path, ReviewState.UNREVIEWED) }
        return decision.trashed - restored.toSet()
    }

    suspend fun trashed(): List<TrashedImage> = trash.trashed()

    /** Restoring from the trash is a decision to keep: the image is not suggested again for the same reasons. */
    suspend fun restore(images: List<ImageRef>): List<ImageRef> {
        val restored = trash.restore(images)
        restored.forEach { index.setReviewState(it.path, ReviewState.KEPT) }
        return restored
    }

    /** Deletes images from the trash for good, and from the index with them. */
    suspend fun purge(images: List<ImageRef>) {
        trash.purge(images)
        index.forget(images.map { it.path })
    }

    private fun singles(category: Category, candidates: List<Candidate>) = candidates.map {
        ReviewGroup(it.image.ref.path, listOf(ReviewItem(it.image.ref, suggestTrash = true, score = score(it, category))))
    }

    private fun groups(category: Category, candidates: List<Candidate>, images: List<IndexedImage>): List<ReviewGroup> {
        val members = mutableMapOf<String, MutableList<Pair<IndexedImage, Role>>>()
        for (image in images) {
            if (image.reviewState == ReviewState.TRASHED) continue
            for (signal in image.signals) {
                if (signal.kind != category) continue
                val group = signal.evidence[GROUP_KEY] ?: continue
                val role = Role.entries.firstOrNull { it.value == signal.evidence[ROLE_KEY] } ?: continue
                members.getOrPut(group) { mutableListOf() } += image to role
            }
        }
        val order = LinkedHashSet<String>()
        val candidateScores = mutableMapOf<String, Float>()
        for (candidate in candidates) {
            for (signal in candidate.reasons.filter { it.kind == category }) {
                signal.evidence[GROUP_KEY]?.let(order::add)
            }
            candidateScores[candidate.image.ref.path] = score(candidate, category)
        }
        return order.map { group ->
            val items = members[group].orEmpty().map { (image, role) ->
                val best = role == Role.BEST
                ReviewItem(image.ref, suggestTrash = !best && image.ref.path in candidateScores, isBest = best,
                    score = candidateScores[image.ref.path] ?: 0f)
            }
            ReviewGroup(group, items.sortedByDescending { it.isBest })
        }
    }

    private fun score(candidate: Candidate, category: Category) =
        candidate.reasons.filter { it.kind == category }.maxOf { it.score }
}
