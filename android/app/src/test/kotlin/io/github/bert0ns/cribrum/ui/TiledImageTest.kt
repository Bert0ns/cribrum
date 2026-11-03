package io.github.bert0ns.cribrum.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `a zoom the base image still covers needs no tiles`() {
        // Twice the fitted size asks for 15 pixels of the photo per screen pixel, sampled by 8: the 2048 px base gives 8 too.
        assertEquals(emptyList<Tile>(), visibleTiles(photo200, 1, panel, 2f, Offset.Zero))
        // Under 8 pixels per screen pixel the tiles start, sampled by 4.
        assertEquals(4, visibleTiles(photo200, 1, panel, 16320f / 540 / 7.9f, Offset.Zero).first().sample)
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

    /** [visibleRegion] as shares of the file's width and height. */
    private fun share(raw: IntSize, orientation: Int, view: IntSize, scale: Float, offset: Offset) = visibleRegion(raw, orientation, view, scale, offset).let { listOf(it.left / raw.width, it.top / raw.height, it.right / raw.width, it.bottom / raw.height) }

    private fun assertSame(expected: List<Float>, actual: List<Float>) = expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-4f) }

    @Test
    fun `an original and its copy at half the size show the same part of the photo`() {
        val zoom = 12f
        val offset = Offset(-3000f, -2000f)

        assertSame(share(photo200, 1, panel, zoom, offset), share(IntSize(8160, 6120), 1, panel, zoom, offset))
        // Turned by EXIF, in a portrait panel.
        assertSame(share(photo200, 6, IntSize(405, 540), zoom, offset), share(IntSize(8160, 6120), 6, IntSize(405, 540), zoom, offset))
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
    fun `a region decodes at its sample size`() {
        val file = File(folder.root, "photo.jpg")
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val source = TileSource.open(file)!!

        val region = source.decode(IntRect(1024, 512, 1200, 900), 2)

        assertEquals(IntSize(1200, 900), source.raw)
        assertEquals(88 to 194, region!!.width to region.height)
        source.close()
    }

    /** Records what the loader asks for, and fails the decodes [failing] says. */
    internal class FakeDecoder(var failing: Boolean = false) : RegionDecoder {
        override val raw = photo200
        override val orientation = 1
        val events = mutableListOf<String>()
        var during: () -> Unit = {}

        override fun decode(rect: IntRect, sample: Int): Bitmap? {
            events += "decode $rect at $sample"
            during()
            if (failing) return null
            return Bitmap.createBitmap((rect.width + sample - 1) / sample, (rect.height + sample - 1) / sample, Bitmap.Config.ARGB_8888)
        }

        override fun close() {
            events += "close"
        }
    }

    private fun grid(sample: Int, columns: IntRange, rows: IntRange) = rows.flatMap { row ->
        columns.map { column -> Tile(sample, IntRect(column * 512 * sample, row * 512 * sample, (column + 1) * 512 * sample, (row + 1) * 512 * sample)) }
    }

    private fun loading(decoder: FakeDecoder, first: List<Tile>, maxBoxBytes: Long = Long.MAX_VALUE, test: suspend TestScope.(MutableStateFlow<List<Tile>>, Map<Tile, Bitmap>, Job) -> Unit) = runTest {
        val views = MutableStateFlow(first)
        val tiles = mutableMapOf<Tile, Bitmap>()
        val job = launch { TileLoader(decoder, tiles, maxBoxBytes).load(views) }
        runCurrent()
        test(views, tiles, job)
        job.cancel()
    }

    @Test
    fun `the missing tiles of a view are read in one decode and cut into the grid`() {
        val decoder = FakeDecoder()
        loading(decoder, grid(1, 0..1, 0..1)) { views, tiles, _ ->
            assertEquals(listOf("decode ${IntRect(0, 0, 1024, 1024)} at 1"), decoder.events)
            assertEquals(grid(1, 0..1, 0..1).toSet(), tiles.keys)
            assertTrue(tiles.values.all { it.width == 512 && it.height == 512 })

            // A pan by one column keeps the column still on screen, drops the other, and reads only the new one.
            views.value = grid(1, 1..2, 0..1)
            runCurrent()

            assertEquals("decode ${IntRect(1024, 0, 1536, 1024)} at 1", decoder.events.last())
            assertEquals(2, decoder.events.size)
            assertEquals(grid(1, 1..2, 0..1).toSet(), tiles.keys)
        }
    }

    @Test
    fun `a box too large for memory is read a row at a time, the centre row first`() {
        val decoder = FakeDecoder()
        loading(decoder, grid(1, 0..0, 0..2), maxBoxBytes = 512L * 512 * 4) { _, tiles, _ ->
            assertEquals(
                listOf("decode ${IntRect(0, 512, 512, 1024)} at 1", "decode ${IntRect(0, 0, 512, 512)} at 1", "decode ${IntRect(0, 1024, 512, 1536)} at 1"),
                decoder.events,
            )
            assertEquals(3, tiles.size)
        }
    }

    @Test
    fun `a coarser tile stays under the finer ones until they are loaded`() {
        val decoder = FakeDecoder()
        loading(decoder, grid(2, 0..0, 0..0)) { views, tiles, _ ->
            var meanwhile = emptySet<Tile>()
            decoder.during = { meanwhile = tiles.keys.toSet() }

            views.value = grid(1, 0..1, 0..1)
            runCurrent()

            assertEquals(grid(2, 0..0, 0..0).toSet(), meanwhile)
            assertEquals(grid(1, 0..1, 0..1).toSet(), tiles.keys)
        }
    }

    @Test
    fun `a failed decode clears the tiles and the next view tries again`() {
        val decoder = FakeDecoder()
        loading(decoder, grid(1, 0..0, 0..0)) { views, tiles, _ ->
            decoder.failing = true
            views.value = grid(1, 0..1, 0..0)
            runCurrent()
            assertEquals(emptyMap<Tile, Bitmap>(), tiles)

            decoder.failing = false
            views.value = grid(1, 1..2, 0..0)
            runCurrent()

            assertEquals(grid(1, 1..2, 0..0).toSet(), tiles.keys)
            assertTrue("close" !in decoder.events)
        }
    }

    @Test
    fun `leaving the view stops the work and closes the decoder once`() {
        val decoder = FakeDecoder()
        loading(decoder, grid(1, 0..0, 0..0)) { views, _, job ->
            job.cancel()
            runCurrent()
            views.value = grid(1, 1..1, 0..0)
            runCurrent()

            assertEquals(listOf("decode ${IntRect(0, 0, 512, 512)} at 1", "close"), decoder.events)
        }
    }

    @Test
    fun `a file that is not an image gives no tiles`() {
        val file = File(folder.root, "photo.jpg").apply { writeText("not a jpeg") }

        assertNull(TileSource.open(file))
        assertNull(TileSource.open(File(folder.root, "missing.jpg")))
    }
}
