package io.github.bert0ns.cribrum.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.core.content.edit
import androidx.tracing.trace
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.filePath
import coil3.getExtra
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException

/**
 * The grid tiles' thumbnails on disk (docs/design.md §8): the bitmap a tile decoded, as a JPEG, so the next process start
 * decodes 30 to 40 KB instead of the original. Coil keeps no disk cache for local files. [disk] is Coil's own [DiskCache], which
 * evicts the least recently used thumbnails past its cap: the app deletes none of them itself. It lives in the app's cache
 * directory, so Android's "clear cache" empties it and the storage section of the settings counts it.
 */
internal class TileCache(private val disk: DiskCache, private val prefs: SharedPreferences, private val minBytes: Long = MIN_FILE_BYTES) {
    /** A tile request the cache holds: its thumbnail is the source. */
    object Hit : ImageSource.Metadata()

    /** A tile request the cache did not hold: the source is the original, and its decode is stored under [key]. */
    class Miss(private val cache: TileCache, private val key: Key) : ImageSource.Metadata() {
        /** Stores [bitmap], unless the file changed since its length and time were read: the key would then be another file's. */
        fun store(bitmap: Bitmap) {
            if (key.file.length() == key.length && key.file.lastModified() == key.modified) cache.store(key.value, bitmap)
        }
    }

    /** [value] is the [tileKey] of [file], read when it was [length] bytes long and last modified at [modified]. */
    class Key(val value: String, val file: File, val length: Long, val modified: Long)

    /** The key of [file]'s tile at the request's [size], for a file at least [minBytes] long, or null when it has none. */
    fun key(file: File, size: Size, scale: Scale, precision: Precision): Key? {
        val length = file.length()
        if (length < minBytes) return null
        val modified = file.lastModified()
        return tileKey(file.path, length, modified, size, scale, precision)?.let { Key(it, file, length, modified) }
    }

    fun contains(key: Key): Boolean = snapshot(key)?.use { true } ?: false

    /** The source of [key]'s thumbnail, closed with the snapshot, or null when the cache does not hold it. */
    fun source(key: Key): ImageSource? = snapshot(key)?.let { ImageSource(it.data, disk.fileSystem, closeable = it, metadata = Hit) }

    /** A cache that cannot be read is a miss, so the tile decodes the original as it did before the cache. */
    private fun snapshot(key: Key): DiskCache.Snapshot? = try {
        disk.openSnapshot(key.value)
    } catch (_: IOException) {
        null
    }

    /** Writes [bitmap] as [key]'s thumbnail. A failed write leaves no entry, and a second writer of the same key gives way. */
    fun store(key: String, bitmap: Bitmap) {
        val editor = disk.openEditor(key) ?: return
        try {
            val written = disk.fileSystem.write(editor.data) { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, outputStream()) }
            if (written) editor.commit() else editor.abort()
        } catch (_: Exception) {
            editor.abort()
        }
    }

    /** The size of the last Compress tile, in pixels, which the preparing job decodes at. Null before a Compress tile loads. */
    var compressTileSize: Size?
        get() = rememberedSize ?: prefs.getString(COMPRESS_SIZE, null)?.let(::parseSize)?.also { rememberedSize = it }
        set(value) {
            if (value == null || value == compressTileSize) return
            rememberedSize = value
            prefs.edit { putString(COMPRESS_SIZE, "${(value.width as Dimension.Pixels).px}x${(value.height as Dimension.Pixels).px}") }
        }

    @Volatile
    private var rememberedSize: Size? = null

    /**
     * Fetches a tile's file through the cache: its thumbnail on a hit, else the original, as the loader's own fetcher gives it, marked
     * [Miss] for the decoder to store. A file under [minBytes], or a size not in pixels, is the plain original. A `Tile.hit` or
     * `Tile.miss` section, of no length, marks each lookup in the trace.
     */
    class Fetcher(private val uri: Uri, private val options: Options, private val imageLoader: ImageLoader, private val cache: TileCache) : coil3.fetch.Fetcher {
        override suspend fun fetch(): FetchResult? {
            // The loader's components hold Coil's fetchers only: this one is the request's.
            val fetched = imageLoader.components.newFetcher(uri, options, imageLoader)?.first?.fetch()
            val original = fetched as? SourceFetchResult ?: return fetched
            val path = uri.filePath ?: return original
            val key = cache.key(File(path), options.size, options.scale, options.precision) ?: return original
            if (options.getExtra(COMPRESS_TILE)) cache.compressTileSize = options.size
            val hit = cache.source(key)
            if (hit == null) {
                trace("Tile.miss") {}
                val source = ImageSource(original.source.file(), original.source.fileSystem, metadata = Miss(cache, key))
                return SourceFetchResult(source, original.mimeType, original.dataSource)
            }
            trace("Tile.hit") {}
            original.source.close()
            return SourceFetchResult(hit, "image/jpeg", DataSource.DISK)
        }

        /** For the `file:` URIs a [java.io.File] maps to. Any other data goes to Coil's own fetchers. */
        class Factory(private val cache: (Context) -> TileCache) : coil3.fetch.Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): coil3.fetch.Fetcher? = if (data.scheme == "file") Fetcher(data, options, imageLoader, cache(options.context)) else null
        }
    }

    companion object {
        /** About 900 thumbnails of 35 KB, for the 100 photos of the first two Compress pages and what the other grids show. */
        const val MAX_BYTES = 32L * 1024 * 1024

        /** A file this small decodes in a few ms, so its thumbnail would only push larger ones out. */
        const val MIN_FILE_BYTES = 1024L * 1024

        const val QUALITY = 85
        private const val COMPRESS_SIZE = "compress_tile_size"

        /** On the Compress tiles' requests: their size is the one the preparing job decodes at. */
        val COMPRESS_TILE = coil3.Extras.Key(default = false)

        @Volatile
        private var instance: TileCache? = null

        /** The one cache of the process: Coil allows one [DiskCache] for each directory. */
        fun get(context: Context): TileCache = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val disk = DiskCache.Builder().directory(File(app.cacheDir, "tiles").toOkioPath()).maxSizeBytes(MAX_BYTES).build()
                TileCache(disk, app.getSharedPreferences("tiles", Context.MODE_PRIVATE)).also { instance = it }
            }
        }

        val fetcherFactory = Fetcher.Factory(::get)
    }
}

/**
 * The cache key of a tile: the path, the file's length and modification time, so an edited or compressed file (a compressed
 * copy keeps the path and the time and has a new length) gets a key of its own, and the request's size, scale and precision,
 * so the thumbnail decodes to the same bitmap the original did. Null when the size is not in pixels. Coil hashes it into the
 * file name, so no path is written to disk.
 */
internal fun tileKey(path: String, length: Long, modified: Long, size: Size, scale: Scale, precision: Precision): String? {
    val width = (size.width as? Dimension.Pixels)?.px ?: return null
    val height = (size.height as? Dimension.Pixels)?.px ?: return null
    return "tile:$path:$length:$modified:${width}x$height:$scale:$precision"
}

private fun parseSize(text: String): Size? {
    val (width, height) = text.split("x").mapNotNull(String::toIntOrNull).takeIf { it.size == 2 } ?: return null
    return Size(width, height)
}
