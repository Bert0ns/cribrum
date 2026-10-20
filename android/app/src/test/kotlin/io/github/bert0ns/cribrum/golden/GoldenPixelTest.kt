package io.github.bert0ns.cribrum.golden

import io.github.bert0ns.cribrum.detectors.AnalysisContext
import io.github.bert0ns.cribrum.detectors.BlurDetector
import io.github.bert0ns.cribrum.detectors.Detector
import io.github.bert0ns.cribrum.detectors.ExposureDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** BLURRY and BAD_EXPOSURE against the lab's golden fixtures, decoded by Android. */
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
}
