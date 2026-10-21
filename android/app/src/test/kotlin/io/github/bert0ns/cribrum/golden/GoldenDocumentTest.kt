package io.github.bert0ns.cribrum.golden

import io.github.bert0ns.cribrum.detectors.AnalysisContext
import io.github.bert0ns.cribrum.detectors.DocumentDetector
import io.github.bert0ns.cribrum.text.textCoverage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Text coverage and DOCUMENT, with the real PP-OCRv4 model on Android's decoders, against the lab's golden fixtures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoldenDocumentTest {
    @Test
    fun `text coverage matches`() {
        for (expected in Golden.images) {
            val want = expected.signal("document")!!.evidence.getValue("text_coverage").toFloat()

            assertEquals(expected.relative, want, textCoverage(File(expected.image.path)), COVERAGE_TOLERANCE)
        }
    }

    @Test
    fun `document scores match`() = runBlocking {
        val detector = DocumentDetector()
        for (expected in Golden.images) {
            val want = expected.signal(detector.id)!!
            val got = detector.analyze(expected.image, AnalysisContext(embedding = { expected.embedding })).single()

            assertEquals(expected.relative, want.version, got.detectorVersion)
            assertEquals(expected.relative, want.category, got.kind.value)
            assertEquals(expected.relative, want.score, got.score, Golden.scoreTolerance)
        }
    }

    private companion object {
        /** Coverage 0.01 moves the score by 0.08, so it has to match closer than the score does. */
        const val COVERAGE_TOLERANCE = 0.005f
    }
}
