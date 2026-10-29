package io.github.bert0ns.cribrum.review

import io.github.bert0ns.cribrum.index.evidenceFixture
import io.github.bert0ns.cribrum.index.memoryIndex
import io.github.bert0ns.cribrum.index.store
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.IndexedImage
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.ranker.Ranker
import io.github.bert0ns.cribrum.ranker.THRESHOLDS
import io.github.bert0ns.cribrum.trash.AppTrash
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The ranker and the review read the index through `indexedImages()`. Here they run on the images as the
 * fixture built them, with plain evidence maps, and on the images read back from the index: the results must match.
 */
@RunWith(RobolectricTestRunner::class)
class EvidenceEquivalenceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val index = memoryIndex()
    private val fixture = evidenceFixture(300)

    @After
    fun close() = index.close()

    /** The fixture as the detectors made it, with the states and kept signals that `setReviewState` stored. */
    private suspend fun expectedImages(newCopy: Signal): List<IndexedImage> = fixture.mapIndexed { i, image ->
        val signals = image.signals.values.flatten() + listOfNotNull(newCopy.takeIf { i % 14 == 0 })
        IndexedImage(image.ref, index.reviewState(image.ref.path), signals, index.keptSignals(image.ref.path))
    }

    private fun review(trashDir: String) = Review(index, AppTrash({ listOf(folder.newFolder(trashDir)) }))

    @Test
    fun `the ranker and the review give the same results on images read from the index`() = runBlocking {
        index.store(fixture)
        fixture.filterIndexed { i, _ -> i % 7 == 0 }.forEach { index.setReviewState(it.ref.path, ReviewState.KEPT) }
        // A signal the kept image did not have: it comes back among the candidates.
        val newCopy = Signal("copy", 1, Category.EXACT_DUPLICATE, 1f, mapOf(GROUP_KEY to "new", ROLE_KEY to Role.INFERIOR.value))
        fixture.filterIndexed { i, _ -> i % 14 == 0 }.forEach { index.setSignals(it.ref.path, "copy", 1, listOf(newCopy)) }
        fixture.filterIndexed { i, _ -> i % 11 == 0 }.forEach { index.setReviewState(it.ref.path, ReviewState.TRASHED) }
        val read = index.indexedImages()
        val expected = expectedImages(newCopy).sortedBy { it.ref.path }
        val excluded = listOf("/photos/5")

        assertEquals(expected, read)
        assertEquals(Ranker(THRESHOLDS).rank(expected, excluded), Ranker(THRESHOLDS).rank(read, excluded))

        index.excludeFolder("/photos/5")
        val fromIndex = review("trash-a")
        val fromFixture = review("trash-b").apply { readImages = { expected } }
        assertEquals(fromFixture.overview(), fromIndex.overview())
        assertTrue(fromIndex.overview().categories.all { it.count > 0 })
        for (category in Category.entries) {
            assertEquals(category.name, fromFixture.page(category), fromIndex.page(category))
        }
    }
}
