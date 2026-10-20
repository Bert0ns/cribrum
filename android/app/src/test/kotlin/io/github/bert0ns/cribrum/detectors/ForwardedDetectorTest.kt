// Tests for ForwardedDetector translated from lab/tests/test_zero_shot.py.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.metadata.ImageMetadata
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Signal
import io.github.bert0ns.cribrum.prompts.Prompts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ForwardedDetectorTest {
    private fun analyze(
        detector: ForwardedDetector = ForwardedDetector(),
        promptText: String,
        path: String = "/sdcard/Download/a.jpg",
        camera: Boolean = false,
    ): Signal {
        val meta = ImageMetadata(1000, 1000, "image/jpeg", cameraModel = if (camera) "Pixel 8" else null)
        val embedding = Prompts.promptVector(promptText)
        val context = AnalysisContext(metadata = { meta }, embedding = { embedding })
        val signal = runBlocking { detector.analyze(ImageRef(path, 0, 0), context) }.single()
        assertEquals(Category.FORWARDED, signal.kind)
        return signal
    }

    @Test
    fun `meme in a messaging folder scores highest`() {
        val meme = "an internet meme"
        val whatsapp = "/sdcard/WhatsApp/Media/WhatsApp Images/IMG-1.jpg"

        val inChat = analyze(promptText = meme, path = whatsapp).score
        val downloaded = analyze(promptText = meme).score

        assertTrue("Expected inChat > downloaded", inChat > downloaded)
        assertTrue("Expected downloaded > 0.5", downloaded > 0.5f)
    }

    @Test
    fun `photo a friend sent stays below a meme`() {
        val whatsapp = "/sdcard/WhatsApp/Media/WhatsApp Images/IMG-1.jpg"
        val signal = analyze(promptText = "a group photo of friends", path = whatsapp)

        assertTrue(signal.score < 0.5f)
        assertEquals("yes", signal.evidence["messaging_folder"])
        assertEquals("no", signal.evidence["camera_exif"])
    }

    @Test
    fun `camera photo outside messaging folders scores near zero`() {
        val signal = analyze(
            promptText = "a photo of a landscape",
            path = "/sdcard/DCIM/Camera/a.jpg",
            camera = true,
        )

        assertTrue(signal.score < 0.05f)
    }

    @Test
    fun `missing embedding propagates`() {
        val context = AnalysisContext(metadata = { ImageMetadata(100, 100, "image/jpeg") })
        assertThrows(MissingEmbedding::class.java) {
            runBlocking {
                ForwardedDetector().analyze(ImageRef("/sdcard/Download/a.jpg", 0, 0), context)
            }
        }
    }
}
