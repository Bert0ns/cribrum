// The lab's tests/test_ranker.py, translated.
package io.github.bert0ns.cribrum.ranker

import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.IndexedImage
import io.github.bert0ns.cribrum.model.Protection
import io.github.bert0ns.cribrum.model.ROLE_KEY
import io.github.bert0ns.cribrum.model.ReviewState
import io.github.bert0ns.cribrum.model.Role
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.model.SignalIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val TEST_THRESHOLDS =
    mapOf(Category.BLURRY to 0.5f, Category.SCREENSHOT to 0.8f, Category.EXACT_DUPLICATE to 0.5f)

private fun image(
    name: String,
    vararg signals: Signal,
    state: ReviewState = ReviewState.UNREVIEWED,
    seenWhenKept: Set<SignalIdentity> = emptySet(),
) = IndexedImage(ImageRef(name, 0, 0), state, signals.toList(), seenWhenKept)

private fun blur(score: Float, version: Int = 1) = Signal("blur", version, Category.BLURRY, score)

private fun screenshot(score: Float) = Signal("screenshot", 1, Category.SCREENSHOT, score)

private fun duplicate(group: String, role: Role): Signal {
    val score = if (role == Role.BEST) 0f else 1f
    val evidence = mapOf(GROUP_KEY to group, ROLE_KEY to role.value)
    return Signal("exact_duplicate", 1, Category.EXACT_DUPLICATE, score, evidence)
}

private fun faces(score: Float = 1f) = Signal("faces", 1, Protection.FACES, score, mapOf("faces" to "2"))

private fun rank(vararg images: IndexedImage) = Ranker(TEST_THRESHOLDS).rank(images.toList())

private fun names(candidates: List<Candidate>) = candidates.map { it.image.ref.path }

class RankerTest {
    @Test
    fun `any signal past its threshold makes a candidate`() {
        val blurry = image("blurry", blur(0.6f), screenshot(0.1f))
        val screen = image("screen", blur(0.1f), screenshot(0.9f))
        val neither = image("neither", blur(0.4f), screenshot(0.7f))

        val candidates = rank(blurry, screen, neither).associateBy { it.image.ref.path }

        assertEquals(listOf(blur(0.6f)), candidates.getValue("blurry").reasons)
        assertEquals(listOf(screenshot(0.9f)), candidates.getValue("screen").reasons)
        assertFalse(candidates.getValue("neither").isCandidate)
    }

    @Test
    fun `signal exactly at the threshold passes`() {
        assertTrue(rank(image("a", blur(0.5f))).single().isCandidate)
    }

    @Test
    fun `category without a threshold never passes`() {
        val signal = Signal("document", 1, Category.DOCUMENT, 1f)

        assertFalse(rank(image("a", signal)).single().isCandidate)
    }

    @Test
    fun `review everything lists every unreviewed image candidates first`() {
        val order = rank(
            image("unflagged", blur(0.45f)),
            image("barely", blur(0.55f)),
            image("clean"),
            image("sure", blur(0.95f)),
            image("trashed", blur(0.9f), state = ReviewState.TRASHED),
        )

        assertEquals(listOf("sure", "barely", "unflagged", "clean"), names(order))
    }

    @Test
    fun `order compares categories by their margin over the threshold`() {
        // 0.85 is barely past the screenshot threshold, 0.7 well past the blur one.
        val order = rank(image("screen", screenshot(0.85f)), image("blurry", blur(0.7f)))

        assertEquals(listOf("blurry", "screen"), names(order))
    }

    @Test
    fun `by category sorts each category by score`() {
        val order = rank(
            image("a", blur(0.6f)),
            image("b", blur(0.9f), screenshot(0.95f)),
            image("c", screenshot(0.85f)),
            image("d", blur(0.1f)),
        )

        val groups = Ranker.byCategory(order).mapValues { names(it.value) }

        assertEquals(mapOf(Category.BLURRY to listOf("b", "a"), Category.SCREENSHOT to listOf("b", "c")), groups)
    }

    @Test
    fun `best copy of a group is not a candidate`() {
        val order = rank(image("best", duplicate("g", Role.BEST)), image("copy", duplicate("g", Role.INFERIOR)))

        assertEquals(listOf(true, false), order.map { it.isCandidate })
    }

    @Test
    fun `kept image is not suggested again for the same signals`() {
        val kept = image(
            "kept",
            blur(0.9f),
            duplicate("g", Role.INFERIOR),
            state = ReviewState.KEPT,
            seenWhenKept = setOf(blur(0.9f).identity, duplicate("g", Role.INFERIOR).identity),
        )

        assertEquals(emptyList<Candidate>(), rank(kept))
    }

    @Test
    fun `new detector version or score is not a new signal`() {
        val kept = image(
            "kept", blur(0.95f, version = 2), state = ReviewState.KEPT, seenWhenKept = setOf(blur(0.2f).identity),
        )

        assertEquals(emptyList<Candidate>(), rank(kept))
    }

    @Test
    fun `kept image comes back for a new signal only`() {
        val kept = image(
            "kept",
            blur(0.9f),
            duplicate("new-group", Role.INFERIOR),
            state = ReviewState.KEPT,
            seenWhenKept = setOf(blur(0.9f).identity),
        )

        assertEquals(listOf(duplicate("new-group", Role.INFERIOR)), rank(kept).single().reasons)
    }

    @Test
    fun `kept best copy comes back when it becomes the inferior one`() {
        // A higher-resolution copy showed up after the user kept this one.
        val kept = image(
            "kept",
            duplicate("g", Role.INFERIOR),
            state = ReviewState.KEPT,
            seenWhenKept = setOf(duplicate("g", Role.BEST).identity),
        )

        assertEquals(listOf("kept"), names(rank(kept)))
    }

    @Test
    fun `kept image with a new signal below threshold stays hidden`() {
        assertEquals(emptyList<Candidate>(), rank(image("kept", screenshot(0.5f), state = ReviewState.KEPT)))
    }

    @Test
    fun `threshold of one passes only a perfect score`() {
        val ranked = Ranker(mapOf(Category.BLURRY to 1f)).rank(listOf(image("a", blur(1f)), image("b", blur(0.9f))))

        assertEquals(listOf(true, false), ranked.map { it.isCandidate })
    }

    @Test
    fun `protection lowers an image but keeps it a candidate`() {
        val order = rank(image("people", blur(0.9f), faces()), image("wall", blur(0.8f)))

        assertEquals(listOf("wall", "people"), names(order))
        assertTrue(order.all { it.isCandidate })
        assertEquals(listOf(blur(0.9f)), order[1].reasons)
    }

    @Test
    fun `protected candidate still comes before every non-candidate`() {
        val order = rank(
            image("people", blur(0.5f), faces(), Signal("x", 1, Protection.FAVORITE, 1f)),
            image("unflagged", blur(0.49f)),
        )

        assertEquals(listOf("people", "unflagged"), names(order))
    }

    @Test
    fun `protection signal alone never makes a candidate`() {
        assertFalse(rank(image("people", faces())).single().isCandidate)
    }

    @Test
    fun `unique image ranks below an equally scored duplicate`() {
        val order = rank(
            image("unique", blur(0.9f)),
            image("copy", blur(0.9f), duplicate("g", Role.INFERIOR)),
            image("best", blur(0.9f), duplicate("g", Role.BEST)),
        )

        assertEquals("unique", names(order).last())
        assertTrue(order.last().protection < 1f)
    }

    @Test
    fun `by category applies protection too`() {
        val order = rank(image("people", blur(0.9f), faces()), image("wall", blur(0.8f)))

        assertEquals(listOf("wall", "people"), names(Ranker.byCategory(order).getValue(Category.BLURRY)))
    }

    @Test
    fun `a sticker is listed only as a sticker`() {
        val sticker = Signal("sticker", 1, Category.STICKER, 1f)
        val order = Ranker(TEST_THRESHOLDS + (Category.STICKER to 0.5f))
            .rank(listOf(image("sticker", blur(0.9f), sticker), image("photo", blur(0.8f))))

        val groups = Ranker.byCategory(order).mapValues { names(it.value) }

        assertEquals(mapOf(Category.BLURRY to listOf("photo"), Category.STICKER to listOf("sticker")), groups)
        assertTrue(order.all { it.isCandidate }) // still a candidate, in the same order
        assertEquals(listOf("sticker", "photo"), names(order))
    }

    @Test
    fun `thresholds are the lab's`() {
        val lab = java.io.File("../../lab/src/cribrum_lab/ranker.py").readText()
        for ((category, threshold) in THRESHOLDS) {
            assertTrue("$category", "Category.${category.name}: $threshold," in lab)
        }
    }
}
