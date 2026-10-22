package io.github.bert0ns.cribrum.review

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

    private fun test(block: suspend Review.() -> Unit) = runBlocking { review.block() }

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
            categories(),
        )
    }

    @Test
    fun `a category counts the bytes of its candidates and leaves out the best copy of a group`() = test {
        image("a.jpg", blurry(0.9f)) // 5 bytes: the file holds its own path
        image("bb.jpg", blurry(0.8f)) // 6
        image("c.jpg", blurry(0.1f)) // below the threshold, not a candidate
        image("DCIM/d.jpg", duplicate("g", Role.BEST))
        image("Download/d.jpg", duplicate("g", Role.INFERIOR)) // 14

        val bytes = categories().associate { it.category to it.bytes }

        assertEquals(11L, bytes[Category.BLURRY])
        assertEquals(14L, bytes[Category.EXACT_DUPLICATE])
        assertEquals(0L, bytes[Category.SCREENSHOT])
    }

    @Test
    fun `changing a threshold changes the lists on the next read, with no new scan`() = test {
        image("a.jpg", blurry(0.9f))
        image("b.jpg", blurry(0.6f))
        image("c.jpg", blurry(0.45f))
        suspend fun blurryCount() = categories().single { it.category == Category.BLURRY }.count

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

        assertEquals(0, categories().single { it.category == Category.BLURRY }.count)
        assertEquals(ReviewState.KEPT, index.reviewState(kept.path))
    }

    @Test
    fun `at the lowest threshold the best copy of a group is still not a candidate`() = test {
        image("DCIM/d.jpg", duplicate("g", Role.BEST))
        image("Download/d.jpg", duplicate("g", Role.INFERIOR))

        thresholds = THRESHOLDS + (Category.EXACT_DUPLICATE to Thresholds.MIN)

        val group = page(Category.EXACT_DUPLICATE).groups.single()
        assertEquals(listOf(false, true), group.images.map { it.suggestTrash })
        assertEquals(1, categories().single { it.category == Category.EXACT_DUPLICATE }.count)
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
        assertEquals(2, categories().single { it.category == Category.BLURRY }.count)
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

    private fun shown(page: Page) = page.groups.flatMap { g -> g.images.map { it.image } }

    @Test
    fun `excluding the folder of an image hides its images from every category and the counts`() = test {
        val kept = image("Pictures/Family/a.jpg", blurry(0.9f), Signal("shot", 1, Category.SCREENSHOT, 0.9f))
        val sub = image("Pictures/Family/2020/b.jpg", blurry(0.9f))
        val other = image("Pictures/Other/c.jpg", blurry(0.9f))

        excludeFolderOf(kept)

        assertEquals(listOf(other), shown(page(Category.BLURRY)))
        assertEquals(1, page(Category.BLURRY).remaining)
        assertEquals(0, categories().first { it.category == Category.SCREENSHOT }.count)
        assertEquals(1, categories().first { it.category == Category.BLURRY }.count)
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
    fun `applying trashes and keeps, and the page moves on`() = test {
        val bad = image("bad.jpg", blurry(0.9f))
        val good = image("good.jpg", blurry(0.8f))

        val decision = apply(listOf(bad), listOf(good))

        assertEquals(Decision(listOf(bad), listOf(good), emptyList()), decision)
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
    fun `purging deletes from the trash and forgets the image`() = test {
        val image = image("a.jpg", blurry(0.9f))
        apply(listOf(image), emptyList())

        purge(listOf(image))

        assertEquals(emptyList<Any>(), trashed())
        assertEquals(emptyList<ImageRef>(), index.images())
    }
}
