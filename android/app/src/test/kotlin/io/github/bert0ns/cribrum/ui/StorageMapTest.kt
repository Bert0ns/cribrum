package io.github.bert0ns.cribrum.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun `folder tree sums sizes of subfolders`() {
        val images = listOf(
            "/storage/emulated/0/DCIM/Camera/IMG_001.jpg" to 1000L,
            "/storage/emulated/0/DCIM/Camera/IMG_002.jpg" to 2000L,
            "/storage/emulated/0/DCIM/Screenshots/scr_01.png" to 500L,
            "/storage/emulated/0/Pictures/vacation.jpg" to 4000L,
            "/storage/emulated/0/Pictures/Sub/pic.jpg" to 1500L,
        )
        val tree = buildFolderTree(images)

        assertEquals("0", tree.name)
        assertEquals("/storage/emulated/0", tree.path)
        assertEquals(9000L, tree.size)
        assertEquals(5, tree.imageCount)

        // Subfolders should be sorted by size descending
        assertEquals(2, tree.children.size)
        val pictures = tree.children[0]
        val dcim = tree.children[1]

        assertEquals("Pictures", pictures.name)
        assertEquals(5500L, pictures.size)
        assertEquals(2, pictures.imageCount)
        assertEquals(4000L, pictures.directSize)
        assertEquals(1, pictures.directImageCount)
        assertEquals(1, pictures.children.size)
        assertEquals("Sub", pictures.children[0].name)
        assertEquals(1500L, pictures.children[0].size)
        assertEquals(1, pictures.children[0].imageCount)

        assertEquals("DCIM", dcim.name)
        assertEquals(3500L, dcim.size)
        assertEquals(3, dcim.imageCount)
        assertEquals(0L, dcim.directSize)
        assertEquals(0, dcim.directImageCount)
        assertEquals(2, dcim.children.size)
        assertEquals("Camera", dcim.children[0].name)
        assertEquals(3000L, dcim.children[0].size)
        assertEquals(2, dcim.children[0].imageCount)
        assertEquals("Screenshots", dcim.children[1].name)
        assertEquals(500L, dcim.children[1].size)
        assertEquals(1, dcim.children[1].imageCount)
    }

    @Test
    fun `folder tree with empty image list`() {
        val tree = buildFolderTree(emptyList())
        assertEquals(0L, tree.size)
        assertEquals(0, tree.imageCount)
        assertTrue(tree.children.isEmpty())
    }

    @Test
    fun `rect contains point`() {
        val r = Rect(10.0, 20.0, 50.0, 40.0)
        assertTrue(r.contains(10.0, 20.0))
        assertTrue(r.contains(30.0, 40.0))
        assertTrue(r.contains(60.0, 60.0))
        assertFalse(r.contains(9.9, 20.0))
        assertFalse(r.contains(60.1, 40.0))
    }

    @Test
    fun `layoutTreemap recursive blocks and hues`() {
        val images = listOf(
            "/DCIM/Camera/IMG_001.jpg" to 3000L,
            "/DCIM/Screenshots/scr.png" to 1000L,
            "/Pictures/vacation.jpg" to 6000L,
        )
        val tree = buildFolderTree(images)
        val blocks = layoutTreemap(tree, bounds, gap = 1.0, minSize = 2.0)
        assertTrue(blocks.isNotEmpty())

        val dcimBlocks = blocks.filter { it.folder.name == "DCIM" || it.folder.path.contains("DCIM") }
        val picBlocks = blocks.filter { it.folder.name == "Pictures" || it.folder.path.contains("Pictures") }

        assertTrue(dcimBlocks.isNotEmpty())
        assertTrue(picBlocks.isNotEmpty())
        // Top level folders have different hue indices
        val dcimHue = dcimBlocks.first().hueIndex
        val picHue = picBlocks.first().hueIndex
        assertTrue(dcimHue != picHue)
    }

    @Test
    fun `layoutTreemap skips tiny rectangles`() {
        val images = listOf(
            "/Huge/huge.jpg" to 1_000_000L,
            "/Tiny/tiny.jpg" to 1L,
        )
        val tree = buildFolderTree(images)
        // With minSize = 2.0 on 100x100 bounds, Tiny gets 1e-4 width which is < 2.0
        val blocks = layoutTreemap(tree, bounds, gap = 0.0, minSize = 2.0)
        assertFalse(blocks.any { it.folder.name == "Tiny" })
    }
}
