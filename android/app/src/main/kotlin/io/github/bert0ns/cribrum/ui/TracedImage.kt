package io.github.bert0ns.cribrum.ui

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
 * every recomposition would not be equal to the last and would load again.
 */
@Composable
internal fun TracedAsyncImage(
    file: File,
    section: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val request = remember(file, section, context) { ImageRequest.Builder(context).data(file).traced(section).build() }
    AsyncImage(request, contentDescription, modifier, alignment = Alignment.Center, contentScale = contentScale)
}
