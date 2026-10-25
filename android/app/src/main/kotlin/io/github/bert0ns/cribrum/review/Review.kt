// What the review screens show and do (docs/design.md §8): candidates in pages per category, and
// every decision applied to the trash and the index together, so each one can be undone.
package io.github.bert0ns.cribrum.review

import io.github.bert0ns.cribrum.folders.isExcluded
import io.github.bert0ns.cribrum.index.ImageIndex
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.IndexedImage
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.pipeline.DETECTOR_CATEGORIES
import io.github.bert0ns.cribrum.ranker.Candidate
import io.github.bert0ns.cribrum.ranker.GROUP_CATEGORIES
import io.github.bert0ns.cribrum.ranker.Ranker
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import io.github.bert0ns.cribrum.trash.Trash
import io.github.bert0ns.cribrum.trash.TrashedImage
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/** Images shown at once: a few screens of thumbnails, reviewed and applied together. */
const val PAGE_SIZE = 48

/**
 * Images reviewed together. A plain category has one image per group. A duplicate or burst group
 * also holds its best copy, which is never a candidate: it is shown, and kept, so the user can
 * compare the copies side by side.
 */
data class ReviewGroup(
    val id: String,
    val images: List<ReviewItem>,
    /** A copy sits in an excluded folder. It is not listed, so the screen says it exists. */
    val hasExcludedCopy: Boolean = false,
    /** The best copy is in the trash. Nothing is preselected to trash. */
    val hasTrashedBestCopy: Boolean = false,
    /** The day, week or month the group is shown under when the review is grouped by date. */
    val date: LocalDate? = null,
)

data class ReviewItem(
    val image: ImageRef,
    /** What the screen preselects: trash the candidates, keep the best copy of a group. */
    val suggestTrash: Boolean,
    val isBest: Boolean = false,
    /** The candidate's score in this category, 0 for a best copy. */
    val score: Float = 0f,
    /**
     * The signals that make it a candidate, strongest first. A group member that is not a candidate,
     * like the best copy or a kept one, has its own signal in the category instead: it says why the
     * member is in the group, and is no reason to trash it.
     */
    val reasons: List<Signal> = emptyList(),
    /**
     * Whether keeping this image on this page records it as KEPT in the index.
     * False for a member that is a candidate in another category or a sticker, so it can be
     * marked in the page where it is listed.
     */
    val keepable: Boolean = true,
)

data class CategorySummary(val category: Category, val count: Int, val hasDetector: Boolean = true, val bytes: Long = 0)

/** The home screen: every category, and the size of all the candidates together, each image counted once. */
data class Overview(val categories: List<CategorySummary>, val freeableBytes: Long)

/** The groups shown now, and how many candidates the category has left in all, these included and the skipped ones not. */
data class Page(val groups: List<ReviewGroup>, val remaining: Int)

/** What applying a page did, so it can be undone. */
data class Decision(val trashed: List<ImageRef>, val kept: List<ImageRef>, val failed: List<ImageRef>)

class Review(
    private val index: ImageIndex,
    private val trash: Trash,
    /** Read on every call, so a threshold the user changes applies to the next list without a scan. */
    private val thresholds: () -> Map<Category, Float> = { THRESHOLDS },
    private val detectorCategories: Set<Category> = DETECTOR_CATEGORIES,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val firstDayOfWeek: () -> DayOfWeek = { WeekFields.of(Locale.getDefault()).firstDayOfWeek },
) {
    /**
     * Every category in fixed order, with its candidate count and size, and whether it has a detector,
     * and the union of all the candidates by path. The figures of two categories overlap, so they are
     * not added. One ranking serves both, so the home screen reads the index once.
     */
    suspend fun overview(): Overview {
        val byCategory = byCategory(thresholds())
        val categories = Category.entries.map { category ->
            val candidates = byCategory[category].orEmpty()
            CategorySummary(category, candidates.size, category in detectorCategories, candidates.sumOf { it.image.ref.size })
        }
        val union = byCategory.values.flatten().associate { it.image.ref.path to it.image.ref.size }
        return Overview(categories, union.values.sum())
    }

    /** How many candidates each category would list at [thresholds], which are the ones in use by default. */
    suspend fun counts(thresholds: Map<Category, Float> = this.thresholds()): Map<Category, Int> {
        val byCategory = byCategory(thresholds)
        return Category.entries.associateWith { byCategory[it]?.size ?: 0 }
    }

    private suspend fun byCategory(thresholds: Map<Category, Float>) = Ranker.byCategory(Ranker(thresholds).rank(index.indexedImages(), index.excludedFolders()))

    /**
     * The first page of a category: groups in the ranker's order, never split across pages. Groups
     * whose id is in [skipped] are left out, and they are not counted in the remaining candidates.
     * With a date [grouping], the newest date comes first and the ranker's order holds inside each date.
     * With [ReviewOrder.SIZE], the group with the most bytes to trash comes first and the ranker's order breaks a tie.
     */
    suspend fun page(category: Category, skipped: Set<String> = emptySet(), grouping: ReviewOrder = ReviewOrder.OFF): Page {
        val excluded = index.excludedFolders()
        val images = index.indexedImages()
        val byCategory = Ranker.byCategory(Ranker(thresholds()).rank(images, excluded))
        val candidates = byCategory[category].orEmpty()
        val (hidden, groups) = (
            if (category in GROUP_CATEGORIES) {
                val otherCandidates = byCategory.filterKeys { it != category }.values.flatten().map { it.image.ref.path }.toSet()
                groups(category, candidates, images, excluded, otherCandidates)
            } else {
                singles(category, candidates, grouping)
            }
            )
            .let { all -> if (grouping == ReviewOrder.SIZE) all.sortedByDescending { group -> group.images.filter { it.suggestTrash }.sumOf { it.image.size } } else all }
            .partition { it.id in skipped }
        val page = mutableListOf<ReviewGroup>()
        var size = 0
        for (group in groups) {
            if (page.isNotEmpty() && size + group.images.size > PAGE_SIZE) break
            page += group
            size += group.images.size
        }
        val skippedCandidates = hidden.sumOf { group -> group.images.count { it.suggestTrash } }
        return Page(page, candidates.size - skippedCandidates)
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

    /**
     * Trashes the marked images of the page, keeps the unselected members that are not candidates
     * elsewhere, and leaves candidates in other categories unreviewed so they can be marked on
     * the page where they are listed.
     */
    suspend fun applyPage(page: Page, marked: Set<String> = emptySet()): Decision = applyPage(page.groups, marked)

    suspend fun applyPage(groups: List<ReviewGroup>, marked: Set<String> = emptySet()): Decision {
        val items = groups.flatMap { it.images }
        val toTrash = items.filter { it.image.path in marked }.map { it.image }
        val toKeep = items.filter { it.image.path !in marked && it.keepable }.map { it.image }
        return apply(toTrash, toKeep)
    }

    /** Puts every image of a decision back as it was before: in its folder, and unreviewed. */
    suspend fun undo(decision: Decision): List<ImageRef> {
        val restored = trash.restore(decision.trashed)
        (restored + decision.kept).forEach { index.setReviewState(it.path, ReviewState.UNREVIEWED) }
        return decision.trashed - restored.toSet()
    }

    suspend fun trashed(): List<TrashedImage> = trash.trashed()

    /** How many trashed images are older than [retention] at [now]: what the next scan would delete for good. */
    suspend fun expiring(retention: Duration, now: Instant = Instant.now()): Int = trash.trashed().count { it.trashedAt + retention <= now && it.seenAt + retention <= now }

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

    /**
     * Never suggests the folder of [image] again, subfolders included, and returns the folder. Its
     * images stay in the index with their signals, so removing the exclusion needs no new scan.
     */
    suspend fun excludeFolderOf(image: ImageRef): String = File(image.path).parent!!.also { excludeFolder(it) }

    suspend fun excludeFolder(folder: String) = index.excludeFolder(folder)

    suspend fun removeExcludedFolder(folder: String) = index.removeExcludedFolder(folder)

    /** Images neither in the trash nor in an excluded folder, for storage space exploration. */
    suspend fun notTrashed(): List<ImageRef> {
        val excluded = index.excludedFolders()
        return index.notTrashed().filterNot { isExcluded(it.path, excluded) }
    }

    private suspend fun singles(category: Category, candidates: List<Candidate>, grouping: ReviewOrder): List<ReviewGroup> {
        val groups = candidates.map {
            ReviewGroup(it.image.ref.path, listOf(ReviewItem(it.image.ref, suggestTrash = true, score = score(it, category), reasons = it.reasons)))
        }
        if (!grouping.byDate) return groups
        val capturedAt = index.capturedAt()
        val zone = zone()
        val firstDay = firstDayOfWeek()
        return groups
            .map { it.copy(date = grouping.start(imageDate(it.images.single().image, capturedAt[it.id], zone), firstDay)) }
            .sortedByDescending { it.date } // stable: the ranker's order holds inside a date
    }

    /** Members in an [excluded] folder or stickers are left out: the review never lists, trashes or keeps them here. */
    private fun groups(
        category: Category,
        candidates: List<Candidate>,
        images: List<IndexedImage>,
        excluded: List<String>,
        otherCandidates: Set<String> = emptySet(),
    ): List<ReviewGroup> {
        val members = mutableMapOf<String, MutableList<Pair<IndexedImage, Role>>>()
        val withExcludedCopy = mutableSetOf<String>()
        val withTrashedBestCopy = mutableSetOf<String>()
        val hasNonTrashedBest = mutableSetOf<String>()
        for (image in images) {
            val isExcluded = isExcluded(image.ref.path, excluded)
            for (signal in image.signals) {
                if (signal.kind != category) continue
                val group = signal.evidence[GROUP_KEY] ?: continue
                val role = Role.entries.firstOrNull { it.value == signal.evidence[ROLE_KEY] } ?: continue
                if (image.reviewState == ReviewState.TRASHED) {
                    if (role == Role.BEST) withTrashedBestCopy += group
                    continue
                }
                if (isExcluded) {
                    withExcludedCopy += group
                    if (role == Role.BEST) hasNonTrashedBest += group
                    continue
                }
                if (image.signals.any { it.kind == Category.STICKER }) {
                    continue
                }
                if (role == Role.BEST) {
                    hasNonTrashedBest += group
                }
                members.getOrPut(group) { mutableListOf() } += image to role
            }
        }
        val order = LinkedHashSet<String>()
        val candidateScores = mutableMapOf<String, Float>()
        val candidateReasons = mutableMapOf<String, List<Signal>>()
        for (candidate in candidates) {
            for (signal in candidate.reasons.filter { it.kind == category }) {
                signal.evidence[GROUP_KEY]?.let(order::add)
            }
            candidateScores[candidate.image.ref.path] = score(candidate, category)
            candidateReasons[candidate.image.ref.path] = candidate.reasons
        }
        return order.map { group ->
            val hasBest = group in hasNonTrashedBest
            val items = members[group].orEmpty().map { (image, role) ->
                val best = role == Role.BEST
                ReviewItem(
                    image = image.ref,
                    suggestTrash = hasBest && !best && image.ref.path in candidateScores,
                    isBest = best,
                    score = candidateScores[image.ref.path] ?: 0f,
                    reasons = candidateReasons[image.ref.path] ?: image.signals.filter { it.kind == category },
                    keepable = image.ref.path !in otherCandidates,
                )
            }
            ReviewGroup(
                id = group,
                images = items.sortedByDescending { it.isBest },
                hasExcludedCopy = group in withExcludedCopy,
                hasTrashedBestCopy = !hasBest && group in withTrashedBestCopy,
            )
        }
    }

    private fun score(candidate: Candidate, category: Category) = candidate.reasons.filter { it.kind == category }.maxOf { it.score }
}
