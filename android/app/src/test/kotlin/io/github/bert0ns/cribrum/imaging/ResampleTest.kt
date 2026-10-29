package io.github.bert0ns.cribrum.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** Extreme values (0 and 255) mixed with noise, so the weights' negative lobes reach their largest sums. */
private fun noise(width: Int, height: Int, seed: Int): Gray {
    val random = Random(seed)
    return Gray(width, height, FloatArray(width * height) { if (random.nextInt(3) == 0) (random.nextInt(2) * 255).toFloat() else random.nextInt(256).toFloat() })
}

private fun noiseArgb(width: Int, height: Int, seed: Int): Argb {
    val random = Random(seed)
    return Argb(width, height, IntArray(width * height) { random.nextInt() })
}

/** Up, down, odd, one pixel wide or high, and the sizes Text and MobileClip ask for. */
private val SIZES = listOf(
    Triple(1000 to 750, 640 to 480, "down, as Text"),
    Triple(1080 to 2400, 288 to 640, "down, a tall screenshot as Text"),
    Triple(750 to 1000, 256 to 341, "down, as MobileClip"),
    Triple(333 to 217, 160 to 96, "down, odd sides"),
    Triple(97 to 53, 640 to 352, "up"),
    Triple(31 to 64, 32 to 65, "almost the same"),
    Triple(64 to 64, 64 to 64, "same size"),
    Triple(1 to 40, 7 to 3, "one pixel wide"),
    Triple(40 to 1, 3 to 7, "one pixel high"),
    Triple(1 to 1, 5 to 5, "one pixel"),
    Triple(300 to 200, 1 to 1, "down to one pixel"),
    Triple(7 to 5, 3 to 2, "tiny, odd"),
)

class ResampleTest {
    @Test
    fun `resample gives the numbers it gave before, for both filters and the box thumbnail uses`() {
        for ((index, case) in SIZES.withIndex()) {
            val (from, to, name) = case
            val image = noise(from.first, from.second, index)
            for ((filterName, filter) in listOf("bilinear" to BILINEAR, "lanczos" to LANCZOS)) {
                val expected = referenceResample(image, to.first, to.second, 0.0, 0.0, from.first.toDouble(), from.second.toDouble(), filter)
                val got = resample(image, to.first, to.second, 0.0, 0.0, from.first.toDouble(), from.second.toDouble(), filter)
                assertEquals("$name, $filterName", listOf(expected.width, expected.height), listOf(got.width, got.height))
                assertArrayEquals("$name, $filterName", expected.pixels, got.pixels, 0f)
            }
        }
    }

    @Test
    fun `resample over a box that is not the whole image gives the numbers it gave before`() {
        val image = noise(200, 120, 99)
        val boxes = listOf(listOf(10.5, 4.25, 187.0, 119.5), listOf(0.0, 0.0, 100.0, 120.0), listOf(33.0, 0.0, 200.0, 60.5))
        for (box in boxes) {
            val expected = referenceResample(image, 50, 33, box[0], box[1], box[2], box[3])
            val got = resample(image, 50, 33, box[0], box[1], box[2], box[3])
            assertArrayEquals("box $box", expected.pixels, got.pixels, 0f)
        }
    }

    @Test
    fun `a channel is the same for every orientation and every shape as it was before`() {
        for ((index, shape) in listOf(5 to 3, 4 to 4, 1 to 6, 6 to 1, 1 to 1, 17 to 9).withIndex()) {
            val image = noiseArgb(shape.first, shape.second, index)
            for (shift in listOf(16, 8, 0)) {
                for (orientation in 0..9) {
                    val expected = referenceChannel(image, shift, orientation)
                    val got = channel(image, shift, orientation)
                    assertEquals("$shape, shift $shift, orientation $orientation", listOf(expected.width, expected.height), listOf(got.width, got.height))
                    assertArrayEquals("$shape, shift $shift, orientation $orientation", expected.pixels, got.pixels, 0f)
                }
            }
        }
    }
}
