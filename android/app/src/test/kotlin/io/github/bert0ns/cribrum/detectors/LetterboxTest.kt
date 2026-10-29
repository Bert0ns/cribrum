package io.github.bert0ns.cribrum.detectors

import io.github.bert0ns.cribrum.imaging.Argb
import io.github.bert0ns.cribrum.imaging.BILINEAR
import io.github.bert0ns.cribrum.imaging.referenceChannel
import io.github.bert0ns.cribrum.imaging.referenceResample
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.max
import kotlin.random.Random

/** YuNet.letterbox as it was before #254, one channel at a time, on the reference channel split and resample. Do not optimize it. */
private fun referenceLetterbox(decoded: Argb, orientation: Int): YuNet.Letterbox {
    val side = YuNet.INPUT_SIDE
    val canvas = FloatArray(3 * side * side)
    var size = 0 to 0
    for ((c, shift) in listOf(0, 8, 16).withIndex()) {
        val plane = referenceChannel(decoded, shift, orientation)
        val scale = side.toDouble() / max(plane.width, plane.height)
        val width = max(1, Math.rint(plane.width * scale).toInt())
        val height = max(1, Math.rint(plane.height * scale).toInt())
        val resized = referenceResample(plane, width, height, 0.0, 0.0, plane.width.toDouble(), plane.height.toDouble(), BILINEAR)
        for (y in 0 until height) for (x in 0 until width) canvas[(c * side + y) * side + x] = resized[x, y]
        size = width to height
    }
    return YuNet.Letterbox(canvas, size.first, size.second)
}

class LetterboxTest {
    @Test
    fun `the letterbox is the same for every size and orientation as it was before`() {
        // Decoded sizes: a 12 MP JPEG after the draft, both ways, a tall screenshot, already 640, up from small,
        // odd sides, one pixel wide or high, and a height that scales to a half (1.5 and 0.5, rounded to even).
        val sizes = listOf(1000 to 750, 750 to 1000, 1080 to 2400, 640 to 640, 1280 to 640, 97 to 53, 333 to 217, 1 to 40, 40 to 1, 1 to 1, 1280 to 3, 1280 to 1)
        for ((index, size) in sizes.withIndex()) {
            val random = Random(index)
            val image = Argb(size.first, size.second, IntArray(size.first * size.second) { random.nextInt() })
            for (orientation in 0..9) {
                val expected = referenceLetterbox(image, orientation)
                val got = YuNet.letterbox(image, orientation)
                val name = "${size.first}x${size.second}, orientation $orientation"
                assertEquals(name, expected.width to expected.height, got.width to got.height)
                assertArrayEquals(name, expected.pixels, got.pixels, 0f) // the padding too
            }
        }
    }
}
