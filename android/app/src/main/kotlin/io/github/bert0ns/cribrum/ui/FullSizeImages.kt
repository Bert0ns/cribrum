// How the full size views get their images (docs/design.md §8): a loader of their own, and the grid's tile as the placeholder.
package io.github.bert0ns.cribrum.ui

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.tracing.trace
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.memory.MemoryCache
import coil3.request.Disposable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The key a grid tile of [file] is stored under in Coil's memory cache, and the one a full size view looks it up by. It is
 * the path, the length and the modification time, as the tile's disk key is ([tileKey]): a compressed copy keeps the path
 * and the time and has a new length, so it is another tile. Coil 3 keeps the tile's size, scale and precision out of an
 * explicit key and checks them against the request instead, so the lookup of a placeholder needs none of them.
 */
internal fun tileMemoryKey(file: File) = MemoryCache.Key("tile:${file.path}:${file.length()}:${file.lastModified()}")

/**
 * [file]'s grid tile, if Coil's memory cache still holds it, drawn like any image. The lookup also marks the tile as in
 * use. A `FullSize.placeholder.hit` or `.miss` section, of no length, marks it in the trace.
 */
internal fun tilePlaceholder(context: Context, file: File): Painter? {
    val tile = SingletonImageLoader.get(context).memoryCache?.get(tileMemoryKey(file))?.image
    trace(if (tile != null) "FullSize.placeholder.hit" else "FullSize.placeholder.miss") {}
    return tile?.asPainter(context)
}

/** [tilePlaceholder] of [file], looked up once while it stays the same file. */
@Composable
internal fun rememberTilePlaceholder(file: File): Painter? {
    val context = LocalContext.current
    return remember(context, file) { tilePlaceholder(context, file) }
}

/**
 * The loader of the full size views and of the summary's before and after. It is Coil's own loader with a memory cache of its
 * own, so what these views show never pushes the grids' tiles out of the default cache: a base image is up to 12.6 MB, and
 * Coil drops the least recently used images, which, under a full size view, are the tiles of the grid behind it. Back then
 * finds the grid as it was.
 */
internal object FullSizeLoader {
    /** Three base images of a 4:3 photo at 2,048 px, 12.6 MB each: the one on screen, the next and the last. */
    const val MAX_BYTES = 40L * 1024 * 1024

    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: SingletonImageLoader.get(context).newBuilder().memoryCache(MemoryCache.Builder().maxSizeBytes(MAX_BYTES).build()).build().also { instance = it }
    }

    /** The prefetches still loading, by the memory cache key they store under. */
    private val prefetches = ConcurrentHashMap<String, Disposable>()

    /**
     * Loads [file]'s base image into this loader's cache as the view will ask for it, so a swipe to it finds it there. A
     * prefetch of the same file that is still loading is not started again.
     */
    fun prefetch(context: Context, file: File): Disposable {
        val key = previewCacheKey(file)
        prefetches[key]?.let { return it }
        return get(context).enqueue(prefetchRequest(context, file)).also { started ->
            prefetches[key] = started
            started.job.invokeOnCompletion { prefetches.remove(key, started) }
        }
    }

    /** Whether a prefetch of [file] is still loading. */
    fun prefetching(file: File) = prefetches.containsKey(previewCacheKey(file))

    /** Waits for the prefetch of [file], if one is loading. It also returns when the prefetch fails or is stopped. */
    suspend fun awaitPrefetch(file: File) {
        prefetches[previewCacheKey(file)]?.job?.join()
    }

    /** Stops the prefetches and lets go of the images it holds, but not of the ones a screen is drawing. */
    fun clear() {
        prefetches.values.forEach { it.dispose() }
        prefetches.clear()
        instance?.memoryCache?.clear()
    }
}

/**
 * Empties the [FullSizeLoader]'s cache when the caller leaves the screen, so a closed view holds no memory. A rotation or a
 * fold recreates the activity and keeps the view open, so it keeps the cache.
 */
@Composable
internal fun ClearFullSizeImagesOnExit() {
    val activity = LocalActivity.current
    DisposableEffect(activity) { onDispose { if (activity?.isChangingConfigurations != true) FullSizeLoader.clear() } }
}
