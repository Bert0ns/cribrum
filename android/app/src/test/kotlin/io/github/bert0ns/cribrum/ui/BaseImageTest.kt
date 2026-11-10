package io.github.bert0ns.cribrum.ui

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/** What a full size view's base image is, and where the tiles take over from it (docs/design.md §8). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class BaseImageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A grey JPEG of [width] by [height] pixels, written block by block: a 200 MP file is 800 KB and nothing holds its pixels. */
    private fun grey(name: String, width: Int, height: Int): File = File(folder.root, name).also { file ->
        file.outputStream().buffered().use { writeGreyJpeg(it, width, height) }
    }

    /** The base image of [file] as the view requests it, decoded with [decoder] unless that is null: Coil's own choice. */
    private fun base(file: File, decoder: BitmapFactoryDecoder.Factory? = null): IntSize {
        val request = baseRequest(context, file, previewCacheKey(file), "TiledImage.base").let { built ->
            if (decoder == null) built else built.newBuilder().decoderFactory(decoder).build()
        }
        val result = runBlocking { ImageLoader.Builder(context).build().execute(request) } as SuccessResult
        val bitmap = (result.image as BitmapImage).bitmap
        println("base image of a ${file.name} photo: ${bitmap.width} x ${bitmap.height}, ${bitmap.allocationByteCount / 1024 / 1024} MB")
        return IntSize(bitmap.width, bitmap.height)
    }

    @Test
    fun `the base image of a 200 MP photo is 2048 px on its long side`() {
        val photo = grey("200mp.jpg", 16320, 12240)
        assertEquals(IntSize(2048, 1536), base(photo))
    }

    @Test
    fun `the fallback decoder gives the same size`() {
        val photo = grey("200mp.jpg", 16320, 12240)
        assertEquals(IntSize(2048, 1536), base(photo, BitmapFactoryDecoder.Factory()))
    }

    @Test
    fun `a portrait photo is limited on its height`() {
        assertEquals(IntSize(1536, 2048), base(grey("portrait.jpg", 6000, 8000)))
    }

    @Test
    fun `a photo smaller than the base is not enlarged`() {
        assertEquals(IntSize(1200, 800), base(grey("small.jpg", 1200, 800)))
    }

    /**
     * The base image has [BASE] px on its long side, a photo of [raw] fitted in a panel [view] wide has [fit] screen pixels for each of
     * its pixels, and the tiles are there from the zoom where the screen needs more pixels than the base holds, up to the zoom
     * the view stops at.
     */
    @Test
    fun `the tiles take over from the base image as soon as the screen needs more than it holds, and reach full detail`() {
        for (raw in listOf(IntSize(16320, 12240), IntSize(8000, 6000), IntSize(4000, 3000), IntSize(3000, 4000), IntSize(9000, 2000))) {
            val view = IntSize(1080, 1440)
            val zoom = Zoom(raw).apply { size = view }
            val fit = zoom.share // the share at scale 1
            val baseShare = BASE.toFloat() / maxOf(raw.width, raw.height)
            var scale = 1f
            while (scale <= 40f) {
                val tiles = visibleTiles(raw, 1, view, scale, Offset(view.width * (1 - scale) / 2, view.height * (1 - scale) / 2)) // zoomed about the middle
                val needed = fit * scale
                if (needed > baseShare) {
                    // The base holds fewer pixels than the screen shows: tiles, and as fine as the screen's pixels or the photo's own.
                    assertFalse("$raw at $scale needs tiles", tiles.isEmpty())
                    assertTrue("$raw at $scale", 1f / tiles.first().sample >= minOf(needed, 1f)) // the photo's own pixels are the finest there are
                } else if (scale > 1f) {
                    // The base is enough: no tiles, so a zoom that the base covers decodes nothing.
                    assertTrue("$raw at $scale needs none", tiles.isEmpty() || 1f / tiles.first().sample > baseShare)
                }
                scale += 0.05f
            }
            // The deepest zoom of the view is two screen pixels for each photo pixel: the tiles are the photo's own pixels.
            zoom.scale = 1f
            zoom.transform(Offset(view.width / 2f, view.height / 2f), Offset.Zero, 1000f)
            if (maxOf(raw.width, raw.height) > BASE) {
                assertEquals("$raw at the deepest zoom", 1, visibleTiles(raw, 1, view, zoom.scale, zoom.offset).first().sample)
            }
        }
    }

    private companion object {
        const val BASE = 2048
    }
}

/**
 * A baseline JPEG of one grey component, [width] by [height] pixels, that is all one grey. Its two Huffman tables hold one code
 * each, '0', for a zero difference of the DC value and for the end of the block, so a block is two zero bits.
 */
internal fun writeGreyJpeg(out: OutputStream, width: Int, height: Int) {
    fun segment(marker: Int, body: ByteArray) {
        out.write(0xFF)
        out.write(marker)
        out.write((body.size + 2) shr 8)
        out.write((body.size + 2) and 0xFF)
        out.write(body)
    }
    fun table(kind: Int): ByteArray = ByteArrayOutputStream().apply {
        write(kind)
        write(1) // one code of length 1
        repeat(15) { write(0) }
        write(0) // its symbol: a DC difference of 0, or the end of a block
    }.toByteArray()
    out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
    segment(0xDB, ByteArray(65) { if (it == 0) 0 else 1 }) // quantisation table 0, all ones
    segment(0xC0, byteArrayOf(8, (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(), 1, 1, 0x11, 0))
    segment(0xC4, table(0x00)) // DC table 0
    segment(0xC4, table(0x10)) // AC table 0
    segment(0xDA, byteArrayOf(1, 1, 0x00, 0, 63, 0))
    val blocks = ((width + 7) / 8).toLong() * ((height + 7) / 8)
    val bytes = ByteArray(1 shl 16)
    var left = (blocks * 2 + 7) / 8 // two bits a block
    while (left > 0) {
        val n = minOf(left, bytes.size.toLong()).toInt()
        out.write(bytes, 0, n)
        left -= n
    }
    out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
}
