// How the full size views get their images (docs/design.md §8): the grid's tile as the placeholder.
package io.github.bert0ns.cribrum.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.tracing.trace
import coil3.SingletonImageLoader
import coil3.compose.asPainter
import coil3.memory.MemoryCache
import java.io.File

/**
 * The key a grid tile of [file] is stored under in Coil's memory cache, and the one a full size view looks it up by. It is
 * the path and the modification time, as Coil's own key for a file is, so an edited file is another tile. Coil 3 keeps the
 * tile's size, scale and precision out of an explicit key and checks them against the request instead, so the lookup of a
 * placeholder needs none of them.
 */
internal fun tileMemoryKey(file: File) = MemoryCache.Key("tile:${file.path}:${file.lastModified()}")

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
