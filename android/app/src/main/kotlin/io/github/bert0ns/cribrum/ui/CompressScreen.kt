// The Compress destination (docs/design.md §8): pick photos and a level, read the summary, watch the batch, undo it.
package io.github.bert0ns.cribrum.ui

import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Scale
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.compress.BatchResult
import io.github.bert0ns.cribrum.compress.BatchState
import io.github.bert0ns.cribrum.compress.CompressibleImage
import io.github.bert0ns.cribrum.compress.CompressionLevel
import io.github.bert0ns.cribrum.compress.GAIN_MAP_SDK
import io.github.bert0ns.cribrum.compress.JpegInfo
import io.github.bert0ns.cribrum.compress.JpegStatus
import io.github.bert0ns.cribrum.compress.Losses
import io.github.bert0ns.cribrum.compress.Marker
import io.github.bert0ns.cribrum.compress.Refusal
import io.github.bert0ns.cribrum.compress.target
import kotlinx.coroutines.launch
import java.io.File

/** How far the before and after zoom, and the longest side Coil decodes them at, so a zoom shows real pixels. */
private const val MAX_ZOOM = 8f
private const val PREVIEW_PIXELS = 2048

@Composable
private fun CompressionLevel.title() = stringResource(
    when (this) {
        CompressionLevel.HIGH -> R.string.compress_level_high
        CompressionLevel.BALANCED -> R.string.compress_level_balanced
        CompressionLevel.SMALLEST -> R.string.compress_level_smallest
    },
)

/** "JPEG 90, full resolution": the numbers come from the level, so the text cannot drift from what is done. */
@Composable
private fun CompressionLevel.detail(): String {
    val limit = maxPixels ?: return stringResource(R.string.compress_level_detail_full, quality)
    return stringResource(R.string.compress_level_detail_limit, quality, (limit / 1_000_000).toInt())
}

@Composable
private fun fileSize(bytes: Long) = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** The stateful page: reads the list when it opens, after a scan and when a batch ends. */
@Composable
fun CompressScreen(model: CompressViewModel, scanning: Boolean, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    val batch by model.batches.state.collectAsStateWithLifecycle()
    val busy = batch is BatchState.Running || batch is BatchState.WaitingForScan
    LaunchedEffect(scanning, busy) { model.refresh() }
    CompressContent(
        state,
        batch,
        hdrSupported = Build.VERSION.SDK_INT >= GAIN_MAP_SDK,
        onToggle = model::toggle,
        onSelectAll = model::selectAll,
        onPage = model::goToPage,
        onCompressedPage = model::goToCompressedPage,
        onLevel = model::chooseLevel,
        onToggleCompressed = model::toggleCompressed,
        onOpenSummary = model::openSummary,
        onCloseSummary = model::closeSummary,
        onConfirm = model::confirm,
        onCancelBatch = model.batches::cancel,
        onUndo = model::undo,
        onDismissResult = model::dismissResult,
        modifier = modifier,
    )
}

@Composable
fun CompressContent(
    state: CompressState,
    batch: BatchState,
    hdrSupported: Boolean,
    onToggle: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onPage: (Int) -> Unit,
    onCompressedPage: (Int) -> Unit,
    onLevel: (CompressionLevel) -> Unit,
    onToggleCompressed: () -> Unit,
    onOpenSummary: () -> Unit,
    onCloseSummary: () -> Unit,
    onConfirm: () -> Unit,
    onCancelBatch: () -> Unit,
    onUndo: (BatchResult) -> Unit,
    onDismissResult: (BatchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        batch is BatchState.WaitingForScan || batch is BatchState.Running -> BatchProgress(batch, onCancelBatch, modifier)
        batch is BatchState.Finished && batch.result != state.dismissed -> BatchResultPanel(batch.result, state.retentionDays, state.undoing, { onUndo(batch.result) }, { onDismissResult(batch.result) }, modifier)
        else -> Picker(state, hdrSupported, onToggle, onSelectAll, onPage, onCompressedPage, onLevel, onToggleCompressed, onOpenSummary, modifier)
    }
    // A summary is never over a batch: its preview is a second full decode next to the batch's.
    if (batch !is BatchState.WaitingForScan && batch !is BatchState.Running) state.summary?.let { SummaryDialog(it, state, onCloseSummary, onConfirm) }
}

@Composable
private fun Picker(
    state: CompressState,
    hdrSupported: Boolean,
    onToggle: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onPage: (Int) -> Unit,
    onCompressedPage: (Int) -> Unit,
    onLevel: (CompressionLevel) -> Unit,
    onToggleCompressed: () -> Unit,
    onOpenSummary: () -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val tick = { haptics.performHapticFeedback(Haptic.TICK.type) }
    val selection = state.selectedPhotos
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var fullSize by remember { mutableStateOf<CompressibleImage?>(null) }
    val shown = state.pagePhotos
    val shownCompressed = state.pageCompressed
    val pages = compressPageCount(state.photos.size)
    val compressedPages = compressPageCount(state.compressed.size)
    // A new page starts at the top of its section. The indices count the grid's items above it.
    val photosAt = (if (state.stayedInTrash > 0) 1 else 0) + 2
    val compressedAt = photosAt + 1 + (if (state.loaded && state.photos.isEmpty()) 1 else 0) + shown.size + (if (pages > 1) 2 else 0)
    fun goTo(at: Int, change: (Int) -> Unit): (Int) -> Unit = { page ->
        tick()
        change(page)
        scope.launch { grid.scrollToItem(at) }
    }
    Column(modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.weight(1f), grid) {
            if (state.stayedInTrash > 0) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "stayed") {
                    Text(
                        pluralStringResource(R.plurals.compress_undo_stayed, state.stayedInTrash, state.stayedInTrash),
                        Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "formats") { SupportedFormats(hdrSupported) }
            item(span = { GridItemSpan(maxLineSpan) }, key = "levels") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.compress_level_heading), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    for (level in CompressionLevel.entries) {
                        LevelRow(level, state.level == level, selection.takeIf { it.isNotEmpty() }?.let { state.estimatedSaving(level) }) {
                            tick()
                            onLevel(level)
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "photos") {
                // Only this page: the photos on the other pages keep the selection they have.
                val all = shown.isNotEmpty() && shown.all { it.path in state.selected }
                Row(Modifier.padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(pluralStringResource(R.plurals.compress_photos_heading, state.photos.size, state.photos.size), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                    if (shown.isNotEmpty()) {
                        TextButton({
                            tick()
                            onSelectAll(!all)
                        }) { Text(stringResource(if (all) R.string.compress_deselect_page else R.string.compress_select_page)) }
                    }
                }
            }
            if (state.loaded && state.photos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "none") { Text(stringResource(R.string.compress_none), Modifier.padding(16.dp)) }
            }
            section(shown, state, state.page, state.photos.size, "photos", goTo(photosAt, onPage), onHold = { fullSize = it }, onToggle = {
                tick()
                onToggle(it)
            })
            if (state.compressed.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "compressed") {
                    CompressedHeader(state.compressed.size, state.compressedExpanded, onToggleCompressed)
                }
                if (state.compressedExpanded) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "compressed-hint") {
                        Text(stringResource(R.string.compress_already_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    section(shownCompressed, state, state.compressedPage, state.compressed.size, "compressed", goTo(compressedAt, onCompressedPage), onHold = { fullSize = it }, onToggle = {
                        tick()
                        onToggle(it)
                    })
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val hint = when {
                selection.isEmpty() -> R.string.compress_hint_select
                state.level == null -> R.string.compress_hint_level
                else -> null
            }
            if (hint != null) Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
            Button(onOpenSummary, Modifier.fillMaxWidth(), enabled = hint == null) {
                Text(pluralStringResource(R.plurals.compress_continue, selection.size, selection.size))
            }
        }
    }
    // A scan or a batch can take the photo off the page while it is open.
    LaunchedEffect(shown, shownCompressed, fullSize) {
        if (fullSize != null && fullSize !in shown && fullSize !in shownCompressed) fullSize = null
    }
    fullSize?.let { start ->
        val photos = if (start in shown) shown else shownCompressed
        FullSize(photos, start, state, onToggle = {
            tick()
            onToggle(it)
        }) { fullSize = null }
    }
}

/** The photos of the page on screen at full size, over the same viewer as the review's (§8). A button selects or deselects the one on screen. */
@Composable
private fun FullSize(photos: List<CompressibleImage>, start: CompressibleImage, state: CompressState, onToggle: (String) -> Unit, onClose: () -> Unit) {
    FullSizeViewer(photos.map { it.path }, start.path, onClose) { page ->
        val photo = photos.getOrNull(page) ?: return@FullSizeViewer // the page emptied under the dialog; the picker closes it next
        val selected = photo.path in state.selected
        val favorite = photo.path in state.favorites
        val before = photo.info.marker?.let { marker -> CompressionLevel.entries.firstOrNull { it.id == marker.level }?.title() }
        if (favorite && selected) FavoriteHeart(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp))
        FullSizeMark(
            if (selected) Icons.Filled.Check else Icons.Filled.Add,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary,
            stringResource(if (selected) R.string.compress_tile_selected else R.string.compress_tile_not_selected),
            stringResource(if (selected) R.string.action_deselect else R.string.action_select),
        ) { onToggle(photo.path) }
        FullSizeBar {
            Text(photo.path, color = Color.White, style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text("${photo.width} × ${photo.height} • ${fileSize(photo.size)}" + if (before != null) " • " + stringResource(R.string.compress_tile_before, before) else "", Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                if (favorite && !selected) FavoriteHeart()
            }
        }
    }
}

/** The page's position, and the way to the next and the previous one. */
@Composable
private fun PageControls(page: Int, total: Int, onPage: (Int) -> Unit) {
    val first = page * COMPRESS_PAGE_SIZE + 1
    val last = minOf(total, first + COMPRESS_PAGE_SIZE - 1)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ onPage(page - 1) }, enabled = page > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.compress_page_previous)) }
        // Polite, so a screen reader says the new position when the page changes.
        Text(
            stringResource(R.string.compress_page_position, first, last, total),
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleSmall,
        )
        IconButton({ onPage(page + 1) }, enabled = page < compressPageCount(total) - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.compress_page_next)) }
    }
}

/** The fixed line under the title. It opens the sheet that says what is supported, and why the rest is not. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupportedFormats(hdrSupported: Boolean) {
    var open by remember { mutableStateOf(false) }
    val line = stringResource(R.string.compress_formats) + if (hdrSupported) "" else " " + stringResource(R.string.compress_formats_no_hdr)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.compress_formats_open), role = Role.Button) { open = true }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(line, style = MaterialTheme.typography.bodyMedium)
    }
    if (open) {
        ModalBottomSheet({ open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.compress_sheet_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.compress_sheet_supported), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.compress_sheet_jpeg))
                Text(stringResource(R.string.compress_sheet_again))
                Text(stringResource(R.string.compress_sheet_unsupported), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.compress_sheet_motion))
                Text(stringResource(R.string.compress_sheet_hdr))
                Text(stringResource(R.string.compress_sheet_extra))
                Text(stringResource(R.string.compress_sheet_other))
                TextButton({ open = false }, Modifier.align(Alignment.End)) { Text(stringResource(R.string.close)) }
            }
        }
    }
}

@Composable
private fun LevelRow(level: CompressionLevel, selected: Boolean, saving: Long?, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(level.title(), style = MaterialTheme.typography.bodyLarge)
            Text(level.detail(), style = MaterialTheme.typography.bodySmall)
        }
        if (saving != null) {
            Text(stringResource(R.string.compress_level_saving, fileSize(saving)), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CompressedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(if (expanded) R.string.compress_collapse else R.string.compress_expand), role = Role.Button, onClick = onToggle)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(pluralStringResource(R.plurals.compress_already_heading, count, count), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.rotate(if (expanded) 180f else 0f))
    }
}

/** The tiles of the page on screen, with the page controls above and below them once the list takes more than one page. */
private fun LazyGridScope.section(
    shown: List<CompressibleImage>,
    state: CompressState,
    page: Int,
    total: Int,
    key: String,
    onPage: (Int) -> Unit,
    onHold: (CompressibleImage) -> Unit,
    onToggle: (String) -> Unit,
) {
    val paged = total > COMPRESS_PAGE_SIZE
    if (paged) item(span = { GridItemSpan(maxLineSpan) }, key = "$key-pages-top") { PageControls(page, total, onPage) }
    items(shown, key = { it.path }) { photo ->
        PhotoTile(photo, photo.path in state.selected, photo.path in state.favorites, onHold = { onHold(photo) }) { onToggle(photo.path) }
    }
    if (paged) item(span = { GridItemSpan(maxLineSpan) }, key = "$key-pages-bottom") { PageControls(page, total, onPage) }
}

/** A thumbnail with its size and resolution. A tap selects it and a long press opens it full screen. A marker adds the level it was compressed at. */
@Composable
private fun PhotoTile(photo: CompressibleImage, selected: Boolean, favorite: Boolean, onHold: () -> Unit, onToggle: () -> Unit) {
    val name = File(photo.path).name
    val size = fileSize(photo.size)
    val resolution = "${photo.width} × ${photo.height}"
    val before = photo.info.marker?.let { marker -> CompressionLevel.entries.firstOrNull { it.id == marker.level }?.title() }
    val parts = buildList {
        add(stringResource(R.string.compress_tile_description, name, resolution, size))
        if (before != null) add(stringResource(R.string.compress_tile_before, before))
        add(stringResource(if (selected) R.string.compress_tile_selected else R.string.compress_tile_not_selected))
        if (favorite) add(stringResource(R.string.favorite))
    }
    val toggle = stringResource(if (selected) R.string.action_deselect else R.string.action_select)
    val open = stringResource(R.string.action_open_full_size)
    Box(
        Modifier.aspectRatio(1f).padding(2.dp)
            .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .combinedClickable(onClick = onToggle, onClickLabel = toggle, onLongClick = onHold, onLongClickLabel = open)
            .semantics {
                role = Role.Checkbox
                this.selected = selected
                contentDescription = parts.joinToString(". ")
                customActions = listOf(
                    CustomAccessibilityAction(toggle) {
                        onToggle()
                        true
                    },
                    CustomAccessibilityAction(open) {
                        onHold()
                        true
                    },
                )
            },
    ) {
        AsyncImage(File(photo.path), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        // The description says all of this, so the visible texts stay out of the screen reader's way.
        Column(Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp).clearAndSetSemantics {}) {
            Text(size, color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text(resolution, color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        if (before != null) {
            Text(
                before,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp).clearAndSetSemantics {},
            )
        }
        // As in the review: the heart sits at the bottom right, and at the top right once selected, beside the check.
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (selected) {
                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small))
                if (favorite) FavoriteHeart(Modifier.clearAndSetSemantics {})
            }
        }
        if (favorite && !selected) FavoriteHeart(Modifier.align(Alignment.BottomEnd).padding(4.dp).clearAndSetSemantics {})
    }
}

@Composable
private fun BatchProgress(batch: BatchState, onCancel: () -> Unit, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (batch) {
                is BatchState.Running -> {
                    val current = (batch.done + 1).coerceAtMost(batch.total)
                    Text(stringResource(R.string.compress_progress, current, batch.total), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { batch.done.toFloat() / batch.total }, Modifier.fillMaxWidth())
                    Text(stringResource(R.string.compress_saved, fileSize(batch.savedBytes)))
                    if (batch.paused) Text(stringResource(R.string.compress_heat_pause))
                }
                else -> {
                    Text(stringResource(R.string.compress_waiting_scan), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        Text(stringResource(R.string.compress_screen_cancel_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton({
            haptics.performHapticFeedback(Haptic.KEEP.type)
            onCancel()
        }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.compress_screen_cancel)) }
    }
}

@Composable
private fun BatchResultPanel(result: BatchResult, retentionDays: Int, undoing: Boolean, onUndo: () -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(pluralStringResource(R.plurals.compress_result_compressed, result.compressed, result.compressed), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.compress_result_saved, fileSize(result.savedBytes)))
            for ((reason, count) in result.refused.filterValues { it > 0 }) {
                Text(pluralStringResource(reason.plural(), count, count), style = MaterialTheme.typography.bodyMedium)
            }
            if (result.notReached > 0) {
                Text(pluralStringResource(R.plurals.compress_not_reached, result.notReached, result.notReached), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (result.compressed > 0) {
            Text(pluralStringResource(R.plurals.compress_result_undo_hint, retentionDays, retentionDays), style = MaterialTheme.typography.bodySmall)
            if (undoing) {
                // It waits for a scan or a batch that runs, which can take minutes, so the page says why nothing happens.
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.compress_undoing))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            Button({
                haptics.performHapticFeedback(Haptic.TICK.type)
                onUndo()
            }, Modifier.fillMaxWidth(), enabled = !undoing) { Text(stringResource(R.string.undo)) }
        }
        OutlinedButton(onDone, Modifier.fillMaxWidth(), enabled = !undoing) { Text(stringResource(R.string.compress_result_done)) }
    }
}

private fun Refusal.plural() = when (this) {
    Refusal.NOT_SMALLER -> R.plurals.compress_refused_not_smaller
    Refusal.LOW_MEMORY -> R.plurals.compress_refused_low_memory
    Refusal.GAIN_MAP_LOST -> R.plurals.compress_refused_gain_map
    Refusal.FAILED -> R.plurals.compress_refused_failed
    Refusal.LEFT_IN_TRASH -> R.plurals.compress_refused_in_trash
}

/** The summary before confirming (§8): the saving, a real before and after, and what the copy loses. */
@Composable
private fun SummaryDialog(summary: Summary, state: CompressState, onClose: () -> Unit, onConfirm: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val count = state.selectedPhotos.size
    val total = state.selectedPhotos.sumOf { it.size }
    val saving = state.estimatedSaving(summary.level)
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(pluralStringResource(R.plurals.compress_summary_title, count, count), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.compress_summary_level, summary.level.title(), summary.level.detail()))
                    Text(stringResource(R.string.compress_summary_saving, fileSize(saving)), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.compress_summary_saving_detail, fileSize(total), fileSize(total - saving)), style = MaterialTheme.typography.bodySmall)
                    BeforeAfter(summary)
                    LossWarning(state, state.lossCounts())
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClose, Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                    Button({
                        // The originals go to the trash, so it is as strong as trashing in the review.
                        haptics.performHapticFeedback(Haptic.TRASH.type)
                        onConfirm()
                    }, Modifier.weight(1f)) { Text(pluralStringResource(R.plurals.compress_summary_confirm, count, count)) }
                }
            }
        }
    }
}

/** The original and a real compression of it at the chosen level, side by side, zoomed together. */
@Composable
private fun BeforeAfter(summary: Summary) {
    val sample = summary.sample
    val zoom = remember { Zoom() }
    val after = target(sample.width, sample.height, summary.level)
    val ratio = (sample.width.toFloat() / sample.height).coerceIn(0.5f, 2f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.compress_preview_heading), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.compress_preview_before, fileSize(sample.size), sample.width, sample.height), style = MaterialTheme.typography.labelMedium)
                ZoomPanel(File(sample.path), zoom, stringResource(R.string.compress_preview_before_description), ratio)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (val preview = summary.preview) {
                    SampleResult.Loading -> {
                        Text(stringResource(R.string.compress_preview_loading), style = MaterialTheme.typography.labelMedium)
                        Box(Modifier.fillMaxWidth().aspectRatio(ratio), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    is SampleResult.Ready -> {
                        Text(stringResource(R.string.compress_preview_after, fileSize(preview.file.length()), after.width, after.height), style = MaterialTheme.typography.labelMedium)
                        ZoomPanel(preview.file, zoom, stringResource(R.string.compress_preview_after_description), ratio)
                    }
                    is SampleResult.Failed -> Text(stringResource(R.string.compress_preview_failed), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text(stringResource(R.string.compress_preview_hint), style = MaterialTheme.typography.bodySmall)
    }
}

/** The zoom both sides of the before and after share, so the same part of the photo shows on both. */
internal class Zoom {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var size = IntSize.Zero

    /** Keeps the point under [centroid] where it is while zooming, then pans, and never shows past the photo's edge. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        // The event that lifts the last finger has no finger that was down before: no centroid, and NaN would reach the layer.
        if (centroid == Offset.Unspecified) return
        val next = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        val moved = centroid - (centroid - offset) * (next / scale) + pan
        scale = next
        offset = Offset(moved.x.coerceIn(size.width * (1 - next), 0f), moved.y.coerceIn(size.height * (1 - next), 0f))
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

/** The preview file keeps its name from one photo to the next, so the file's date is what tells Coil it changed. */
internal fun previewCacheKey(file: File) = "${file.path}:${file.lastModified()}"

@Composable
internal fun ZoomPanel(file: File, zoom: Zoom, description: String, ratio: Float) {
    val state = stringResource(R.string.compress_zoom, (zoom.scale * 100).toInt())
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).clipToBounds()
            .onSizeChanged { zoom.size = it }
            .pointerInput(zoom) { detectTapGestures(onDoubleTap = { zoom.reset() }) }
            // Two fingers, or one once zoomed, so a single finger on a photo at rest still scrolls the summary.
            .pointerInput(zoom) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || zoom.scale > 1f) {
                            zoom.transform(event.calculateCentroid(), event.calculatePan(), event.calculateZoom())
                            event.changes.forEach { if (it.pressed) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .semantics {
                contentDescription = description
                stateDescription = state
            },
    ) {
        AsyncImage(
            ImageRequest.Builder(LocalContext.current).data(file).memoryCacheKey(previewCacheKey(file)).diskCachePolicy(CachePolicy.DISABLED).size(PREVIEW_PIXELS).scale(Scale.FIT).build(),
            null,
            Modifier.fillMaxSize().graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = zoom.scale
                scaleY = zoom.scale
                translationX = zoom.offset.x
                translationY = zoom.offset.y
            },
            contentScale = ContentScale.Fit,
        )
    }
}

/** What the copy loses (§9), with how many of the selected photos each loss touches. */
@Composable
private fun LossWarning(state: CompressState, counts: LossCounts) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.error, MaterialTheme.shapes.medium).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.compress_loss_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        }
        Text(stringResource(R.string.compress_loss_kept))
        for ((count, plural) in listOf(
            counts.reEdit to R.plurals.compress_loss_re_edit,
            counts.depthMap to R.plurals.compress_loss_depth_map,
            counts.xmp to R.plurals.compress_loss_xmp,
            counts.c2pa to R.plurals.compress_loss_c2pa,
        )) {
            if (count > 0) Text("• " + pluralStringResource(plural, count, count))
        }
        Text("• " + stringResource(R.string.compress_loss_shooting_mode))
        Text(pluralStringResource(R.plurals.compress_loss_restore, state.retentionDays, state.retentionDays), fontWeight = FontWeight.Bold)
    }
}

private val previewPhotos = listOf(
    CompressibleImage("/photos/IMG_1.jpg", 24_000_000, 16320, 12240, JpegInfo(JpegStatus.ELIGIBLE, quality = 94, losses = Losses(reEdit = true, xmp = true))),
    CompressibleImage("/photos/IMG_2.jpg", 6_200_000, 4000, 3000, JpegInfo(JpegStatus.ELIGIBLE, quality = 92)),
    CompressibleImage("/photos/IMG_3.jpg", 4_100_000, 4000, 3000, JpegInfo(JpegStatus.ELIGIBLE, quality = 85, marker = Marker(1, "balanced", "2026-11-01T10:00:00Z"))),
)

@Preview(name = "Default", showBackground = true)
@Preview(name = "Narrow (280 dp)", widthDp = 280, showBackground = true)
@Preview(name = "Italian", locale = "it", showBackground = true)
@Preview(name = "Narrow Italian large font", widthDp = 280, locale = "it", fontScale = 1.5f, showBackground = true)
@Composable
private fun CompressPreview() {
    val state = CompressState(
        loaded = true,
        photos = previewPhotos.take(2),
        compressed = previewPhotos.drop(2),
        favorites = setOf(previewPhotos[0].path),
        selected = setOf(previewPhotos[0].path),
        level = CompressionLevel.BALANCED,
        compressedExpanded = true,
    )
    CompressContent(state, BatchState.Idle, hdrSupported = false, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "Second page", showBackground = true)
@Preview(name = "Second page, narrow Italian", widthDp = 280, locale = "it", showBackground = true)
@Composable
private fun PagedPreview() {
    val photos = List(120) { CompressibleImage("/photos/IMG_$it.jpg", 9_000_000L - it, 4000, 3000, JpegInfo(JpegStatus.ELIGIBLE, quality = 92)) }
    val state = CompressState(loaded = true, photos = photos, page = 1, selected = photos.take(60).map { it.path }.toSet(), level = CompressionLevel.BALANCED)
    CompressContent(state, BatchState.Idle, hdrSupported = true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "Summary", showBackground = true)
@Preview(name = "Summary, narrow Italian", widthDp = 280, locale = "it", showBackground = true)
@Composable
private fun SummaryPreview() {
    val state = CompressState(
        loaded = true,
        photos = previewPhotos.take(2),
        selected = previewPhotos.take(2).map { it.path }.toSet(),
        level = CompressionLevel.SMALLEST,
        summary = Summary(previewPhotos[0], CompressionLevel.SMALLEST, SampleResult.Loading),
    )
    CompressContent(state, BatchState.Idle, hdrSupported = true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "Running", showBackground = true)
@Preview(name = "Finished, narrow Italian", widthDp = 280, locale = "it", showBackground = true)
@Composable
private fun BatchPreview() {
    Column {
        BatchProgress(BatchState.Running(3, 12, 412_000_000, paused = true), {}, Modifier.heightIn(max = 300.dp))
        BatchResultPanel(BatchResult(12, 10, mapOf(Refusal.NOT_SMALLER to 2), 1_300_000_000, emptyList()), 30, false, {}, {}, Modifier.heightIn(max = 300.dp))
    }
}
