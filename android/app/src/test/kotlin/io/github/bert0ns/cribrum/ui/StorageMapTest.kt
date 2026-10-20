package io.github.bert0ns.cribrum.ui

import io.github.bert0ns.cribrum.model.ImageRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageMapTest {
    private val bounds = MapRect(0.0, 0.0, 100.0, 100.0)

    private fun ref(path: String, size: Long) = ImageRef(path, size, 0L)

    @Test
    fun `squarify empty weights returns empty list`() {
        assertEquals(emptyList<MapRect>(), squarify(emptyList(), bounds))
    }

    @Test
    fun `squarify single weight fills bounds`() {
        val rects = squarify(listOf(10.0), bounds)
        assertEquals(1, rects.size)
        assertEquals(0.0, rects[0].left, 1e-6)
        assertEquals(0.0, rects[0].top, 1e-6)
        assertEquals(100.0, rects[0].width, 1e-6)
        assertEquals(100.0, rects[0].height, 1e-6)
    }

    @Test
    fun `squarify areas are proportional to weights`() {
        val weights = listOf(6.0, 6.0, 4.0, 3.0, 2.0, 2.0, 1.0)
        val rects = squarify(weights, bounds)
        assertEquals(weights.size, rects.size)

        val totalWeight = weights.sum()
        val totalArea = bounds.area
        for (i in weights.indices) {
            val expectedArea = totalArea * (weights[i] / totalWeight)
            assertEquals(expectedArea, rects[i].area, 1e-4)
        }
    }

    @Test
    fun `squarify rectangles stay inside bounds`() {
        val weights = listOf(10.0, 20.0, 30.0, 40.0)
        val rects = squarify(weights, bounds)
        for (r in rects) {
            assertTrue(r.left >= bounds.left - 1e-6)
            assertTrue(r.top >= bounds.top - 1e-6)
            assertTrue(r.right <= bounds.right + 1e-6)
            assertTrue(r.bottom <= bounds.bottom + 1e-6)
        }
    }

    @Test
    fun `squarify rectangles do not overlap`() {
        val weights = listOf(10.0, 20.0, 30.0, 40.0)
        val rects = squarify(weights, bounds)
        for (i in 0 until rects.size) {
            for (j in i + 1 until rects.size) {
                val a = rects[i]
                val b = rects[j]
                val horizontalOverlap = maxOf(0.0, minOf(a.right, b.right) - maxOf(a.left, b.left))
                val verticalOverlap = maxOf(0.0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
                val overlapArea = horizontalOverlap * verticalOverlap
                assertTrue(overlapArea < 1e-4)
            }
        }
    }

    @Test
    fun `squarify zero and positive weights`() {
        val weights = listOf(10.0, 0.0, 20.0, 0.0)
        val rects = squarify(weights, bounds)
        assertEquals(4, rects.size)
        assertEquals(0.0, rects[1].area, 1e-6)
        assertEquals(0.0, rects[3].area, 1e-6)
        assertEquals(10000.0 * (10.0 / 30.0), rects[0].area, 1e-4)
        assertEquals(10000.0 * (20.0 / 30.0), rects[2].area, 1e-4)
    }

    @Test
    fun `folder tree sums sizes and counts`() {
        val images = listOf(
            ref("/storage/emulated/0/DCIM/Camera/IMG_001.jpg", 1000L),
            ref("/storage/emulated/0/DCIM/Camera/IMG_002.jpg", 2000L),
            ref("/storage/emulated/0/DCIM/Screenshots/Screenshot_1.png", 500L),
            ref("/storage/emulated/0/Pictures/vacation.jpg", 4000L),
            ref("/storage/emulated/0/Pictures/Sub/photo.jpg", 1500L),
        )
        val tree = buildFolderTree(images)

        assertEquals("0", tree.name)
        assertEquals("/storage/emulated/0", tree.path)
        assertEquals(9000L, tree.size)
        assertEquals(5, tree.imageCount)
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
        val r = MapRect(10.0, 20.0, 50.0, 40.0)
        assertTrue(r.contains(10.0, 20.0))
        assertTrue(r.contains(30.0, 40.0))
        assertTrue(r.contains(60.0, 60.0))
        assertFalse(r.contains(9.9, 20.0))
        assertFalse(r.contains(60.1, 40.0))
    }

    @Test
    fun `layoutTreemap recursive blocks and hues`() {
        val images = listOf(
            ref("/DCIM/Camera/IMG_001.jpg", 3000L),
            ref("/DCIM/Screenshots/scr.png", 1000L),
            ref("/Pictures/vacation.jpg", 6000L),
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
            ref("/Huge/huge.jpg", 1_000_000L),
            ref("/Tiny/tiny.jpg", 1L),
        )
        val tree = buildFolderTree(images)
        // With minSize = 2.0 on 100x100 bounds, Tiny gets 1e-4 width which is < 2.0
        val blocks = layoutTreemap(tree, bounds, gap = 0.0, minSize = 2.0)
        assertFalse(blocks.any { it.folder.name == "Tiny" })
    }

    @Test
    fun `layoutTreemap assigns non-negative hue to direct files when folder has subfolders`() {
        val folder = Folder(
            name = "DCIM",
            path = "/DCIM",
            size = 5000L,
            imageCount = 5,
            children = listOf(
                Folder(name = "Camera", path = "/DCIM/Camera", size = 3000L, imageCount = 3),
            ),
        )
        val blocks = layoutTreemap(folder, bounds, gap = 1.0, minSize = 2.0)
        val directFilesBlock = blocks.first { it.isDirectFiles }
        assertTrue("hueIndex must be >= 0 but was ${directFilesBlock.hueIndex}", directFilesBlock.hueIndex >= 0)
    }

    @Test
    fun `leaf folder is not marked as direct files`() {
        val folder = Folder(
            name = "DCIM",
            path = "/DCIM",
            size = 3000L,
            imageCount = 3,
            children = listOf(
                Folder(name = "Camera", path = "/DCIM/Camera", size = 3000L, imageCount = 3),
            ),
        )
        val blocks = layoutTreemap(folder, bounds, gap = 1.0, minSize = 2.0)
        val cameraBlock = blocks.first { it.folder.name == "Camera" }
        assertFalse("Leaf folder must not be marked as direct files", cameraBlock.isDirectFiles)
    }

    @Test
    fun `tap inside a nested folder hits its top-level folder`() {
        val tree = buildFolderTree(
            listOf(
                ref("/DCIM/Camera/IMG_001.jpg", 3000L),
                ref("/DCIM/Screenshots/scr.png", 1000L),
                ref("/Pictures/vacation.jpg", 6000L),
            ),
        )
        val blocks = layoutTreemap(tree, bounds, gap = 1.0, minSize = 2.0)
        val camera = blocks.first { it.folder.name == "Camera" }
        val hit = topLevelBlockAt(blocks, camera.rect.left + 1.0, camera.rect.top + 1.0)
        assertEquals("DCIM", hit?.folder?.name)
        assertFalse(hit!!.isDirectFiles)
    }
}
