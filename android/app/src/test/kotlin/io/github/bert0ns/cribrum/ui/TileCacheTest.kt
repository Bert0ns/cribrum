package io.github.bert0ns.cribrum.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.Uri
import coil3.disk.DiskCache
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TileCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val disk by lazy { DiskCache.Builder().directory(folder.newFolder("tiles").toOkioPath()).maxSizeBytes(TileCache.MAX_BYTES).build() }
    private val cache by lazy { TileCache(disk, minBytes = 0) }
    private val loader by lazy { ImageLoader.Builder(context).build() }

    /** A noisy photo-like JPEG, so it is no smaller than its thumbnail. */
    private fun photo(name: String, width: Int = 400, height: Int = 300, seed: Int = 1): File {
        val random = Random(seed)
        val pixels = IntArray(width * height) { Color.rgb(random.nextInt(256), it % width * 255 / width, it / width * 255 / height) }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return File(folder.root, name).apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
    }

    private fun load(file: File, size: Int = 100, target: TileCache = cache) = runBlocking {
        val builder = ImageRequest.Builder(context).data(file)
            .fetcherFactory(TileCache.Fetcher.Factory { target }, Uri::class).decoderFactory(TileDecoder.factory)
            .size(size, size).scale(Scale.FILL).precision(Precision.INEXACT)
            .memoryCachePolicy(CachePolicy.DISABLED).allowHardware(false)
        loader.execute(builder.build())
    }

    private fun key(file: File, size: Int = 100) = cache.key(file, Size(size, size), Scale.FILL, Precision.INEXACT)!!

    private val SuccessResult.bitmap get() = (image as BitmapImage).bitmap

    /** Replaces the file's bytes and keeps its length and time, which no camera does: a hit then cannot read the original. */
    private fun scramble(file: File) {
        val modified = file.lastModified()
        file.writeBytes(ByteArray(file.length().toInt()) { 0x42 })
        file.setLastModified(modified)
    }

    @Test
    fun `the key changes with the length, the time and the tile's size, and needs a size in pixels`() {
        val key = tileKey("/a.jpg", 10, 20, Size(100, 100), Scale.FILL, Precision.INEXACT)
        assertNotEquals(key, tileKey("/a.jpg", 11, 20, Size(100, 100), Scale.FILL, Precision.INEXACT))
        assertNotEquals(key, tileKey("/a.jpg", 10, 21, Size(100, 100), Scale.FILL, Precision.INEXACT))
        assertNotEquals(key, tileKey("/a.jpg", 10, 20, Size(120, 120), Scale.FILL, Precision.INEXACT))
        assertNotEquals(key, tileKey("/b.jpg", 10, 20, Size(100, 100), Scale.FILL, Precision.INEXACT))
        assertNull(tileKey("/a.jpg", 10, 20, Size(Dimension.Undefined, Dimension.Pixels(100)), Scale.FILL, Precision.INEXACT))
    }

    @Test
    fun `a miss stores the tile, and a hit decodes it to the same bitmap without the original`() {
        val file = photo("a.jpg")
        val first = load(file) as SuccessResult
        assertTrue(cache.contains(key(file)))

        scramble(file)
        val second = load(file) as SuccessResult
        assertEquals(first.bitmap.width, second.bitmap.width)
        assertEquals(first.bitmap.height, second.bitmap.height)
        assertEquals(133, second.bitmap.width)
        assertEquals(100, second.bitmap.height)
        // Sampled, as the original's decode was, so the memory cache never takes it for the whole photo.
        assertTrue(first.isSampled)
        assertTrue(second.isSampled)
    }

    @Test
    fun `a compressed copy, with the same path and time and a new length, misses`() {
        val file = photo("a.jpg")
        load(file)
        val modified = file.lastModified()
        photo("copy.jpg", width = 300, height = 225, seed = 2).copyTo(file, overwrite = true)
        file.setLastModified(modified)
        assertFalse(cache.contains(key(file)))

        scramble(file)
        assertTrue(load(file) is ErrorResult)
    }

    @Test
    fun `a tile of another size misses`() {
        val file = photo("a.jpg")
        load(file, size = 100)
        assertFalse(cache.contains(key(file, size = 120)))
    }

    @Test
    fun `an image no larger than the tile is not stored`() {
        val file = photo("small.jpg", width = 80, height = 60)
        load(file)
        assertFalse(cache.contains(key(file)))
    }

    @Test
    fun `an image with alpha is not stored, since a JPEG has none`() {
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        val file = File(folder.root, "sticker.png").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        assertTrue(load(file) is SuccessResult)
        assertFalse(cache.contains(key(file)))
    }

    @Test
    fun `a file under the cache's floor is neither looked up nor stored`() {
        val file = photo("a.jpg")
        val floored = TileCache(disk, minBytes = file.length() + 1)
        assertNull(floored.key(file, Size(100, 100), Scale.FILL, Precision.INEXACT))
        assertTrue(load(file, target = floored) is SuccessResult)
        assertFalse(cache.contains(key(file)))
    }
}
