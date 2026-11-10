package io.github.bert0ns.cribrum.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.bert0ns.cribrum.tracing.traced
import java.io.File

/**
 * [AsyncImage] of [file], with each load a trace section called [section] (android/README.md). It is the request that
 * AsyncImage builds from a file, plus a listener, so it draws and caches the same. The request is remembered: a new one on
 * every recomposition would not be equal to the last and would load again. A [tile] (a grid's cell) decodes with the grid
 * tiles' decoder ([TileDecoder]) and is stored in the memory cache under [tileMemoryKey]. A [fullSize] view loads with Coil's
 * default and shows the file's tile, if the cache still holds it, until its own image arrives.
 */
@Composable
internal fun TracedAsyncImage(
    file: File,
    section: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    tile: Boolean = false,
    fullSize: Boolean = false,
) {
    val context = LocalContext.current
    val request = remember(file, section, tile, context) { fileRequest(context, file, section, tile) }
    AsyncImage(request, contentDescription, modifier, placeholder = if (fullSize) rememberTilePlaceholder(file) else null, alignment = Alignment.Center, contentScale = contentScale)
}

/** The request [TracedAsyncImage] builds for [file]: a [tile]'s is stored in the memory cache under [tileMemoryKey]. */
internal fun fileRequest(context: Context, file: File, section: String, tile: Boolean): ImageRequest {
    val builder = ImageRequest.Builder(context).data(file)
    return (if (tile) builder.tile(section).memoryCacheKey(tileMemoryKey(file).key) else builder.traced(section)).build()
}
