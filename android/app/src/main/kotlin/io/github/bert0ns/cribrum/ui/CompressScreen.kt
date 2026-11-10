// The Compress destination (docs/design.md §8): pick photos and a level, read the summary, watch the batch, undo it.
package io.github.bert0ns.cribrum.ui

import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
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
import androidx.compose.ui.semantics.onClick
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
import io.github.bert0ns.cribrum.pipeline.Indexing
import io.github.bert0ns.cribrum.pipeline.ScanProgress
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

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
fun CompressScreen(model: CompressViewModel, progress: ScanProgress?, modifier: Modifier = Modifier) {
    val state by model.state.collectAsStateWithLifecycle()
    val batch by model.batches.state.collectAsStateWithLifecycle()
    val busy = batch is BatchState.Running || batch is BatchState.WaitingForScan
    LaunchedEffect(progress != null, busy) { model.refresh() }
    CompressContent(
        state,
        batch,
        hdrSupported = Build.VERSION.SDK_INT >= GAIN_MAP_SDK,
        onToggle = model::toggle,
        onSelectAll = model::selectAll,
        onPage = model::goToPage,
        onCompressedPage = model::goToCompressedPage,
        onLevel = model::chooseLevel,
        onLevelSettled = model::remakePreviews,
        onToggleCompressed = model::toggleCompressed,
        onOpenSummary = model::openSummary,
        onCloseSummary = model::closeSummary,
        onConfirm = model::confirm,
        onCancelBatch = model.batches::cancel,
        onUndo = model::undo,
        onDismissResult = model::dismissResult,
        progress = progress,
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
    /** The running scan, which a waiting batch shows: scanning itself lives on Clean. */
    progress: ScanProgress? = null,
    /** The finger left the level slider, or a screen reader stepped it: the summary makes its previews again then. */
    onLevelSettled: () -> Unit = {},
) {
    val busy = batch is BatchState.WaitingForScan || batch is BatchState.Running
    Column(modifier) {
        val body = Modifier.weight(1f)
        when {
            busy -> BatchProgress(batch, progress, onCancelBatch, body)
            batch is BatchState.Finished && batch.result != state.dismissed -> BatchResultPanel(batch.result, batch.favoritesLost, state.retentionDays, state.undoing, { onUndo(batch.result) }, { onDismissResult(batch.result) }, body)
            else -> Picker(state, hdrSupported, onToggle, onSelectAll, onPage, onCompressedPage, onLevel, onToggleCompressed, onOpenSummary, body)
        }
    }
    // A summary is never over a batch: its preview is a second full decode next to the batch's.
    if (!busy) state.summary?.let { SummaryDialog(it, state, onLevel, onLevelSettled, onCloseSummary, onConfirm) }
}

/** The room under the grid for the floating Continue button: its 16 dp margins around a 40 dp button, and some slack for a large font. */
private val CONTINUE_CLEARANCE = 96.dp

@OptIn(ExperimentalLayoutApi::class)
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
    val photosAt = (if (state.stayedInTrash > 0) 1 else 0) + 1
    val compressedAt = photosAt + 1 + (if (state.loaded && state.photos.isEmpty()) 1 else 0) + shown.size + (if (pages > 1) 2 else 0)
    fun goTo(at: Int, change: (Int) -> Unit): (Int) -> Unit = { page ->
        tick()
        change(page)
        scope.launch { grid.scrollToItem(at) }
    }
    // The button floats over the grid with nothing behind it. The grid keeps room under its last row so the pager buttons scroll clear of it.
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize(), grid, contentPadding = PaddingValues(bottom = CONTINUE_CLEARANCE)) {
            if (state.stayedInTrash > 0) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "stayed") {
                    Text(
                        pluralStringResource(R.plurals.compress_undo_stayed, state.stayedInTrash, state.stayedInTrash),
                        Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // Nothing to act on: the reason, and the way to read what is supported.
            if (state.loaded && state.photos.isEmpty() && state.compressed.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "none") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.compress_none))
                        SupportedFormats(hdrSupported) { open -> TextButton(open) { Text(stringResource(R.string.compress_formats_open)) } }
                    }
                }
                return@LazyVerticalGrid
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "levels") {
                LevelSlider(state.level, selection.takeIf { it.isNotEmpty() }?.let { state.estimatedSaving(state.level) }, Modifier.padding(horizontal = 16.dp)) {
                    tick()
                    onLevel(it)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "photos") {
                // Only this page: the photos on the other pages keep the selection they have.
                val all = shown.isNotEmpty() && shown.all { it.path in state.selected }
                // The button goes under the count when both do not fit on one line, as with a large font.
                FlowRow(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pluralStringResource(R.plurals.compress_photos_heading, state.photos.size, state.photos.size), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                        SupportedFormats(hdrSupported) { open ->
                            IconButton(open) { Icon(Icons.Filled.Info, stringResource(R.string.compress_formats_open), tint = MaterialTheme.colorScheme.primary) }
                        }
                    }
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
        // Hidden rather than disabled: a disabled button is see-through, and over the photos it reads as clutter.
        if (selection.isNotEmpty()) {
            Button(onOpenSummary, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
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
    val image: @Composable (Int) -> Unit = image@{ page ->
        val photo = photos.getOrNull(page) ?: return@image
        val zoom = remember(photo.path) { Zoom(IntSize(photo.width, photo.height)) }
        TiledImage(
            File(photo.path),
            zoom,
            Modifier.fillMaxSize().zoomGestures(zoom, onTap = onClose).semantics {
                contentDescription = File(photo.path).name
                onClick {
                    onClose()
                    true
                }
            },
        )
    }
    FullSizeViewer(photos.map { it.path }, start.path, onClose, image) { page ->
        val photo = photos.getOrNull(page) ?: return@FullSizeViewer // the page emptied under the dialog; the picker closes it next
        val selected = photo.path in state.selected
        val favorite = photo.path in state.favorites
        val before = photo.info.marker?.let { marker -> CompressionLevel.entries.firstOrNull { it.id == marker.level }?.title() }
        FullSizeToggle(selected, stringResource(R.string.tile_selected), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary) { onToggle(photo.path) }
        FullSizeBar(photo.path, favorite) {
            Text("${photo.width} × ${photo.height} • ${fileSize(photo.size)}" + if (before != null) " • " + stringResource(R.string.compress_tile_before, before) else "", color = Color.White, style = MaterialTheme.typography.bodyMedium)
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

/**
 * The sheet that says what is compressed and what is not, in two short lists, behind the control [opener] draws: an
 * info icon beside the count, or a link on an empty page. The HDR line names the phone's case only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupportedFormats(hdrSupported: Boolean, opener: @Composable (open: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    opener { open = true }
    if (open) {
        ModalBottomSheet({ open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.compress_sheet_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.compress_sheet_supported), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                for (line in listOfNotNull(R.string.compress_sheet_jpeg, R.string.compress_sheet_hdr_yes.takeIf { hdrSupported }, R.string.compress_sheet_again)) {
                    Text("• " + stringResource(line))
                }
                Text(stringResource(R.string.compress_sheet_unsupported), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                for (line in listOfNotNull(R.string.compress_sheet_motion, R.string.compress_sheet_hdr.takeIf { !hdrSupported }, R.string.compress_sheet_extra, R.string.compress_sheet_other)) {
                    Text("• " + stringResource(line))
                }
                TextButton({ open = false }, Modifier.align(Alignment.End)) { Text(stringResource(R.string.close)) }
            }
        }
    }
}

/**
 * The three levels on one slider, High quality at the left and Smallest at the right (§8). Material's `Slider` with
 * one step in the middle snaps to the three stops and brings the semantics: the range, the action that increment and
 * decrement send, and a state description that names the level.
 */
@Composable
private fun LevelSlider(level: CompressionLevel, saving: Long?, modifier: Modifier = Modifier, onSettled: () -> Unit = {}, onLevel: (CompressionLevel) -> Unit) {
    val heading = stringResource(R.string.compress_level_heading)
    val state = stringResource(R.string.compress_level_state, level.title(), level.detail())
    // The level's name and the saving on one line, then the slider: the JPEG numbers are for the screen reader only.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(level.title(), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (saving != null) Text(stringResource(R.string.compress_level_saving, fileSize(saving)), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = level.ordinal.toFloat(),
            // Only a new stop is reported, so the caller's tick comes once per stop even while the finger stays on it.
            onValueChange = { value -> CompressionLevel.entries[value.roundToInt().coerceIn(0, 2)].let { if (it != level) onLevel(it) } },
            onValueChangeFinished = onSettled,
            valueRange = 0f..2f,
            steps = 1,
            modifier = Modifier.semantics {
                contentDescription = heading
                stateDescription = state
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The ends are named as the levels are, so a tile's "Smallest" is a stop the slider has.
            Text(CompressionLevel.HIGH.title(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(CompressionLevel.SMALLEST.title(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
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

/** A thumbnail with its size. A tap selects it and a long press opens it full screen. A marker adds the level it was compressed at. */
@Composable
private fun PhotoTile(photo: CompressibleImage, selected: Boolean, favorite: Boolean, onHold: () -> Unit, onToggle: () -> Unit) {
    val name = File(photo.path).name
    val size = fileSize(photo.size)
    val resolution = "${photo.width} × ${photo.height}"
    val before = photo.info.marker?.let { marker -> CompressionLevel.entries.firstOrNull { it.id == marker.level }?.title() }
    val parts = buildList {
        add(stringResource(R.string.compress_tile_description, name, resolution, size))
        if (before != null) add(stringResource(R.string.compress_tile_before, before))
        add(stringResource(if (selected) R.string.tile_selected else R.string.compress_tile_not_selected))
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
        TracedAsyncImage(File(photo.path), "Compress.tile", null, Modifier.fillMaxSize(), tile = true)
        // The description says all of this, so the visible texts stay out of the screen reader's way.
        // The size only: the resolution is in the full size view and the summary.
        Text(size, Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp).clearAndSetSemantics {}, color = Color.White, style = MaterialTheme.typography.labelSmall)
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
                SelectedCheck()
                if (favorite) FavoriteHeart(Modifier.clearAndSetSemantics {})
            }
        }
        if (favorite && !selected) FavoriteHeart(Modifier.align(Alignment.BottomEnd).padding(4.dp).clearAndSetSemantics {})
    }
}

@Composable
private fun BatchProgress(batch: BatchState, progress: ScanProgress?, onCancel: () -> Unit, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    Column(modifier.fillMaxSize().readableWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    // What the batch waits for: the scan's phase, which says how far it is.
                    if (progress != null) Text(Indexing.phaseText(LocalResources.current, progress.step) + " · " + stringResource(Indexing.phaseTitle(progress.step)))
                }
            }
        }
        // Only a running batch has a photo in progress to wait for.
        if (batch is BatchState.Running) Text(stringResource(R.string.compress_screen_cancel_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton({
            haptics.performHapticFeedback(Haptic.KEEP.type)
            onCancel()
        }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.compress_screen_cancel)) }
    }
}

@Composable
private fun BatchResultPanel(result: BatchResult, favoritesLost: Int, retentionDays: Int, undoing: Boolean, onUndo: () -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val haptics = LocalHapticFeedback.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).readableWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(pluralStringResource(R.plurals.compress_result_compressed, result.compressed, result.compressed), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.compress_result_saved, fileSize(result.savedBytes)))
            for ((reason, count) in result.refused.filterValues { it > 0 }) {
                Text(pluralStringResource(reason.plural(), count, count), style = MaterialTheme.typography.bodyMedium)
            }
            if (result.notReached > 0) {
                Text(pluralStringResource(R.plurals.compress_not_reached, result.notReached, result.notReached), style = MaterialTheme.typography.bodyMedium)
            }
            if (favoritesLost > 0) {
                Text(pluralStringResource(R.plurals.compress_favorites_lost, favoritesLost, favoritesLost), style = MaterialTheme.typography.bodyMedium)
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
        }
        // Done is the filled button: in Italian "Annulla" also reads as Cancel, and undoing the batch is the rare choice.
        Buttons(Modifier.fillMaxWidth()) {
            Button(onDone, enabled = !undoing) { Text(stringResource(R.string.compress_result_done)) }
            if (result.compressed > 0) {
                OutlinedButton({
                    haptics.performHapticFeedback(Haptic.TICK.type)
                    onUndo()
                }, enabled = !undoing) { Text(stringResource(R.string.compress_result_undo)) }
            }
        }
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
private fun SummaryDialog(summary: Summary, state: CompressState, onLevel: (CompressionLevel) -> Unit, onLevelSettled: () -> Unit, onClose: () -> Unit, onConfirm: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val count = state.selectedPhotos.size
    ClearFullSizeImagesOnExit()
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).readableWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(pluralStringResource(R.plurals.compress_summary_title, count, count), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    // The level can change here too, next to its effect. The slider follows the finger, and the previews are made again
                    // only once it lets go: each is a full decode, and one started at each stop on the way would block the next.
                    LevelSlider(state.level, state.estimatedSaving(state.level), onSettled = onLevelSettled) {
                        haptics.performHapticFeedback(Haptic.TICK.type)
                        onLevel(it)
                    }
                    BeforeAfter(summary)
                    LossWarning(state, state.lossCounts())
                }
                Buttons(Modifier.readableWidth().padding(16.dp)) {
                    Button({
                        // The originals go to the trash, so it is as strong as trashing in the review.
                        haptics.performHapticFeedback(Haptic.TRASH.type)
                        onConfirm()
                    }) { Text(pluralStringResource(R.plurals.compress_summary_confirm, count, count)) }
                    OutlinedButton(onClose) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

/** The original and a real compression of it at the chosen level, under one draggable line, for each photo the summary compares. */
@Composable
private fun BeforeAfter(summary: Summary) {
    val haptics = LocalHapticFeedback.current
    val samples = summary.samples
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val current = tab.coerceIn(0, samples.lastIndex)
    val sample = samples[current]
    val photo = sample.photo
    val line = remember { mutableFloatStateOf(0.5f) }
    // A photo keeps its zoom while the user looks at another.
    val zooms = remember(samples.map { it.photo.path }) { samples.map { Zoom(IntSize(it.photo.width, it.photo.height)) } }
    val zoom = zooms[current]
    val after = target(photo.width, photo.height, summary.level)
    val ratio = (photo.width.toFloat() / photo.height).coerceIn(0.5f, 2f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (samples.size > 1) {
            PrimaryTabRow(current) {
                samples.forEachIndexed { index, item ->
                    val before = fileSize(item.photo.size)
                    val description = when (val preview = item.preview) {
                        SampleResult.Loading -> stringResource(R.string.compress_compare_tab_loading, index + 1, samples.size, before)
                        is SampleResult.Ready -> stringResource(R.string.compress_compare_tab_ready, index + 1, samples.size, before, fileSize(preview.file.length()))
                        is SampleResult.Failed -> stringResource(R.string.compress_compare_tab_failed, index + 1, samples.size, before)
                    }
                    Tab(
                        index == current,
                        {
                            if (index != current) {
                                haptics.performHapticFeedback(Haptic.TICK.type)
                                tab = index
                            }
                        },
                        Modifier.semantics { contentDescription = description },
                        text = { Text(stringResource(R.string.compress_compare_tab, index + 1), Modifier.clearAndSetSemantics {}) },
                    )
                }
            }
        }
        Text(stringResource(R.string.compress_preview_before, fileSize(photo.size), photo.width, photo.height), style = MaterialTheme.typography.labelMedium)
        when (val preview = sample.preview) {
            SampleResult.Loading -> {
                Text(stringResource(R.string.compress_preview_loading), style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is SampleResult.Ready -> Text(stringResource(R.string.compress_preview_after, fileSize(preview.file.length()), after.width, after.height), style = MaterialTheme.typography.labelMedium)
            is SampleResult.Failed -> Text(stringResource(R.string.compress_preview_failed), style = MaterialTheme.typography.bodyMedium)
        }
        // Tiles wait until every preview is made: a zoom decoding next to an encode would slow it, and take memory its check counts on.
        CompareSlider(File(photo.path), (sample.preview as? SampleResult.Ready)?.file, zoom, line, ratio, tiled = samples.none { it.preview is SampleResult.Loading })
    }
}

@Composable
internal fun zoomLabel(zoom: Zoom) = stringResource(R.string.compress_zoom, (zoom.share * 100).roundToInt())

/**
 * What the copy loses (§9), with how many of the selected photos each loss touches. Every copy loses the shooting mode
 * info, which matters to almost nobody, so the box is neutral, and red with a warning only when a real loss is listed.
 */
@Composable
private fun LossWarning(state: CompressState, counts: LossCounts) {
    val losses = listOf(
        counts.reEdit to R.plurals.compress_loss_re_edit,
        counts.depthMap to R.plurals.compress_loss_depth_map,
        counts.xmp to R.plurals.compress_loss_xmp,
        counts.c2pa to R.plurals.compress_loss_c2pa,
    ).filter { it.first > 0 }
    val color = if (losses.isEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
    Column(
        Modifier.fillMaxWidth().border(1.dp, color, MaterialTheme.shapes.medium).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (losses.isNotEmpty()) Icon(Icons.Filled.Warning, contentDescription = null, tint = color)
            Text(stringResource(R.string.compress_loss_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        }
        Text(stringResource(R.string.compress_loss_kept), style = MaterialTheme.typography.bodyMedium)
        if (losses.isNotEmpty()) Text(stringResource(R.string.compress_loss_also), style = MaterialTheme.typography.bodyMedium)
        for ((count, plural) in losses) {
            Text("• " + pluralStringResource(plural, count, count), style = MaterialTheme.typography.bodyMedium)
        }
        Text(pluralStringResource(R.plurals.compress_loss_restore, state.retentionDays, state.retentionDays), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
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
@Preview(name = "Summary, narrow Italian large font", widthDp = 280, locale = "it", fontScale = 1.5f, showBackground = true)
@Composable
private fun SummaryPreview() {
    val state = CompressState(
        loaded = true,
        photos = previewPhotos.take(2),
        selected = previewPhotos.take(2).map { it.path }.toSet(),
        level = CompressionLevel.SMALLEST,
        summary = Summary(CompressionLevel.SMALLEST, listOf(Sample(previewPhotos[0], SampleResult.Ready(File("/photos/preview.jpg"))), Sample(previewPhotos[1], SampleResult.Loading), Sample(previewPhotos[2], SampleResult.Loading))),
    )
    CompressContent(state, BatchState.Idle, hdrSupported = true, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "Running", showBackground = true)
@Preview(name = "Finished, narrow Italian", widthDp = 280, locale = "it", showBackground = true)
@Composable
private fun BatchPreview() {
    Column {
        BatchProgress(BatchState.Running(3, 12, 412_000_000, paused = true), null, {}, Modifier.heightIn(max = 300.dp))
        BatchResultPanel(BatchResult(12, 10, mapOf(Refusal.NOT_SMALLER to 2), 1_300_000_000, emptyList()), 0, 30, false, {}, {}, Modifier.heightIn(max = 300.dp))
    }
}
