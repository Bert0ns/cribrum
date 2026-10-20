package io.github.bert0ns.cribrum.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StorageMapTest {
    private val bounds = Rect(0.0, 0.0, 100.0, 100.0)

    @Test
    fun `single weight fills the bounds`() {
        val rects = squarify(listOf(42.0), bounds)
        assertEquals(1, rects.size)
        assertEquals(bounds, rects.first())
    }

    @Test
    fun `zero weights get empty rects`() {
        val rects = squarify(listOf(0.0, 0.0), bounds)
        assertEquals(2, rects.size)
        assertTrue(rects.all { it.width == 0.0 && it.height == 0.0 })
    }

    @Test
    fun `mixed zero and non-zero weights`() {
        val rects = squarify(listOf(0.0, 50.0, 0.0), bounds)
        assertEquals(3, rects.size)
        assertEquals(0.0, rects[0].width, 1e-6)
        assertEquals(bounds.width * bounds.height, rects[1].width * rects[1].height, 1e-6)
        assertEquals(0.0, rects[2].width, 1e-6)
    }

    @Test
    fun `areas proportional to weights`() {
        val weights = listOf(6.0, 6.0, 4.0, 3.0, 2.0, 2.0, 1.0)
        val rects = squarify(weights, bounds)
        assertEquals(weights.size, rects.size)
        val totalWeight = weights.sum()
        val totalArea = bounds.width * bounds.height

        for (i in weights.indices) {
            val expectedArea = totalArea * (weights[i] / totalWeight)
            val actualArea = rects[i].width * rects[i].height
            assertEquals(expectedArea, actualArea, 1e-6)
        }
    }

    @Test
    fun `every rect inside bounds`() {
        val weights = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        val rects = squarify(weights, bounds)
        for (rect in rects) {
            assertTrue("left >= bounds.left", rect.left >= bounds.left - 1e-6)
            assertTrue("top >= bounds.top", rect.top >= bounds.top - 1e-6)
            assertTrue("right <= bounds.right", rect.left + rect.width <= bounds.left + bounds.width + 1e-6)
            assertTrue("bottom <= bounds.bottom", rect.top + rect.height <= bounds.top + bounds.height + 1e-6)
        }
    }

    @Test
    fun `no overlaps between rectangles`() {
        val weights = listOf(15.0, 25.0, 5.0, 35.0, 10.0, 20.0)
        val rects = squarify(weights, bounds)

        for (i in rects.indices) {
            for (j in i + 1 until rects.size) {
                val r1 = rects[i]
                val r2 = rects[j]
                val xOverlap = maxOf(0.0, minOf(r1.left + r1.width, r2.left + r2.width) - maxOf(r1.left, r2.left))
                val yOverlap = maxOf(0.0, minOf(r1.top + r1.height, r2.top + r2.height) - maxOf(r1.top, r2.top))
                val intersectionArea = xOverlap * yOverlap
                assertTrue("Rect $i and Rect $j overlap with area $intersectionArea", intersectionArea < 1e-6)
            }
        }
    }
}
