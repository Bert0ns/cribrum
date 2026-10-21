// The review screens (docs/design.md §8): categories, a preselected grid per category, and the
// summary before trashing.
package io.github.bert0ns.cribrum.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.model.Category
import io.github.bert0ns.cribrum.model.ImageRef
import io.github.bert0ns.cribrum.review.CategorySummary
import io.github.bert0ns.cribrum.review.ReviewGroup
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
fun CategoryList(categories: List<CategorySummary>, onOpen: (Category) -> Unit, onTrash: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize()) {
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

/** A thumbnail that is marked for the trash or kept. Tap toggles, a long press shows it full size. */
@Composable
private fun Tile(image: ImageRef, marked: Boolean, label: String?, onTap: () -> Unit, onHold: () -> Unit) {
    val description = stringResource(if (marked) R.string.selected_for_trash else R.string.kept)
    Box(
        Modifier.aspectRatio(1f).padding(2.dp)
            .border(if (marked) 3.dp else 0.dp, if (marked) MaterialTheme.colorScheme.error else Color.Transparent)
            .combinedClickable(onClick = onTap, onLongClick = onHold, onClickLabel = description),
    ) {
        AsyncImage(
            model = File(image.path),
            contentDescription = File(image.path).name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (marked) 0.6f else 1f),
        )
        Icon(
            if (marked) Icons.Default.Delete else Icons.Default.Check,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                .background(if (marked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small),
        )
        if (label != null) {
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp),
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
) {
    val haptics = LocalHaptics.current
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
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.weight(1f)) {
            for (group in page) {
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
                        item.image,
                        item.image.path in marked,
                        if (item.isBest) stringResource(R.string.best_copy) else null,
                        onTap = {
                            haptics(Haptic.TICK)
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
                    haptics(Haptic.TICK)
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
            text = { Text(stringResource(R.string.summary_body, size)) },
            confirmButton = {
                TextButton({
                    showSummary = false
                    haptics(if (toTrash.isEmpty()) Haptic.KEEP else Haptic.TRASH)
                    onApply()
                }) { Text(stringResource(R.string.summary_confirm)) }
            },
            dismissButton = { TextButton({ showSummary = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    fullSize?.let { image ->
        FullSize(image, onExcludeFolder = {
            fullSize = null
            haptics(Haptic.KEEP)
            onExcludeFolder(image)
        }) { fullSize = null }
    }
}

/**
 * The image at full size, to tell real blur from a small thumbnail. Pinch to zoom, tap to close.
 * It also offers to never suggest the image's folder again.
 */
@Composable
private fun FullSize(image: ImageRef, onExcludeFolder: () -> Unit, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset += pan
                    }
                }
                .clickable(onClick = onClose),
        ) {
            AsyncImage(
                model = File(image.path),
                contentDescription = File(image.path).name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
            Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                Text(image.path, color = Color.White, style = MaterialTheme.typography.labelSmall)
                TextButton(onExcludeFolder) { Text(stringResource(R.string.exclude_folder)) }
            }
        }
    }
}
