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

@RunWith(RobolectricTestRunner::class)
class ReviewTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val index = memoryIndex()
    private val volume by lazy { folder.newFolder("storage") }
    private val review by lazy { Review(index, AppTrash({ listOf(volume) })) }

    @After
    fun close() = index.close()

    private fun image(path: String, vararg signals: Signal): ImageRef = runBlocking {
        val file = File(volume, path).apply { parentFile!!.mkdirs(); writeText(path) }
        val ref = file.toImageRef()
        index.record(ref)
        signals.groupBy { it.detectorId }.forEach { (id, s) -> index.setSignals(ref.path, id, 1, s) }
        ref
    }

    private fun blurry(score: Float) = Signal("blur", 1, Category.BLURRY, score)

    private fun duplicate(group: String, role: Role) = Signal(
        "exact_duplicate", 1, Category.EXACT_DUPLICATE, if (role == Role.BEST) 0f else 1f,
        mapOf(GROUP_KEY to group, ROLE_KEY to role.value),
    )

    private fun test(block: suspend Review.() -> Unit) = runBlocking { review.block() }

    @Test
    fun `categories with candidates, the largest first`() = test {
        image("a.jpg", blurry(0.9f))
        image("b.jpg", blurry(0.8f))
        image("c.jpg", blurry(0.1f))
        image("DCIM/d.jpg", duplicate("g", Role.BEST))
        image("Download/d.jpg", duplicate("g", Role.INFERIOR))

        assertEquals(
            listOf(CategorySummary(Category.BLURRY, 2), CategorySummary(Category.EXACT_DUPLICATE, 1)),
            categories(),
        )
    }

    @Test
    fun `a page preselects every candidate, most likely first, and fits a page`() = test {
        repeat(PAGE_SIZE + 5) { image("img$it.jpg", blurry(0.5f + it / 200f)) }

        val page = page(Category.BLURRY)

        assertEquals(PAGE_SIZE, page.size)
        assertTrue(page.all { group -> group.images.single().suggestTrash })
        assertEquals(page.map { it.images.single().score }.sortedDescending(), page.map { it.images.single().score })
    }

    @Test
    fun `a duplicate group shows its best copy first, kept, next to the copies to trash`() = test {
        val best = image("DCIM/Camera/a.jpg", duplicate("g", Role.BEST))
        val copy = image("WhatsApp/a.jpg", duplicate("g", Role.INFERIOR))

        val group = page(Category.EXACT_DUPLICATE).single()

        assertEquals(listOf(ReviewItem(best, false, isBest = true), ReviewItem(copy, true, score = 1f)), group.images)
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
        assertEquals(emptyList<ReviewGroup>(), page(Category.BLURRY))
    }

    @Test
    fun `a file changed since its scan is not trashed and stays in the review`() = test {
        val changed = image("a.jpg", blurry(0.9f))
        File(changed.path).appendText(" edited")

        val decision = apply(listOf(changed), emptyList())

        assertEquals(listOf(changed), decision.failed)
        assertEquals(ReviewState.UNREVIEWED, index.reviewState(changed.path))
        assertEquals(1, page(Category.BLURRY).size)
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
        assertEquals(2, page(Category.BLURRY).size)
    }

    @Test
    fun `restoring from the trash keeps the image`() = test {
        val image = image("a.jpg", blurry(0.9f))
        apply(listOf(image), emptyList())

        assertEquals(listOf(image), restore(listOf(image)))

        assertTrue(File(image.path).exists())
        assertEquals(ReviewState.KEPT, index.reviewState(image.path))
        assertEquals(emptyList<ReviewGroup>(), page(Category.BLURRY))
        assertEquals(emptyList<Any>(), trashed())
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
