package io.github.bert0ns.cribrum.review

import io.github.bert0ns.cribrum.index.ExcludedFolderRow
import io.github.bert0ns.cribrum.index.memoryIndex
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.model.toImageRef
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import io.github.bert0ns.cribrum.trash.AppTrash
import io.github.bert0ns.cribrum.trash.Trash
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class ReviewTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val index = memoryIndex()
    private val volume by lazy { folder.newFolder("storage") }
    private var thresholds: Map<Category, Float> = THRESHOLDS
    private val review by lazy { Review(index, AppTrash({ listOf(volume) }), thresholds = { thresholds }) }

    @After
    fun close() = index.close()

    private fun image(path: String, vararg signals: Signal): ImageRef = runBlocking {
        val file = File(volume, path).apply {
            parentFile!!.mkdirs()
            writeText(path)
        }
        val ref = file.toImageRef()
        index.record(ref)
        signals.groupBy { it.detectorId }.forEach { (id, s) -> index.setSignals(ref.path, id, 1, s) }
        ref
    }

    private fun blurry(score: Float) = Signal("blur", 1, Category.BLURRY, score)

    private fun duplicate(group: String, role: Role) = Signal(
        "exact_duplicate",
        1,
        Category.EXACT_DUPLICATE,
        if (role == Role.BEST) 0f else 1f,
        mapOf(GROUP_KEY to group, ROLE_KEY to role.value),
    )

    private fun nearDuplicate(group: String, role: Role, score: Float = if (role == Role.BEST) 0f else 0.8f) = Signal(
        "near_duplicate",
        2,
        Category.NEAR_DUPLICATE,
        score,
        mapOf(GROUP_KEY to group, ROLE_KEY to role.value, "distance" to "4"),
    )

    private fun burst(group: String, role: Role) = Signal(
        "burst",
        1,
        Category.BURST,
        if (role == Role.BEST) 0f else 0.8f,
        mapOf(GROUP_KEY to group, ROLE_KEY to role.value),
    )

    private fun test(block: suspend Review.() -> Unit) = runBlocking { review.block() }

    private fun shown(page: Page) = page.groups.flatMap { g -> g.images.map { it.image } }

    @Test
    fun `every category is listed in fixed order with its count and detector flag`() = test {
        image("a.jpg", blurry(0.9f))
        image("b.jpg", blurry(0.8f))
        image("c.jpg", blurry(0.1f))
        image("DCIM/d.jpg", duplicate("g", Role.BEST))
        image("Download/d.jpg", duplicate("g", Role.INFERIOR))

        assertEquals(
            listOf(
                CategorySummary(Category.EXACT_DUPLICATE, 1, hasDetector = true, bytes = 14),
                CategorySummary(Category.NEAR_DUPLICATE, 0, hasDetector = true),
                CategorySummary(Category.BURST, 0, hasDetector = true),
                CategorySummary(Category.BLURRY, 2, hasDetector = true, bytes = 10),
                CategorySummary(Category.BAD_EXPOSURE, 0, hasDetector = true),
                CategorySummary(Category.SCREENSHOT, 0, hasDetector = true),
                CategorySummary(Category.DOCUMENT, 0, hasDetector = true),
                CategorySummary(Category.FORWARDED, 0, hasDetector = true),
                CategorySummary(Category.STICKER, 0, hasDetector = true),
            ),
            overview().categories,
        )
    }

    @Test
    fun `a category counts the bytes of its candidates and leaves out the best copy of a group`() = test {
        image("a.jpg", blurry(0.9f)) // 5 bytes: the file holds its own path
        image("bb.jpg", blurry(0.8f)) // 6
        image("c.jpg", blurry(0.1f)) // below the threshold, not a candidate
        image("DCIM/d.jpg", duplicate("g", Role.BEST))
        image("Download/d.jpg", duplicate("g", Role.INFERIOR)) // 14

        val bytes = overview().categories.associate { it.category to it.bytes }

        assertEquals(11L, bytes[Category.BLURRY])
        assertEquals(14L, bytes[Category.EXACT_DUPLICATE])
        assertEquals(0L, bytes[Category.SCREENSHOT])
    }

    @Test
    fun `changing a threshold changes the lists on the next read, with no new scan`() = test {
        image("a.jpg", blurry(0.9f))
        image("b.jpg", blurry(0.6f))
        image("c.jpg", blurry(0.45f))
        suspend fun blurryCount() = overview().categories.single { it.category == Category.BLURRY }.count

        assertEquals(3, blurryCount())

        thresholds = THRESHOLDS + (Category.BLURRY to 0.7f)
        assertEquals(1, blurryCount())
        assertEquals(1, page(Category.BLURRY).remaining)

        thresholds = THRESHOLDS + (Category.BLURRY to 0.1f)
        assertEquals(3, blurryCount())
    }

    @Test
    fun `a lowered threshold does not list a kept image again`() = test {
        val kept = image("a.jpg", blurry(0.3f))
        index.setReviewState(kept.path, ReviewState.KEPT)

        thresholds = THRESHOLDS + (Category.BLURRY to 0.2f)

        assertEquals(0, overview().categories.single { it.category == Category.BLURRY }.count)
        assertEquals(ReviewState.KEPT, index.reviewState(kept.path))
    }

    @Test
    fun `at the lowest threshold the best copy of a group is still not a candidate`() = test {
        image("DCIM/d.jpg", duplicate("g", Role.BEST))
        image("Download/d.jpg", duplicate("g", Role.INFERIOR))

        thresholds = THRESHOLDS + (Category.EXACT_DUPLICATE to Thresholds.MIN)

        val group = page(Category.EXACT_DUPLICATE).groups.single()
        assertEquals(listOf(false, true), group.images.map { it.suggestTrash })
        assertEquals(1, overview().categories.single { it.category == Category.EXACT_DUPLICATE }.count)
    }

    @Test
    fun `candidate counts at other thresholds leave the ones in use alone`() = test {
        image("a.jpg", blurry(0.9f))
        image("b.jpg", blurry(0.45f))
        image("c.jpg", Signal("document", 1, Category.DOCUMENT, 0.3f))

        val counts = counts(THRESHOLDS + (Category.BLURRY to 0.7f))

        assertEquals(1, counts[Category.BLURRY])
        assertEquals(1, counts[Category.DOCUMENT])
        assertEquals(0, counts[Category.SCREENSHOT])
        assertEquals(2, overview().categories.single { it.category == Category.BLURRY }.count)
    }

    @Test
    fun `a page preselects every candidate, most likely first, and fits a page`() = test {
        repeat(PAGE_SIZE + 5) { image("img$it.jpg", blurry(0.5f + it / 200f)) }

        val page = page(Category.BLURRY).groups

        assertEquals(PAGE_SIZE + 5, page(Category.BLURRY).remaining)
        assertEquals(PAGE_SIZE, page.size)
        assertTrue(page.all { group -> group.images.single().suggestTrash })
        assertEquals(page.map { it.images.single().score }.sortedDescending(), page.map { it.images.single().score })
    }

    @Test
    fun `sorting by size lists the largest first, drops nothing, and breaks a tie by confidence`() = test {
        val small = image("a.jpg", blurry(0.9f)) // 5 bytes
        val large = image("long/path/c.jpg", blurry(0.5f)) // 15
        val tieHigh = image("bb.jpg", blurry(0.8f)) // 6
        val tieLow = image("cc.jpg", blurry(0.6f)) // 6

        val page = page(Category.BLURRY, grouping = ReviewOrder.SIZE)

        assertEquals(listOf(large, tieHigh, tieLow, small), shown(page))
        assertEquals(4, page.remaining)
        assertTrue(page.groups.all { it.date == null && it.images.single().suggestTrash })
    }

    @Test
    fun `sorting by size also orders a category without date order`() = test {
        val small = image("a.jpg", Signal("sticker", 1, Category.STICKER, 1f))
        val large = image("long/c.jpg", Signal("sticker", 1, Category.STICKER, 1f))

        assertEquals(listOf(large, small), shown(page(Category.STICKER, grouping = ReviewOrder.SIZE)))
    }

    @Test
    fun `sorting a group category by size puts the group with the most bytes to trash first`() = test {
        image("DCIM/best1.jpg", duplicate("small", Role.BEST))
        val small = image("DCIM/small.jpg", duplicate("small", Role.INFERIOR))
        image("DCIM/best2.jpg", duplicate("large", Role.BEST))
        val large1 = image("DCIM/large-one.jpg", duplicate("large", Role.INFERIOR))
        val large2 = image("DCIM/large-two.jpg", duplicate("large", Role.INFERIOR))

        val page = page(Category.EXACT_DUPLICATE, grouping = ReviewOrder.SIZE)

        assertEquals(listOf("large", "small"), page.groups.map { it.id })
        assertEquals(listOf(large1, large2), page.groups.first().images.filter { it.suggestTrash }.map { it.image })
        assertEquals(listOf(small), page.groups.last().images.filter { it.suggestTrash }.map { it.image })
        assertEquals(3, page.remaining)
    }

    @Test
    fun `a group is sorted by the bytes of its copies to trash, not by its best copy`() = test {
        image("DCIM/a-very-long-name-for-a-large-best-copy.jpg", duplicate("bigBest", Role.BEST))
        image("Download/a.jpg", duplicate("bigBest", Role.INFERIOR))
        image("DCIM/b.jpg", duplicate("smallBest", Role.BEST))
        image("Download/a-much-longer-name-for-the-copy-to-trash.jpg", duplicate("smallBest", Role.INFERIOR))

        assertEquals(listOf("smallBest", "bigBest"), page(Category.EXACT_DUPLICATE, grouping = ReviewOrder.SIZE).groups.map { it.id })
    }

    @Test
    fun `sorting by size pages through every candidate once, never splits a group and never goes up in size`() = test {
        repeat(40) { group ->
            image("DCIM/g$group-best.jpg", duplicate("g$group", Role.BEST))
            repeat(group % 3 + 1) { copy -> image("Download/g$group-c$copy-${"x".repeat(group % 7)}.jpg", duplicate("g$group", Role.INFERIOR)) }
        }
        repeat(2 * PAGE_SIZE + 7) { image("single-$it-${"y".repeat(it % 11)}.jpg", blurry(0.5f + it / 1000f)) }

        for ((category, total) in listOf(Category.EXACT_DUPLICATE to 79, Category.BLURRY to 2 * PAGE_SIZE + 7)) {
            val seen = mutableListOf<ReviewGroup>()
            val skipped = mutableSetOf<String>()
            while (true) {
                val page = page(category, skipped, ReviewOrder.SIZE)
                if (page.groups.isEmpty()) break
                assertEquals(total - seen.sumOf { group -> group.images.count { it.suggestTrash } }, page.remaining)
                assertTrue(page.groups.sumOf { it.images.size } <= PAGE_SIZE)
                seen += page.groups
                skipped += page.groups.map { it.id }
            }
            val toTrash = seen.flatMap { group -> group.images.filter { it.suggestTrash }.map { it.image.path } }
            assertEquals(total, toTrash.size)
            assertEquals(total, toTrash.toSet().size)
            val bytes = seen.map { group -> group.images.filter { it.suggestTrash }.sumOf { it.image.size } }
            assertEquals(bytes.sortedDescending(), bytes)
        }
    }

    @Test
    fun `the freeable total drops a trashed and a kept image and gets them back on undo`() = test {
        val a = image("a.jpg", blurry(0.9f)) // 5 bytes
        val b = image("bb.jpg", blurry(0.9f)) // 6
        image("ccc.jpg", blurry(0.9f)) // 7
        assertEquals(18L, overview().freeableBytes)

        val decision = apply(listOf(a), listOf(b))

        assertEquals(7L, overview().freeableBytes)
        undo(decision)
        assertEquals(18L, overview().freeableBytes)
    }

    @Test
    fun `the freeable total counts an image once however many categories list it, and not a best copy`() = test {
        image("a.jpg", blurry(0.9f), Signal("shot", 1, Category.SCREENSHOT, 0.9f)) // 5 bytes, two categories
        image("bb.jpg", blurry(0.9f)) // 6
        image("c.jpg", blurry(0.1f)) // below the threshold
        image("DCIM/best.jpg", duplicate("g", Role.BEST))
        image("Download/d.jpg", duplicate("g", Role.INFERIOR)) // 14

        val overview = overview()

        assertEquals(25L, overview.freeableBytes)
        assertEquals(30L, overview.categories.sumOf { it.bytes }) // the per-category figures still overlap
    }

    @Test
    fun `the freeable total leaves out excluded and trashed images`() = test {
        val kept = image("Family/a.jpg", blurry(0.9f))
        image("Other/bb.jpg", blurry(0.9f)) // 12 bytes
        excludeFolderOf(kept)

        assertEquals(12L, overview().freeableBytes)
    }

    @Test
    fun `skipped groups leave the page and the count without changing their review state`() = test {
        val first = image("a.jpg", blurry(0.9f))
        val second = image("b.jpg", blurry(0.8f))

        val page = page(Category.BLURRY, skipped = setOf(first.path))

        assertEquals(listOf(second), page.groups.flatMap { g -> g.images.map { it.image } })
        assertEquals(1, page.remaining)
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(first.path))
        assertEquals(2, page(Category.BLURRY).remaining)
    }

    @Test
    fun `skipping a duplicate group leaves out its best copy too`() = test {
        image("DCIM/Camera/a.jpg", duplicate("g", Role.BEST))
        image("WhatsApp/a.jpg", duplicate("g", Role.INFERIOR))

        val page = page(Category.EXACT_DUPLICATE, skipped = setOf("g"))

        assertEquals(emptyList<ReviewGroup>(), page.groups)
        assertEquals(0, page.remaining)
    }

    @Test
    fun `a duplicate group shows its best copy first, kept, next to the copies to trash`() = test {
        val best = image("DCIM/Camera/a.jpg", duplicate("g", Role.BEST))
        val copy = image("WhatsApp/a.jpg", duplicate("g", Role.INFERIOR))

        val group = page(Category.EXACT_DUPLICATE).groups.single()

        assertEquals(listOf(ReviewItem(best, false, isBest = true, reasons = listOf(duplicate("g", Role.BEST))), ReviewItem(copy, true, score = 1f, reasons = listOf(duplicate("g", Role.INFERIOR)))), group.images)
        assertFalse(group.hasExcludedCopy)
    }

    @Test
    fun `a near-duplicate group shows its best copy first, kept, next to the copies to trash`() = test {
        val best = image("DCIM/Camera/a.jpg", nearDuplicate("g", Role.BEST))
        val copy = image("WhatsApp/a.jpg", nearDuplicate("g", Role.INFERIOR))

        val group = page(Category.NEAR_DUPLICATE).groups.single()

        assertEquals(listOf(ReviewItem(best, false, isBest = true, reasons = listOf(nearDuplicate("g", Role.BEST))), ReviewItem(copy, true, score = 0.8f, reasons = listOf(nearDuplicate("g", Role.INFERIOR)))), group.images)
    }

    @Test
    fun `an item carries every reason it is a candidate for, strongest first`() = test {
        val dark = Signal("exposure", 1, Category.BAD_EXPOSURE, 1f, mapOf("case" to "dark"))
        image("a.jpg", dark, blurry(0.9f))

        val item = page(Category.BLURRY).groups.single().images.single()

        assertEquals(listOf(dark, blurry(0.9f)), item.reasons)
    }

    @Test
    fun `a group member that is not a candidate shows the line of its own group signal`() = test {
        val best = image("DCIM/Camera/a.jpg", nearDuplicate("g", Role.BEST))
        val copy = image("WhatsApp/a.jpg", nearDuplicate("g", Role.INFERIOR))
        val weak = image("Download/a.jpg", nearDuplicate("g", Role.INFERIOR, score = 0.3f))

        val group = page(Category.NEAR_DUPLICATE).groups.single()

        assertEquals(
            ReviewItem(weak, false, score = 0f, reasons = listOf(nearDuplicate("g", Role.INFERIOR, score = 0.3f))),
            group.images.single { it.image == weak },
        )
        assertEquals(listOf(best, copy, weak).toSet(), group.images.map { it.image }.toSet())
    }

    @Test
    fun `excluding the folder of an image hides its images from every category and the counts`() = test {
        val kept = image("Pictures/Family/a.jpg", blurry(0.9f), Signal("shot", 1, Category.SCREENSHOT, 0.9f))
        val sub = image("Pictures/Family/2020/b.jpg", blurry(0.9f))
        val other = image("Pictures/Other/c.jpg", blurry(0.9f))

        excludeFolderOf(kept)

        assertEquals(listOf(other), shown(page(Category.BLURRY)))
        assertEquals(1, page(Category.BLURRY).remaining)
        assertEquals(0, overview().categories.first { it.category == Category.SCREENSHOT }.count)
        assertEquals(1, overview().categories.first { it.category == Category.BLURRY }.count)
        assertEquals(listOf(File(kept.path).parent), index.excludedFolders())
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(sub.path))
    }

    @Test
    fun `removing the exclusion brings the images back without a new scan`() = test {
        val a = image("Pictures/Family/a.jpg", blurry(0.9f))
        excludeFolderOf(a)

        removeExcludedFolder(File(a.path).parent!!)

        assertEquals(listOf(a), shown(page(Category.BLURRY)))
        assertEquals(emptyList<String>(), index.excludedFolders())
    }

    @Test
    fun `a group is still offered for the copies outside an excluded folder, and the excluded copy stays out of it`() = test {
        val best = image("DCIM/Camera/a.jpg", duplicate("g", Role.BEST))
        val copy = image("WhatsApp/a.jpg", duplicate("g", Role.INFERIOR))
        val excludedCopy = image("Family/a.jpg", duplicate("g", Role.INFERIOR))

        excludeFolderOf(excludedCopy)

        val group = page(Category.EXACT_DUPLICATE).groups.single()
        assertEquals(listOf(ReviewItem(best, false, isBest = true, reasons = listOf(duplicate("g", Role.BEST))), ReviewItem(copy, true, score = 1f, reasons = listOf(duplicate("g", Role.INFERIOR)))), group.images)
        assertTrue(group.hasExcludedCopy)
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(excludedCopy.path))
    }

    @Test
    fun `when the best copy is excluded the others are still offered, without it, and it is never touched`() = test {
        val best = image("Family/a.jpg", duplicate("g", Role.BEST))
        val copy = image("WhatsApp/a.jpg", duplicate("g", Role.INFERIOR))

        excludeFolderOf(best)

        val group = page(Category.EXACT_DUPLICATE).groups.single()
        assertEquals(listOf(ReviewItem(copy, true, score = 1f, reasons = listOf(duplicate("g", Role.INFERIOR)))), group.images)
        assertTrue(group.hasExcludedCopy)
    }

    @Test
    fun `a group whose only candidates are excluded is not offered`() = test {
        image("DCIM/Camera/a.jpg", duplicate("g", Role.BEST))
        val excludedCopy = image("Family/a.jpg", duplicate("g", Role.INFERIOR))

        excludeFolderOf(excludedCopy)

        assertEquals(emptyList<ReviewGroup>(), page(Category.EXACT_DUPLICATE).groups)
        assertEquals(0, page(Category.EXACT_DUPLICATE).remaining)
    }

    @Test
    fun `a group with a trashed best copy and two copies left has no suggestTrash member`() = test {
        val best = image("DCIM/Camera/a.jpg", duplicate("g", Role.BEST))
        val copy = image("WhatsApp/a.jpg", duplicate("g", Role.INFERIOR))
        val other = image("Download/a.jpg", duplicate("g", Role.INFERIOR))

        apply(listOf(best), emptyList())

        val group = page(Category.EXACT_DUPLICATE).groups.single()
        assertTrue(group.hasTrashedBestCopy)
        assertEquals(
            listOf(
                ReviewItem(other, suggestTrash = false, score = 1f, reasons = listOf(duplicate("g", Role.INFERIOR))),
                ReviewItem(copy, suggestTrash = false, score = 1f, reasons = listOf(duplicate("g", Role.INFERIOR))),
            ),
            group.images.sortedBy { it.image.path },
        )
        assertEquals(2, page(Category.EXACT_DUPLICATE).remaining)
        assertEquals(2, counts().getValue(Category.EXACT_DUPLICATE))
    }

    @Test
    fun `a group with a trashed best copy and one copy left is not offered, and no figure counts it`() = test {
        val best = image("DCIM/Camera/a.jpg", duplicate("g", Role.BEST))
        val copy = image("WhatsApp/a.jpg", duplicate("g", Role.INFERIOR))

        apply(listOf(best), emptyList())

        assertLoneCopyHidden(Category.EXACT_DUPLICATE)
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(copy.path))
    }

    @Test
    fun `a near-duplicate and a burst group with a trashed best copy and one copy left are not offered either`() = test {
        val nearBest = image("DCIM/Camera/n1.jpg", nearDuplicate("n", Role.BEST))
        image("WhatsApp/n2.jpg", nearDuplicate("n", Role.INFERIOR))
        val burstBest = image("DCIM/Camera/b1.jpg", burst("b", Role.BEST))
        image("DCIM/Camera/b2.jpg", burst("b", Role.INFERIOR))

        apply(listOf(nearBest, burstBest), emptyList())

        assertLoneCopyHidden(Category.NEAR_DUPLICATE)
        assertLoneCopyHidden(Category.BURST)
    }

    @Test
    fun `a lone copy comes back as a normal group when its best copy is restored`() = test {
        val best = image("DCIM/Camera/a.jpg", duplicate("g", Role.BEST))
        val copy = image("WhatsApp/a.jpg", duplicate("g", Role.INFERIOR))
        apply(listOf(best), emptyList())

        restore(listOf(best))

        val group = page(Category.EXACT_DUPLICATE).groups.single()
        assertFalse(group.hasTrashedBestCopy)
        assertEquals(listOf(best, copy), group.images.map { it.image })
        assertEquals(listOf(false, true), group.images.map { it.suggestTrash })
        assertEquals(1, counts().getValue(Category.EXACT_DUPLICATE))
    }

    @Test
    fun `a lone copy next to a copy in an excluded folder is not offered when the best copy is in the trash`() = test {
        val best = image("DCIM/Camera/a.jpg", duplicate("g", Role.BEST))
        val copy = image("WhatsApp/a.jpg", duplicate("g", Role.INFERIOR))
        val excludedCopy = image("Family/a.jpg", duplicate("g", Role.INFERIOR))
        apply(listOf(best), emptyList())

        excludeFolderOf(excludedCopy)

        assertLoneCopyHidden(Category.EXACT_DUPLICATE)
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(copy.path))
    }

    @Test
    fun `a lone copy whose best copy is gone from the index is not offered`() = test {
        val best = image("DCIM/Camera/a.jpg", duplicate("g", Role.BEST))
        image("WhatsApp/a.jpg", duplicate("g", Role.INFERIOR))
        apply(listOf(best), emptyList())

        purge(trashed().map { it.original })

        assertLoneCopyHidden(Category.EXACT_DUPLICATE)
    }

    @Test
    fun `a lone copy that is a candidate in another category stays there, and only there`() = test {
        val best = image("DCIM/Camera/a.jpg", duplicate("g", Role.BEST))
        image("WhatsApp/a.jpg", duplicate("g", Role.INFERIOR), blurry(0.9f)) // 5 + 5 + 1 bytes: the file holds its path
        apply(listOf(best), emptyList())

        assertEquals(emptyList<ReviewGroup>(), page(Category.EXACT_DUPLICATE).groups)
        assertEquals(1, page(Category.BLURRY).groups.size)
        assertEquals(1, counts().getValue(Category.BLURRY))
        assertEquals(0, counts().getValue(Category.EXACT_DUPLICATE))
        assertEquals(File(volume, "WhatsApp/a.jpg").length(), overview().freeableBytes)
    }

    @Test
    fun `hidden groups do not keep the other groups off the page, and skip still works`() = test {
        repeat(PAGE_SIZE) { i ->
            val best = image("DCIM/Camera/h$i.jpg", duplicate("h$i", Role.BEST))
            image("WhatsApp/h$i.jpg", duplicate("h$i", Role.INFERIOR))
            apply(listOf(best), emptyList())
        }
        val visibleBest = image("DCIM/Camera/v.jpg", duplicate("v", Role.BEST))
        val visibleCopy = image("WhatsApp/v.jpg", duplicate("v", Role.INFERIOR))

        val first = page(Category.EXACT_DUPLICATE)

        assertEquals(listOf(visibleBest, visibleCopy), shown(first))
        assertEquals(1, first.remaining)
        assertEquals(Page(emptyList(), 0), page(Category.EXACT_DUPLICATE, skipped = setOf("v")))
        assertEquals(1, overview().categories.first { it.category == Category.EXACT_DUPLICATE }.count)
    }

    private suspend fun Review.assertLoneCopyHidden(category: Category) {
        assertEquals(Page(emptyList(), 0), page(category))
        assertEquals(0, counts().getValue(category))
        val summary = overview().categories.first { it.category == category }
        assertEquals(0, summary.count)
        assertEquals(0L, summary.bytes)
        assertEquals(0L, overview().freeableBytes)
    }

    @Test
    fun `a near-duplicate group with a sticker member and one with a blurry candidate are not marked kept by applying the page`() = test {
        val best = image("DCIM/Camera/best.jpg", nearDuplicate("g", Role.BEST))
        val sticker = image("WhatsApp/sticker.jpg", nearDuplicate("g", Role.INFERIOR), Signal("sticker", 1, Category.STICKER, 1f))
        val blurry = image("WhatsApp/blurry.jpg", nearDuplicate("g", Role.INFERIOR), blurry(0.9f))
        val plain = image("WhatsApp/plain.jpg", nearDuplicate("g", Role.INFERIOR))

        val p = page(Category.NEAR_DUPLICATE)
        val group = p.groups.single()
        assertEquals(listOf(best, blurry, plain), group.images.map { it.image })

        applyPage(p)

        assertEquals(ReviewState.KEPT, index.reviewState(best.path))
        assertEquals(ReviewState.KEPT, index.reviewState(plain.path))
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(blurry.path))
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(sticker.path))
    }

    @Test
    fun `group members that are not stickers stay on the page, though every scanned image has a sticker score`() = test {
        val notSticker = Signal("sticker", 1, Category.STICKER, 0f)
        val best = image("DCIM/Camera/best.jpg", nearDuplicate("g", Role.BEST), notSticker)
        val copy = image("DCIM/Camera/copy.jpg", nearDuplicate("g", Role.INFERIOR), notSticker)

        assertEquals(listOf(best, copy), page(Category.NEAR_DUPLICATE).groups.single().images.map { it.image })
    }

    @Test
    fun `applying trashes and keeps, and the page moves on`() = test {
        val bad = image("bad.jpg", blurry(0.9f))
        val good = image("good.jpg", blurry(0.8f))

        val decision = apply(listOf(bad), listOf(good))

        assertEquals(Decision(listOf(bad), listOf(good), emptyList(), mapOf(good.path to PreviousReview(ReviewState.UNREVIEWED, emptySet(), emptySet()))), decision)
        assertFalse(File(bad.path).exists())
        assertEquals(ReviewState.TRASHED, index.reviewState(bad.path))
        assertEquals(ReviewState.KEPT, index.reviewState(good.path))
        assertEquals(emptyList<ReviewGroup>(), page(Category.BLURRY).groups)
    }

    @Test
    fun `a file changed since its scan is not trashed and stays in the review`() = test {
        val changed = image("a.jpg", blurry(0.9f))
        File(changed.path).appendText(" edited")

        val decision = apply(listOf(changed), emptyList())

        assertEquals(listOf(changed), decision.failed)
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(changed.path))
        assertEquals(1, page(Category.BLURRY).groups.size)
    }

    @Test
    fun `undo puts every image back as it was`() = test {
        val bad = image("bad.jpg", blurry(0.9f))
        val good = image("good.jpg", blurry(0.8f))
        val decision = apply(listOf(bad), listOf(good))

        assertEquals(emptyList<ImageRef>(), undo(decision))

        assertEquals(bad, File(bad.path).toImageRef())
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(bad.path))
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(good.path))
        assertEquals(2, page(Category.BLURRY).groups.size)
    }

    @Test
    fun `restoring from the trash keeps the image`() = test {
        val image = image("a.jpg", blurry(0.9f))
        apply(listOf(image), emptyList())

        assertEquals(listOf(image), restore(listOf(image)))

        assertTrue(File(image.path).exists())
        assertEquals(ReviewState.KEPT, index.reviewState(image.path))
        assertEquals(emptyList<ReviewGroup>(), page(Category.BLURRY).groups)
        assertEquals(emptyList<Any>(), trashed())
    }

    @Test
    fun `undo into an empty index puts the trashed file back with a row, and adds none for a kept one`() = test {
        val bad = image("bad.jpg", blurry(0.9f))
        val good = image("good.jpg", blurry(0.8f))
        val decision = apply(listOf(bad), listOf(good))
        index.forget(listOf(bad.path, good.path)) // a reinstall: the trash survives, the index does not

        assertEquals(emptyList<ImageRef>(), undo(decision))

        assertTrue(File(bad.path).exists())
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(bad.path))
        assertEquals(listOf(bad), index.images()) // the kept image gets no row: its file never left, and a scan will find it
    }

    @Test
    fun `restoring into an empty index brings the file back and creates its row unreviewed`() = test {
        val image = image("a.jpg", blurry(0.9f))
        apply(listOf(image), emptyList())
        index.forget(listOf(image.path))

        assertEquals(listOf(image), restore(listOf(image)))

        assertTrue(File(image.path).exists())
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(image.path))
        assertEquals(emptyList<Any>(), trashed())
    }

    @Test
    fun `restoring an image whose row has changed since is unreviewed, not trashed`() = test {
        val image = image("a.jpg", blurry(0.9f))
        apply(listOf(image), emptyList())
        index.record(image.copy(size = image.size + 1)) // the row no longer matches the file in the trash

        assertEquals(listOf(image), restore(listOf(image)))

        assertEquals(ReviewState.UNREVIEWED, index.reviewState(image.path))
    }

    @Test
    fun `undo leaves in the trash an image whose path is taken, and says so`() = test {
        val bad = image("bad.jpg", blurry(0.9f))
        val good = image("good.jpg", blurry(0.8f))
        val decision = apply(listOf(bad), listOf(good))
        File(bad.path).writeText("another file")

        assertEquals(listOf(bad), undo(decision))

        assertEquals("another file", File(bad.path).readText())
        assertEquals(ReviewState.TRASHED, index.reviewState(bad.path))
        assertEquals(listOf(bad), trashed().map { it.original })
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(good.path))
    }

    @Test
    fun `restoring an image whose path is taken leaves it in the trash and trashed in the index`() = test {
        val image = image("a.jpg", blurry(0.9f))
        apply(listOf(image), emptyList())
        File(image.path).writeText("another file")

        assertEquals(emptyList<ImageRef>(), restore(listOf(image)))

        assertEquals(ReviewState.TRASHED, index.reviewState(image.path))
        assertEquals(listOf(image), trashed().map { it.original })
    }

    @Test
    fun `undo keeps a member kept in an earlier session kept, with the signals it was kept with`() = test {
        val best = image("DCIM/best.jpg", nearDuplicate("g", Role.BEST))
        val copy = image("DCIM/copy.jpg", nearDuplicate("g", Role.INFERIOR))
        apply(emptyList(), listOf(best)) // kept in an earlier session
        val kept = index.indexedImages().single { it.ref.path == best.path }.seenWhenKept
        val detectors = index.keptDetectors(best.path)
        assertTrue(kept.isNotEmpty() && detectors.isNotEmpty())

        val p = page(Category.NEAR_DUPLICATE)
        val decision = applyPage(p, setOf(copy.path))
        assertEquals(ReviewState.KEPT, index.reviewState(best.path))
        assertEquals(emptyList<ImageRef>(), undo(decision))

        assertEquals(ReviewState.KEPT, index.reviewState(best.path))
        assertEquals(kept, index.indexedImages().single { it.ref.path == best.path }.seenWhenKept)
        assertEquals(detectors, index.keptDetectors(best.path))
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(copy.path))
        assertTrue(File(copy.path).exists())
    }

    @Test
    fun `a kept image stays out for the first result of a detector that had not run when it was kept`() = test {
        val image = image("a.jpg", blurry(0.9f))
        apply(emptyList(), listOf(image))

        index.setSignals(image.path, "document", 1, listOf(Signal("document", 1, Category.DOCUMENT, 0.9f)))

        assertEquals(emptyList<ReviewGroup>(), page(Category.DOCUMENT).groups)
    }

    @Test
    fun `a kept image comes back when a group forms after that detector's first result`() = test {
        val image = image("DCIM/a.jpg", blurry(0.9f))
        apply(emptyList(), listOf(image))
        index.setSignals(image.path, "near_duplicate", 2, listOf(nearDuplicate("g", Role.BEST)))
        assertEquals(emptyList<ReviewGroup>(), page(Category.NEAR_DUPLICATE).groups)

        val best = image("DCIM/best.jpg", nearDuplicate("g", Role.BEST))
        index.setSignals(image.path, "near_duplicate", 2, listOf(nearDuplicate("g", Role.INFERIOR)))

        assertEquals(listOf(best, image), shown(page(Category.NEAR_DUPLICATE)))
    }

    @Test
    fun `undo of a keep made for a new signal brings the image back for it, and not for the old one`() = test {
        val exposure = Signal("exposure", 1, Category.BAD_EXPOSURE, 0.9f)
        val image = image("a.jpg", blurry(0.9f))
        index.setSignals(image.path, "exposure", 1, emptyList()) // ran, found nothing
        apply(emptyList(), listOf(image)) // kept for BLURRY
        index.setSignals(image.path, "exposure", 1, listOf(exposure)) // a new signal makes it a candidate again
        assertEquals(listOf(image), shown(page(Category.BAD_EXPOSURE)))

        val decision = applyPage(page(Category.BAD_EXPOSURE))
        assertEquals(emptyList<ReviewGroup>(), page(Category.BAD_EXPOSURE).groups)
        undo(decision)

        assertEquals(listOf(image), shown(page(Category.BAD_EXPOSURE)))
        assertEquals(ReviewState.KEPT, index.reviewState(image.path))
        assertEquals(emptyList<ReviewGroup>(), page(Category.BLURRY).groups)
    }

    @Test
    fun `undo of a keep does not reset a row a scan recorded again with a new size`() = test {
        val image = image("a.jpg", blurry(0.9f))
        val decision = apply(emptyList(), listOf(image))
        val changed = image.copy(size = image.size + 5)
        index.record(changed) // a scan found the file changed
        index.setSignals(image.path, "blur", 1, listOf(blurry(0.9f)))

        undo(decision)

        assertEquals(listOf(changed), index.images())
        assertEquals(1, index.signals(image.path).size)
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(image.path))
    }

    @Test
    fun `undo of a keep does not bring back a row a scan forgot`() = test {
        val image = image("a.jpg", blurry(0.9f))
        val decision = apply(emptyList(), listOf(image))
        index.forget(listOf(image.path)) // the file is gone

        assertEquals(emptyList<ImageRef>(), undo(decision))

        assertEquals(emptyList<ImageRef>(), index.images())
    }

    @Test
    fun `an apply that cannot read a row fails before it moves any file`() = test {
        val bad = image("bad.jpg", blurry(0.9f))
        val ghost = File(volume, "ghost.jpg").apply { writeText("x") }.toImageRef() // never recorded

        runCatching { apply(listOf(bad), listOf(ghost)) }.also { assertTrue(it.isFailure) }

        assertTrue(File(bad.path).exists())
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(bad.path))
    }

    @Test
    fun `undo makes unreviewed again a member the page kept for the first time`() = test {
        val best = image("DCIM/best.jpg", nearDuplicate("g", Role.BEST))
        val copy = image("DCIM/copy.jpg", nearDuplicate("g", Role.INFERIOR))

        val decision = applyPage(page(Category.NEAR_DUPLICATE), setOf(copy.path))
        assertEquals(ReviewState.KEPT, index.reviewState(best.path))
        undo(decision)

        assertEquals(ReviewState.UNREVIEWED, index.reviewState(best.path))
        assertEquals(emptySet<Any>(), index.indexedImages().single { it.ref.path == best.path }.seenWhenKept)
    }

    @Test
    fun `a shorter retention counts the trashed images it would purge`() = test {
        val old = image("old.jpg", blurry(0.9f))
        val recent = image("recent.jpg", blurry(0.8f))
        apply(listOf(old, recent), emptyList())

        val inTenDays = Instant.now() + Duration.ofDays(10)

        assertEquals(2, expiring(Duration.ofDays(7), inTenDays))
        assertEquals(0, expiring(Duration.ofDays(14), inTenDays))
        assertEquals(0, expiring(Duration.ofDays(7)))
    }

    @Test
    fun `expiring only counts images whose trashed_at and seen_at both passed retention`() = test {
        val now = Instant.now()
        val trashRoot = File(volume, ".cribrum-trash").apply { mkdirs() }
        val folder = File(trashRoot, "custom-item").apply { mkdirs() }
        val imgFile = File(folder, "custom.jpg").apply { writeText("data") }
        val props = Properties().apply {
            setProperty("path", "${volume.path}/DCIM/custom.jpg")
            setProperty("size", imgFile.length().toString())
            setProperty("mtime_ns", "1700000000000000000")
            // trashed 30 days ago, but only seen 5 days ago
            setProperty("trashed_at", (now - Duration.ofDays(30)).toString())
            setProperty("seen_at", (now - Duration.ofDays(5)).toString())
        }
        File(folder, "origin.properties").outputStream().use { props.store(it, null) }

        // Retention 14 days: trashed_at + 14d is in the past, but seen_at + 14d is in the future
        assertEquals(0, expiring(Duration.ofDays(14), now))

        // Retention 4 days: both trashed_at + 4d and seen_at + 4d are in the past
        assertEquals(1, expiring(Duration.ofDays(4), now))
    }

    @Test
    fun `purging deletes from the trash and forgets the image`() = test {
        val image = image("a.jpg", blurry(0.9f))
        apply(listOf(image), emptyList())

        purge(listOf(image))

        assertEquals(emptyList<Any>(), trashed())
        assertEquals(emptyList<ImageRef>(), index.images())
    }

    @Test
    fun `purging keeps the row of an image the trash could not delete`() = runBlocking {
        val stuck = image("a.jpg", blurry(0.9f))
        val deleted = image("b.jpg", blurry(0.8f))
        val real = AppTrash({ listOf(volume) })
        val review = Review(
            index,
            object : Trash by real {
                override suspend fun purge(images: List<ImageRef>) = real.purge(images - stuck)
            },
        )
        review.apply(listOf(stuck, deleted), emptyList())

        review.purge(listOf(stuck, deleted))

        assertEquals(listOf(stuck), review.trashed().map { it.original })
        assertEquals(ReviewState.TRASHED, index.reviewState(stuck.path))
        assertEquals(listOf(stuck), index.images())
    }

    /** Moves files for real, then stops at [gate] before it returns: the point where a scope that ends is felt. */
    private inner class GatedTrash(private val real: Trash, private val started: CompletableDeferred<Unit>, private val gate: CompletableDeferred<Unit>) : Trash by real {
        override suspend fun moveToTrash(images: List<ImageRef>) = real.moveToTrash(images).also { pause() }

        override suspend fun restore(images: List<ImageRef>) = real.restore(images).also { pause() }

        private suspend fun pause() {
            started.complete(Unit)
            gate.await()
        }
    }

    /** Runs [action] on a review whose scope is cancelled once the files have moved, and lets it finish. */
    private fun cancelledAfterMoving(action: suspend Review.() -> Unit) = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val review = Review(index, GatedTrash(AppTrash({ listOf(volume) }), started, gate))
        val job = launch { review.action() }
        started.await()
        job.cancel()
        gate.complete(Unit)
        job.join()
    }

    @Test
    fun `purging does not forget the row of a new file now at the path`() = test {
        val old = image("a.jpg", blurry(0.9f))
        apply(listOf(old), emptyList())
        val written = File(volume, "a.jpg").apply { writeText("a different picture") }.toImageRef()
        index.record(written) // a new file at the trashed path: its row starts unreviewed
        index.setReviewState(written.path, ReviewState.KEPT)

        purge(listOf(old))

        assertEquals(emptyList<Any>(), trashed())
        assertEquals(listOf(written), index.images())
        assertEquals(ReviewState.KEPT, index.reviewState(written.path))
    }

    @Test
    fun `a scope that ends after the files moved still marks them trashed and kept`() {
        val trashed = image("a.jpg", blurry(0.9f))
        val kept = image("b.jpg", blurry(0.8f))

        cancelledAfterMoving { apply(listOf(trashed), listOf(kept)) }

        assertEquals(ReviewState.TRASHED, runBlocking { index.reviewState(trashed.path) })
        assertEquals(ReviewState.KEPT, runBlocking { index.reviewState(kept.path) })
    }

    @Test
    fun `a scope that ends after a restore still keeps the image`() {
        val image = image("a.jpg", blurry(0.9f))
        runBlocking { review.apply(listOf(image), emptyList()) }

        cancelledAfterMoving { restore(listOf(image)) }

        assertEquals(ReviewState.KEPT, runBlocking { index.reviewState(image.path) })
    }

    @Test
    fun `a scope that ends after an undo still makes the image unreviewed`() {
        val image = image("a.jpg", blurry(0.9f))
        val decision = runBlocking { review.apply(listOf(image), emptyList()) }

        cancelledAfterMoving { undo(decision) }

        assertEquals(ReviewState.UNREVIEWED, runBlocking { index.reviewState(image.path) })
    }

    @Test
    fun `the ranking runs off the calling thread and gives the same results`() = test {
        image("a.jpg", blurry(0.9f))
        image("DCIM/d.jpg", duplicate("g", Role.BEST))
        image("DCIM/e.jpg", duplicate("g", Role.INFERIOR))
        val caller = Thread.currentThread()
        val readOn = mutableSetOf<Thread>()
        val spying = Review(index, AppTrash({ listOf(volume) }), thresholds = {
            readOn += Thread.currentThread()
            THRESHOLDS
        })

        val page = spying.page(Category.BLURRY)
        val overview = spying.overview()

        assertEquals(page, review.page(Category.BLURRY))
        assertEquals(overview, review.overview())
        assertEquals(review.counts(), spying.counts(THRESHOLDS))
        assertTrue(readOn.isNotEmpty() && caller !in readOn)
    }

    /** Writes behind the index's back, so its version stays: a review that reads the index again sees it, a cached one does not. */
    private fun blurryCount(overview: Overview) = overview.categories.single { it.category == Category.BLURRY }.count

    private suspend fun trashUnseen(image: ImageRef) = index.database.dao().setReviewState(image.path, ReviewState.TRASHED.value)

    @Test
    fun `a cached review does not see a change that bypasses the index version, until a write`() = test {
        val a = image("a.jpg", blurry(0.9f))
        val before = overview()

        trashUnseen(a)

        assertEquals(before, overview())
        index.excludeFolder("/elsewhere")
        assertEquals(0, overview().categories.single { it.category == Category.BLURRY }.count)
    }

    @Test
    fun `opening a category after the home reuses the ranking the home computed`() = test {
        val a = image("a.jpg", blurry(0.9f))
        overview()

        trashUnseen(a)

        assertEquals(listOf(a), shown(page(Category.BLURRY)))
    }

    @Test
    fun `a threshold change ranks again from the cached images and excluded folders without reading the index`() = test {
        val a = image("a.jpg", blurry(0.9f))
        val b = image("b/b.jpg", blurry(0.5f))
        overview()
        thresholds = THRESHOLDS + (Category.BLURRY to 0.4f)
        val fresh = Review(index, AppTrash({ listOf(volume) }), thresholds = { thresholds })
        val expected = fresh.counts()

        trashUnseen(a)
        index.database.dao().insertExcludedFolder(ExcludedFolderRow(File(b.path).parent!!))

        assertEquals(expected, counts())
        assertEquals(2, expected.getValue(Category.BLURRY))
    }

    @Test
    fun `a ranking the system took back is read again from the index`() = test {
        val a = image("a.jpg", blurry(0.9f))
        overview()
        trashUnseen(a)

        dropRanking()

        assertEquals(0, blurryCount(overview()))
    }

    @Test
    fun `a write during the read leaves the ranking for the next read to replace`() = test {
        val a = image("a.jpg", blurry(0.9f))
        readImages = { index.indexedImages().also { index.setReviewState(a.path, ReviewState.TRASHED) } }
        assertEquals(1, blurryCount(overview()))

        readImages = index::indexedImages

        assertEquals(0, blurryCount(overview()))
    }

    @Test
    fun `a read that a write overtakes does not replace the ranking stored after the write`() = test {
        val a = image("a.jpg", blurry(0.9f))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = AtomicBoolean(true)
        readImages = {
            if (first.getAndSet(false)) {
                entered.complete(Unit)
                release.await()
            }
            index.indexedImages()
        }
        coroutineScope {
            val slow = async { overview() }
            entered.await()
            index.excludeFolder("/elsewhere")
            overview()
            release.complete(Unit)
            slow.await()
        }

        trashUnseen(a)

        assertEquals(1, blurryCount(overview()))
    }

    @Test
    fun `changing the thresholds map after a call does not change what the cache holds for it`() = test {
        image("a.jpg", blurry(0.9f))
        image("b.jpg", blurry(0.5f))
        val mutable = (THRESHOLDS + (Category.BLURRY to 0.4f)).toMutableMap()
        assertEquals(2, counts(mutable).getValue(Category.BLURRY))

        mutable[Category.BLURRY] = 0.95f

        assertEquals(0, counts(mutable).getValue(Category.BLURRY))
    }

    @Test
    fun `a cached review gives what a fresh one gives after every kind of write`() = test {
        val a = image("a.jpg", blurry(0.9f))
        val b = image("DCIM/b.jpg", duplicate("g", Role.BEST))
        val c = image("DCIM/c.jpg", duplicate("g", Role.INFERIOR))
        val d = image("other/d.jpg", nearDuplicate("n", Role.BEST))
        val e = image("other/e.jpg", nearDuplicate("n", Role.INFERIOR), blurry(0.3f))
        val writes: List<suspend () -> Unit> = listOf(
            { image("f.jpg", blurry(0.95f)) },
            { index.setSignals(a.path, "blur", 1, listOf(blurry(0.2f))) },
            { apply(listOf(c), listOf(e)) },
            { undo(apply(listOf(a), listOf(b))) },
            { restore(listOf(c)) },
            { thresholds = THRESHOLDS + (Category.BLURRY to 0.25f) },
            { excludeFolderOf(d) },
            { removeExcludedFolder(File(d.path).parent!!) },
            { index.setReviewState(e.path, ReviewState.UNREVIEWED) },
            { index.forgetAnalyses(listOf(e.path), listOf("near_duplicate")) },
            { index.record(File(a.path).apply { appendText(" edited") }.toImageRef()) },
            { index.forget(listOf(b.path)) },
            { thresholds = THRESHOLDS },
        )
        for (write in writes) {
            write()
            val fresh = Review(index, AppTrash({ listOf(volume) }), thresholds = { thresholds })
            assertEquals(fresh.overview(), overview())
            assertEquals(fresh.counts(THRESHOLDS), counts(THRESHOLDS))
            for (category in Category.entries) {
                assertEquals(fresh.page(category), page(category))
            }
            assertEquals(fresh.counts(), counts())
        }
    }
}
