package io.github.bert0ns.cribrum.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.memory.MemoryCache
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class FullSizeImagesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tiles get() = SingletonImageLoader.get(context).memoryCache!!

    @Before
    fun emptyCache() = tiles.clear()

    private fun photo(name: String, width: Int = 400, height: Int = 300): File {
        val bitmap = Bitmap.createBitmap(IntArray(width * height) { Color.rgb(it % 256, it / 256 % 256, 7) }, width, height, Bitmap.Config.ARGB_8888)
        return File(folder.root, name).apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
    }

    private fun store(file: File) = tiles.set(tileMemoryKey(file), MemoryCache.Value(Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).asImage()))

    @Test
    fun `a tile's key is its path and time, so an edited file is another tile`() {
        val file = photo("a.jpg")
        val key = tileMemoryKey(file)
        assertNotEquals(key, tileMemoryKey(photo("b.jpg")))
        file.setLastModified(file.lastModified() + 5000)
        assertNotEquals(key, tileMemoryKey(file))
    }

    @Test
    fun `a grid tile is stored in the memory cache under its key, and a full size image is not`() {
        val file = photo("a.jpg")
        val loader = ImageLoader.Builder(context).build()
        runBlocking { loader.execute(fileRequest(context, file, "Compress.tile", tile = true)) }
        assertNotNull(loader.memoryCache!![tileMemoryKey(file)])
        loader.memoryCache!!.clear()
        runBlocking { loader.execute(fileRequest(context, file, "FullSize.image", tile = false)) }
        assertNull(loader.memoryCache!![tileMemoryKey(file)])
    }

    @Test
    fun `the placeholder is the tile the memory cache holds, and none once the file changed or the tile is gone`() {
        val file = photo("a.jpg")
        assertNull(tilePlaceholder(context, file))
        store(file)
        assertNotNull(tilePlaceholder(context, file))
        assertNull(tilePlaceholder(context, photo("other.jpg")))
        file.setLastModified(file.lastModified() + 5000)
        assertNull(tilePlaceholder(context, file))
        file.setLastModified(file.lastModified() - 5000)
        tiles.clear()
        assertNull(tilePlaceholder(context, file))
        assertEquals(0, tiles.size)
    }

    @Test
    fun `a full size image goes to the cache of its own loader and leaves the grid's tiles alone`() {
        val file = photo("a.jpg")
        store(file)
        val before = tiles.keys.toSet()
        val loader = FullSizeLoader.get(context)
        assertNotSame(tiles, loader.memoryCache)
        assertEquals(FullSizeLoader.MAX_BYTES, loader.memoryCache!!.maxSize)
        runBlocking { loader.execute(baseRequest(context, file, previewCacheKey(file), "TiledImage.base")) }
        runBlocking { loader.execute(fileRequest(context, file, "FullSize.image", tile = false)) }
        assertEquals(before, tiles.keys)
        assertNotNull(loader.memoryCache!![MemoryCache.Key(previewCacheKey(file))])
        FullSizeLoader.clear()
        assertEquals(0, loader.memoryCache!!.size)
        assertEquals(before, tiles.keys)
    }

    @Test
    fun `a prefetched base image is the one the view finds in the cache`() {
        val file = photo("next.jpg", 3000, 2000)
        val loader = FullSizeLoader.get(context)
        FullSizeLoader.clear()
        runBlocking { loader.execute(prefetchRequest(context, file)) }
        val result = runBlocking { loader.execute(baseRequest(context, file, previewCacheKey(file), "TiledImage.base")) } as SuccessResult
        assertEquals(DataSource.MEMORY_CACHE, result.dataSource)
        assertEquals(2048, (result.image as BitmapImage).bitmap.width)
    }
}
