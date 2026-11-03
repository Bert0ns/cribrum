// The Compress destination (docs/design.md §8): pick photos and a level, read the summary, watch the batch, undo it.
package io.github.bert0ns.cribrum.ui

import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
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
        batch is BatchState.Finished && batch.result != state.dismissed -> BatchResultPanel(batch.result, state.retentionDays, { onUndo(batch.result) }, { onDismissResult(batch.result) }, modifier)
        else -> Picker(state, hdrSupported, onToggle, onSelectAll, onLevel, onToggleCompressed, onOpenSummary, modifier)
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
    onLevel: (CompressionLevel) -> Unit,
    onToggleCompressed: () -> Unit,
    onOpenSummary: () -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val tick = { haptics.performHapticFeedback(Haptic.TICK.type) }
    val selection = state.selectedPhotos
    Column(modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.weight(1f)) {
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
                val all = state.photos.isNotEmpty() && state.photos.all { it.path in state.selected }
                Row(Modifier.padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(pluralStringResource(R.plurals.compress_photos_heading, state.photos.size, state.photos.size), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                    if (state.photos.isNotEmpty()) {
                        TextButton({
                            tick()
                            onSelectAll(!all)
                        }) { Text(stringResource(if (all) R.string.deselect_all else R.string.select_all)) }
                    }
                }
            }
            if (state.loaded && state.photos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "none") { Text(stringResource(R.string.compress_none), Modifier.padding(16.dp)) }
            }
            tiles(state.photos, state, onToggle = {
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
                    tiles(state.compressed, state, onToggle = {
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

private fun LazyGridScope.tiles(photos: List<CompressibleImage>, state: CompressState, onToggle: (String) -> Unit) {
    items(photos, key = { it.path }) { photo ->
        PhotoTile(photo, photo.path in state.selected, photo.path in state.favorites) { onToggle(photo.path) }
    }
}

/** A thumbnail with its size and resolution. A tap selects it. A marker adds the level it was compressed at. */
@Composable
private fun PhotoTile(photo: CompressibleImage, selected: Boolean, favorite: Boolean, onToggle: () -> Unit) {
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
    Box(
        Modifier.aspectRatio(1f).padding(2.dp)
            .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .selectable(selected, role = Role.Checkbox, onClick = onToggle)
            .semantics {
                contentDescription = parts.joinToString(". ")
                customActions = listOf(
                    CustomAccessibilityAction(toggle) {
                        onToggle()
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
private fun BatchResultPanel(result: BatchResult, retentionDays: Int, onUndo: () -> Unit, onDone: () -> Unit, modifier: Modifier) {
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
            Button({
                haptics.performHapticFeedback(Haptic.TICK.type)
                onUndo()
            }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.undo)) }
        }
        OutlinedButton(onDone, Modifier.fillMaxWidth()) { Text(stringResource(R.string.compress_result_done)) }
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
private class Zoom {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var size = IntSize.Zero

    /** Keeps the point under [centroid] where it is while zooming, then pans, and never shows past the photo's edge. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val next = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        val moved = centroid - (centroid - offset) * (next / scale) + pan
        scale = next
        offset = Offset(moved.x.coerceIn(size.width * (1 - next), 0f), moved.y.coerceIn(size.height * (1 - next), 0f))
    }
}

/** The preview file keeps its name from one photo to the next, so the file's date is what tells Coil it changed. */
internal fun previewCacheKey(file: File) = "${file.path}:${file.lastModified()}"

@Composable
private fun ZoomPanel(file: File, zoom: Zoom, description: String, ratio: Float) {
    val state = stringResource(R.string.compress_zoom, (zoom.scale * 100).toInt())
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).clipToBounds()
            .onSizeChanged { zoom.size = it }
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
    CompressContent(state, BatchState.Idle, hdrSupported = false, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
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
    CompressContent(state, BatchState.Idle, hdrSupported = true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "Running", showBackground = true)
@Preview(name = "Finished, narrow Italian", widthDp = 280, locale = "it", showBackground = true)
@Composable
private fun BatchPreview() {
    Column {
        BatchProgress(BatchState.Running(3, 12, 412_000_000, paused = true), {}, Modifier.heightIn(max = 300.dp))
        BatchResultPanel(BatchResult(12, 10, mapOf(Refusal.NOT_SMALLER to 2), 1_300_000_000, emptyList()), 30, {}, {}, Modifier.heightIn(max = 300.dp))
    }
}
