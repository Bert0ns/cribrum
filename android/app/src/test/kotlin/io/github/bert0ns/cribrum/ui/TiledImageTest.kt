package io.github.bert0ns.cribrum.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

private val photo200 = IntSize(16320, 12240)
private val panel = IntSize(540, 405)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TiledImageTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a photo that is not zoomed needs no tiles`() {
        assertEquals(emptyList<Tile>(), visibleTiles(photo200, 1, panel, 1f, Offset.Zero))
    }

    @Test
    fun `at full resolution the tiles cover the visible pixels of the photo`() {
        // 540 / 16320 times this is one screen pixel per pixel, and the offset shows the photo from (8000, 6000).
        val tiles = visibleTiles(photo200, 1, panel, 16320f / 540, Offset(-8000f, -6000f))

        assertEquals(
            listOf(
                Tile(1, IntRect(7680, 5632, 8192, 6144)),
                Tile(1, IntRect(8192, 5632, 8704, 6144)),
                Tile(1, IntRect(7680, 6144, 8192, 6656)),
                Tile(1, IntRect(8192, 6144, 8704, 6656)),
            ),
            tiles,
        )
    }

    @Test
    fun `a smaller zoom decodes fewer pixels, at a power of two`() {
        // Five pixels of the photo per screen pixel: sampled by 4, so the tile shows at least as much as the screen does.
        val tiles = visibleTiles(photo200, 1, panel, 16320f / 540 / 5, Offset.Zero)

        assertEquals(listOf(Tile(4, IntRect(0, 0, 2048, 2048)), Tile(4, IntRect(2048, 0, 4096, 2048))), tiles)
    }

    @Test
    fun `the last tile stops at the edge of the photo`() {
        val scale = 16320f / 540
        val tiles = visibleTiles(photo200, 1, panel, scale, Offset(540 * (1 - scale), 0f))

        assertEquals(listOf(Tile(1, IntRect(15360, 0, 15872, 512)), Tile(1, IntRect(15872, 0, 16320, 512))), tiles)
    }

    @Test
    fun `a photo turned by its EXIF orientation is read from its own pixels`() {
        // 4000 x 3000 in the file, turned 90 degrees clockwise to 3000 x 4000. The top left of the screen is the file's bottom left.
        val tiles = visibleTiles(IntSize(4000, 3000), 6, IntSize(300, 400), 10f, Offset.Zero)

        assertEquals(listOf(Tile(1, IntRect(0, 2560, 512, 3000))), tiles)
    }

    @Test
    fun `the bands beside a photo narrower than the panel are not part of it`() {
        // 4000 x 3000 fits a 600 x 300 panel at 400 x 300, 100 pixels from the left; the zoom shows the photo's first 600 pixels.
        val tiles = visibleTiles(IntSize(4000, 3000), 1, IntSize(600, 300), 10f, Offset(-1000f, 0f))

        assertEquals(listOf(Tile(1, IntRect(0, 0, 512, 512)), Tile(1, IntRect(512, 0, 1024, 512))), tiles)
    }

    @Test
    fun `the zoom reads as a share of the original's full resolution`() {
        val zoom = Zoom(photo200).apply { size = panel }

        assertEquals(540f / 16320, zoom.share, 1e-6f)
        zoom.transform(Offset(270f, 200f), Offset.Zero, 10f)
        assertEquals(5400f / 16320, zoom.share, 1e-6f)
    }

    @Test
    fun `the zoom goes up to twice the original's full resolution`() {
        val zoom = Zoom(photo200).apply { size = panel }

        zoom.transform(Offset(270f, 200f), Offset.Zero, 1000f)

        assertEquals(2f, zoom.share, 1e-4f)
    }

    @Test
    fun `a tile decodes at its sample size`() {
        val file = File(folder.root, "photo.jpg")
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val source = TileSource.open(file)!!

        val tile = source.decode(Tile(2, IntRect(1024, 512, 1200, 900)))

        assertEquals(IntSize(1200, 900), source.raw)
        assertEquals(88 to 194, tile!!.width to tile.height)
        source.close()
    }

    @Test
    fun `a file that is not an image gives no tiles`() {
        val file = File(folder.root, "photo.jpg").apply { writeText("not a jpeg") }

        assertNull(TileSource.open(file))
        assertNull(TileSource.open(File(folder.root, "missing.jpg")))
    }
}
