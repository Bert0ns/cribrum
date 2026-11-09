// The lab's tests/test_blur.py, translated.
package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.imaging.Gray
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
import kotlin.random.Random

/** BlurDetector.measure as it was before #353, on boxed lists. Do not optimize it. */
private fun referenceMeasure(gray: Gray): Pair<Double, Double>? {
    val w = gray.width - 2
    val h = gray.height - 2
    if (w <= 0 || h <= 0) return null
    val laplacian = DoubleArray(w * h) { i ->
        val x = i % w + 1
        val y = i / w + 1
        (gray[x, y - 1] + gray[x, y + 1] + gray[x - 1, y] + gray[x + 1, y] - 4 * gray[x, y]).toDouble()
    }
    fun split(n: Int, parts: Int): List<IntRange> {
        var start = 0
        return (0 until parts).map { i ->
            val size = n / parts + if (i < n % parts) 1 else 0
            (start until start + size).also { start += size }
        }
    }
    fun variance(values: List<Double>): Double {
        val mean = values.sum() / values.size
        return values.sumOf { (it - mean) * (it - mean) } / values.size
    }
    var peak = 0.0
    for (rows in split(h, BlurDetector.TILES)) {
        for (columns in split(w, BlurDetector.TILES)) {
            if (rows.isEmpty() || columns.isEmpty()) continue
            peak = maxOf(peak, variance(rows.flatMap { y -> columns.map { x -> laplacian[y * w + x] } }))
        }
    }
    return variance(laplacian.asList()) to peak
}

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

    @Test
    fun `the measure is the same to the bit as it was on boxed lists`() {
        // Under TILES px inside, odd sides, as the thumbnail leaves a photo both ways, and a long strip.
        val sizes = listOf(3 to 3, 4 to 4, 5 to 7, 6 to 3, 7 to 40, 40 to 7, 17 to 13, 512 to 384, 384 to 512, 513 to 9, 2 to 10, 10 to 2)
        for ((index, size) in sizes.withIndex()) {
            val random = Random(index)
            val (width, height) = size
            // Whole luminance levels, as loadGrayscale gives, and arbitrary floats, which round differently when summed.
            val levels = Gray(width, height, FloatArray(width * height) { random.nextInt(256).toFloat() })
            val floats = Gray(width, height, FloatArray(width * height) { random.nextFloat() * 255f })
            for (gray in listOf(levels, floats)) {
                assertEquals("${width}x$height", referenceMeasure(gray), BlurDetector.measure(gray))
            }
        }
    }
}
