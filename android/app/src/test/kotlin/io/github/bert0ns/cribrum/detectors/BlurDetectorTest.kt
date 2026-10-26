// The lab's tests/test_blur.py, translated.
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
class BlurDetectorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun analyze(image: TestImage, name: String = "a.png"): List<Signal> {
        val file = image.save(File(folder.root, name))
        return runBlocking { BlurDetector().analyze(ImageRef(file.path, 0, 0), AnalysisContext()) }
    }

    private fun score(image: TestImage, name: String = "a.png"): Float {
        val signal = analyze(image, name).single()
        assertEquals(Category.BLURRY, signal.kind)
        return signal.score
    }

    @Test
    fun `blurred copy scores higher than the sharp original`() {
        val sharp = TestImage.texture()

        val sharpScore = score(sharp, "sharp.png")
        val blurredScore = score(sharp.blurred(8), "blurred.png")

        assertTrue("$sharpScore < 0.5 < $blurredScore", sharpScore < 0.5f && 0.5f < blurredScore)
    }

    @Test
    fun `sharp subject on a blurred background is not blurry`() {
        val portrait = TestImage.texture().blurred(8)
        portrait.paste(TestImage.texture(200, 200, seed = 1), 300, 200)

        assertTrue(score(portrait) < 0.5f)
    }

    @Test
    fun `score grows with blur`() {
        val scores = listOf(0, 2, 8, 30).map { score(TestImage.texture().blurred(it), "r$it.png") }

        assertEquals(scores.sorted(), scores)
    }

    @Test
    fun `evidence records the measures`() {
        assertEquals(setOf("global_variance", "peak_variance"), analyze(TestImage.texture()).single().evidence.keys)
    }

    @Test
    fun `tiny and thin images do not crash`() {
        for ((width, height) in listOf(4 to 4, 10000 to 200)) {
            val signals = analyze(
                TestImage.texture(maxOf(width, 8), maxOf(height, 8)).let {
                    TestImage(width, height, IntArray(width * height) { i -> it[i % width % it.width, i / width % it.height] })
                },
                "${width}x$height.png",
            )

            assertTrue(signals.isNotEmpty())
            assertTrue(signals.all { it.score in 0f..1f })
        }
    }

    @Test
    fun `an image that ends under 3 px on a side yields no signal`() {
        for ((width, height) in listOf(1 to 1, 2 to 2, 10000 to 20)) {
            val image = TestImage(width, height, IntArray(width * height) { 100 * it % 256 })
            assertTrue(analyze(image, "${width}x$height.png").isEmpty())
        }
    }
}
