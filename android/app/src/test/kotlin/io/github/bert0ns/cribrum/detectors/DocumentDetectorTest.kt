// Tests for DocumentDetector translated from lab/tests/test_zero_shot.py.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.prompts.PROMPTS
import io.github.bert0ns.cribrum.prompts.promptVector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DocumentDetectorTest {
    private fun analyze(detector: DocumentDetector, promptText: String): Signal {
        val embedding = promptVector(promptText)
        val context = AnalysisContext(embedding = { embedding })
        return runBlocking { detector.analyze(ImageRef("/sdcard/Download/a.jpg", 0, 0), context) }.single()
    }

    @Test
    fun `document prompts score high as documents`() {
        for (text in PROMPTS.getValue("document")) {
            val signal = analyze(DocumentDetector { 0f }, text)

            assertEquals(Category.DOCUMENT, signal.kind)
            assertTrue("$text: ${signal.score}", signal.score > 0.5f)
            assertEquals(text, signal.evidence["prompt"])
        }
    }

    @Test
    fun `photo of people is not a document`() {
        assertTrue(analyze(DocumentDetector { 0.002f }, "a group photo of friends").score < 0.1f)
    }

    @Test
    fun `text coverage raises the document score`() {
        val signal = analyze(DocumentDetector { 0.12f }, "a photo of a landscape")

        assertTrue(signal.score > 0.75f)
        assertEquals("0.1200", signal.evidence["text_coverage"])
    }

    @Test
    fun `missing embedding propagates`() {
        assertThrows(MissingEmbedding::class.java) {
            runBlocking { DocumentDetector { 0f }.analyze(ImageRef("/sdcard/Download/a.jpg", 0, 0), AnalysisContext()) }
        }
    }
}
