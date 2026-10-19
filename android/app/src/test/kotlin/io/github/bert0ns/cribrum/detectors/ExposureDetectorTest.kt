// The lab's tests/test_exposure.py, translated.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.imaging.TestImage
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.model.Signal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExposureDetectorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun analyze(image: TestImage): Signal {
        val file = image.save(File(folder.newFolder(), "a.png"))
        val signal = runBlocking { ExposureDetector().analyze(ImageRef(file.path, 0, 0), AnalysisContext()) }.single()
        assertEquals(Category.BAD_EXPOSURE, signal.kind)
        return signal
    }

    @Test
    fun `bad frames score high and name their case`() {
        for ((image, case) in listOf(
            TestImage.filled(400, 300, 0) to "dark",
            TestImage.noisy(12) to "dark", // pocket shot: near-black sensor noise
            TestImage.filled(400, 300, 255) to "bright",
            TestImage.filled(400, 300, 128) to "flat",
        )) {
            val signal = analyze(image)

            assertTrue(case, signal.score > 0.9f)
            assertEquals(case, signal.evidence["case"])
        }
    }

    @Test
    fun `well exposed image scores low`() {
        val gradient = TestImage(400, 300, IntArray(400 * 300) { i -> (i % 400) * 255 / 399 })

        assertTrue(analyze(gradient).score < 0.5f)
    }

    @Test
    fun `high contrast scenes with skewed histograms score low`() {
        val night = TestImage.noisy(10, spread = 4.0)
        night.paste(TestImage.filled(160, 150, 205), 120, 80) // lit subject, 20% of frame
        val page = TestImage.filled(400, 300, 245)
        for (top in 20 until 280 step 20) page.paste(TestImage.filled(360, 2, 20), 20, top) // lines of text, ~10%

        assertTrue("night shot", analyze(night).score < 0.5f)
        assertTrue("document", analyze(page).score < 0.5f)
    }
}
