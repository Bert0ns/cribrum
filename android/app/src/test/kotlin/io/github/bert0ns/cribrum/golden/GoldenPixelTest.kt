package io.github.bert0ns.cribrum.golden

import io.github.bert0ns.cribrum.detectors.AnalysisContext
import io.github.bert0ns.cribrum.detectors.BlurDetector
import io.github.bert0ns.cribrum.detectors.Detector
import io.github.bert0ns.cribrum.detectors.ExposureDetector
import io.github.bert0ns.cribrum.detectors.NearDuplicateDetector
import io.github.bert0ns.cribrum.hashing.perceptualHash
import io.github.bert0ns.cribrum.model.GROUP_KEY
import io.github.bert0ns.cribrum.model.ROLE_KEY
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** BLURRY, BAD_EXPOSURE, perceptual hashes and NEAR_DUPLICATE against the lab's golden fixtures, decoded by Android. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoldenPixelTest {
    private fun check(detector: Detector, sameCase: Boolean = false) = runBlocking {
        for (expected in Golden.images) {
            val want = expected.signal(detector.id)!!
            val got = detector.analyze(expected.image, AnalysisContext()).single()

            assertEquals(expected.relative, want.version, got.detectorVersion)
            assertEquals(expected.relative, want.category, got.kind.value)
            assertEquals(expected.relative, want.score, got.score, Golden.scoreTolerance)
            if (sameCase) assertEquals(expected.relative, want.evidence["case"], got.evidence["case"])
        }
    }

    @Test
    fun `blur scores match`() = check(BlurDetector())

    @Test
    fun `exposure scores and cases match`() = check(ExposureDetector(), sameCase = true)

    @Test
    fun `perceptual hashes match exactly`() {
        for (expected in Golden.images) {
            val got = perceptualHash(expected.image).toString(16).padStart(16, '0')
            assertEquals(expected.relative, expected.perceptualHash, got)
        }
    }

    /**
     * Near-duplicate groups matched by perceptual hash alone reproduce expected.json's groups,
     * best copies, and Hamming distances. Images requiring MobileCLIP embeddings (such as blurred.jpg,
     * distance 32 > 10) are documented and left unlinked until embeddings are added.
     */
    @Test
    fun `near duplicate reproduces hash-linked groups from golden fixtures`() = runBlocking {
        val detector = NearDuplicateDetector()
        val found = detector.analyze(Golden.images.map { it.image }, AnalysisContext())

        val expectedToActualGroup = mutableMapOf<String, String>()

        for (expected in Golden.images) {
            val want = expected.signal(detector.id)
            val got = found[expected.image]?.single()
            if (expected.relative == "DCIM/Camera/blurred.jpg") {
                // Linked only by MobileCLIP embeddings in expected.json (distance 32 > MAX_DISTANCE 10)
                assertEquals(null, got)
                continue
            }
            if (want == null) {
                assertEquals(expected.relative, null, got)
            } else {
                assertNotNull(expected.relative, got)
                assertEquals(expected.relative, want.version, got!!.detectorVersion)
                assertEquals(expected.relative, want.category, got.kind.value)
                assertEquals(expected.relative, want.evidence[ROLE_KEY], got.evidence[ROLE_KEY])
                assertEquals(expected.relative, want.evidence["distance"], got.evidence["distance"])

                val wantGroup = want.evidence[GROUP_KEY]!!
                val gotGroup = got.evidence[GROUP_KEY]!!
                val mapped = expectedToActualGroup.getOrPut(wantGroup) { gotGroup }
                assertEquals(expected.relative, mapped, gotGroup)
            }
        }
        assertEquals(expectedToActualGroup.values.toSet().size, expectedToActualGroup.size)
    }
}
