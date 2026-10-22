// The review screens (docs/design.md §8): categories, a preselected grid per category, and the
// summary before trashing.
package io.github.bert0ns.cribrum.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.review.CategorySummary
import io.github.bert0ns.cribrum.review.ReviewGroup
import io.github.bert0ns.cribrum.review.ReviewItem
import io.github.bert0ns.cribrum.review.ReviewOrder
import io.github.bert0ns.cribrum.trash.TrashRetention
import java.io.File

@Composable
fun categoryName(category: Category): String = stringResource(
    when (category) {
        Category.EXACT_DUPLICATE -> R.string.category_exact_duplicate
        Category.NEAR_DUPLICATE -> R.string.category_near_duplicate
        Category.BURST -> R.string.category_burst
        Category.BLURRY -> R.string.category_blurry
        Category.BAD_EXPOSURE -> R.string.category_bad_exposure
        Category.SCREENSHOT -> R.string.category_screenshot
        Category.DOCUMENT -> R.string.category_document
        Category.FORWARDED -> R.string.category_forwarded
        Category.STICKER -> R.string.category_sticker
    },
)

@Composable
fun CategoryList(categories: List<CategorySummary>, freeableBytes: Long, onOpen: (Category) -> Unit, onTrash: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize()) {
        if (freeableBytes > 0) {
            item {
                Text(
                    stringResource(R.string.freeable_total, Formatter.formatShortFileSize(LocalContext.current, freeableBytes)),
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        items(categories, key = { it.category }) { summary ->
            val canOpen = summary.hasDetector && summary.count > 0
            ListItem(
                headlineContent = { Text(categoryName(summary.category)) },
                supportingContent = {
                    Text(
                        if (!summary.hasDetector) {
                            stringResource(R.string.category_coming_soon)
                        } else {
                            val count = pluralStringResource(R.plurals.candidates, summary.count, summary.count)
                            if (summary.count == 0) count else stringResource(R.string.category_line_with_space, count, Formatter.formatShortFileSize(LocalContext.current, summary.bytes))
                        },
                    )
                },
                // Greyed out like a disabled button: there is nothing to open.
                modifier = Modifier.clickable(enabled = canOpen) { onOpen(summary.category) }.alpha(if (canOpen) 1f else 0.5f),
            )
            HorizontalDivider()
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.trash)) },
                leadingContent = { Icon(Icons.Default.Delete, null) },
                modifier = Modifier.clickable(onClick = onTrash),
            )
        }
    }
}

/** The control of the category screen's top bar: one menu with the review order to choose. */
@Composable
fun ReviewOrderMenu(selected: ReviewOrder, options: List<ReviewOrder>, onSelect: (ReviewOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton({ open = true }) { Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.review_order)) }
    DropdownMenu(open, { open = false }) {
        for (grouping in options) {
            DropdownMenuItem(
                text = { Text(stringResource(grouping.title)) },
                leadingIcon = { if (grouping == selected) Icon(Icons.Default.Check, null) },
                onClick = {
                    open = false
                    onSelect(grouping)
                },
            )
        }
    }
}

private val ReviewOrder.title
    get() = when (this) {
        ReviewOrder.OFF -> R.string.review_order_off
        ReviewOrder.DAY -> R.string.review_order_day
        ReviewOrder.WEEK -> R.string.review_order_week
        ReviewOrder.MONTH -> R.string.review_order_month
        ReviewOrder.SIZE -> R.string.review_order_size
    }

/**
 * A thumbnail that is marked for the trash or kept. Tap toggles, a long press shows it full size.
 * A screen reader hears why the image is here and how it is marked, and gets both as actions too.
 */
@Composable
private fun Tile(item: ReviewItem, marked: Boolean, onTap: () -> Unit, onHold: () -> Unit) {
    val state = stringResource(
        when {
            marked -> R.string.selected_for_trash
            item.isBest -> R.string.kept_best_copy
            else -> R.string.kept
        },
    )
    val why = item.reasons.firstNotNullOfOrNull(::reasonOf)?.text() ?: File(item.image.path).name
    val description = stringResource(R.string.tile_description, why, state)
    val toggle = stringResource(if (marked) R.string.action_keep else R.string.action_trash)
    val open = stringResource(R.string.action_open_full_size)
    Box(
        Modifier.aspectRatio(1f).padding(2.dp)
            .border(if (marked) 3.dp else 0.dp, if (marked) MaterialTheme.colorScheme.error else Color.Transparent)
            .combinedClickable(onClick = onTap, onClickLabel = toggle, onLongClick = onHold, onLongClickLabel = open)
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(toggle) {
                        onTap()
                        true
                    },
                    CustomAccessibilityAction(open) {
                        onHold()
                        true
                    },
                )
            },
    ) {
        AsyncImage(
            model = File(item.image.path),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (marked) 0.6f else 1f),
        )
        Icon(
            if (marked) Icons.Default.Delete else Icons.Default.Check,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                .background(if (marked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small),
        )
        if (item.isBest) {
            Text(
                stringResource(R.string.best_copy),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                // The description already says it is the best copy.
                modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp).clearAndSetSemantics {},
            )
        }
    }
}

@Composable
fun CategoryScreen(
    page: List<ReviewGroup>,
    remaining: Int,
    marked: Set<String>,
    grouped: Boolean,
    onToggle: (ImageRef) -> Unit,
    onApply: () -> Unit,
    /** Gets the ids of the groups on screen, so a skip is of what the user saw. */
    onSkip: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    /** Shown right above the button, which a snackbar at the bottom of the screen would cover. */
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onExcludeFolder: (ImageRef) -> Unit = {},
    reviewOrder: ReviewOrder = ReviewOrder.OFF,
    retentionDays: Int = TrashRetention.DEFAULT_DAYS,
) {
    val locale = LocalConfiguration.current.locales[0]
    val haptics = LocalHapticFeedback.current
    var showSummary by remember { mutableStateOf(false) }
    var fullSize by remember { mutableStateOf<ImageRef?>(null) }
    val images = page.flatMap { it.images }
    val toTrash = images.filter { it.image.path in marked }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (images.isNotEmpty()) {
                // Candidates only: a group shows its best copy too, for comparison.
                val shown = images.count { it.suggestTrash }
                val position = if (remaining > shown) R.plurals.page_more else R.plurals.page_last
                Text(pluralStringResource(position, remaining, shown, remaining), style = MaterialTheme.typography.titleSmall)
            }
            Text(stringResource(if (images.isEmpty()) R.string.review_done else R.string.review_hint), style = MaterialTheme.typography.bodySmall)
        }
        // A new grouping reorders the page: start at its top instead of in the middle of it.
        val grid = rememberLazyGridState()
        LaunchedEffect(reviewOrder) { grid.scrollToItem(0) }
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.weight(1f), grid) {
            for ((position, group) in page.withIndex()) {
                if (group.date != null && group.date != page.getOrNull(position - 1)?.date) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "date-${group.id}") {
                        Text(
                            reviewOrder.label(group.date, locale),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                }
                if (grouped) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header-${group.id}") {
                        Column {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            if (group.hasExcludedCopy) {
                                Text(stringResource(R.string.excluded_copy), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp))
                            }
                        }
                    }
                }
                items(group.images, key = { it.image.path }) { item ->
                    Tile(
                        item,
                        item.image.path in marked,
                        onTap = {
                            haptics.performHapticFeedback(Haptic.TICK.type)
                            onToggle(item.image)
                        },
                        onHold = { fullSize = item.image },
                    )
                }
            }
        }
        SnackbarHost(snackbar)
        if (images.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showSummary = true }, Modifier.weight(1f)) {
                    Text(stringResource(R.string.apply, toTrash.size, images.size - toTrash.size))
                }
                OutlinedButton(onClick = {
                    haptics.performHapticFeedback(Haptic.TICK.type)
                    onSkip(page.map { it.id }.toSet())
                }) { Text(stringResource(R.string.skip)) }
            }
        }
    }
    if (showSummary) {
        val size = Formatter.formatShortFileSize(LocalContext.current, toTrash.sumOf { it.image.size })
        AlertDialog(
            onDismissRequest = { showSummary = false },
            title = { Text(pluralStringResource(R.plurals.summary_title, toTrash.size, toTrash.size)) },
            text = { Text(pluralStringResource(R.plurals.summary_body, retentionDays, size, retentionDays)) },
            confirmButton = {
                TextButton({
                    showSummary = false
                    haptics.performHapticFeedback((if (toTrash.isEmpty()) Haptic.KEEP else Haptic.TRASH).type)
                    onApply()
                }) { Text(stringResource(R.string.summary_confirm)) }
            },
            dismissButton = { TextButton({ showSummary = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    fullSize?.let { start ->
        FullSize(images, start, marked, onExcludeFolder = {
            fullSize = null
            haptics.performHapticFeedback(Haptic.KEEP.type)
            onExcludeFolder(it)
        }) { fullSize = null }
    }
}

/**
 * The images of the list at full size, to tell real blur from a small thumbnail. Swipe to page,
 * pinch to zoom, and drag to pan once zoomed. Tap to close. A chip says how the grid marks the
 * image, and the view offers to never suggest the image's folder again.
 */
@Composable
private fun FullSize(items: List<ReviewItem>, start: ImageRef, marked: Set<String>, onExcludeFolder: (ImageRef) -> Unit, onClose: () -> Unit) {
    val pager = rememberPagerState(items.indexOfFirst { it.image == start }.coerceAtLeast(0)) { items.size }
    val current = items[pager.currentPage]
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize().testTag("fullSize"), key = { items[it].image.path }) { page ->
                ZoomableImage(items[page].image, onClose)
            }
            val isMarked = current.image.path in marked
            Text(
                stringResource(
                    if (isMarked) {
                        R.string.selected_for_trash
                    } else if (current.isBest) {
                        R.string.kept_best_copy
                    } else {
                        R.string.kept
                    },
                ),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)
                    .background(if (isMarked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
                for (reason in current.reasons.mapNotNull(::reasonOf)) {
                    Text(reason.text(), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
                Text(current.image.path, color = Color.White, style = MaterialTheme.typography.labelSmall)
                TextButton(
                    onClick = { onExcludeFolder(current.image) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                ) { Text(stringResource(R.string.exclude_folder)) }
            }
        }
    }
}

/** Pinch zooms. A drag pans only while zoomed, so at scale 1 it is left to the pager to change page. */
@Composable
private fun ZoomableImage(image: ImageRef, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = File(image.path),
        contentDescription = File(image.path).name,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        if (zoom != 1f || scale > 1f) {
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .clickable(onClick = onClose)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
