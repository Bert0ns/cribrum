// Storage map screen (docs/design.md §8): WinDirStat-style treemap of explored folders.
package io.github.bert0ns.cribrum.ui

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bert0ns.cribrum.R

private const val SATURATION = 0.6f
private const val DIMMED_ALPHA = 0.35f

@Composable
private fun summary(size: Long, imageCount: Int): String = pluralStringResource(
    R.plurals.storage_map_summary,
    imageCount,
    Formatter.formatShortFileSize(LocalContext.current, size),
    imageCount,
)

@Composable
fun StorageMapScreen(
    currentFolder: Folder,
    onZoomIn: (Folder) -> Unit,
    modifier: Modifier = Modifier,
    onExclude: (Folder) -> Unit = {},
    /** The map is at its root: with nothing selected, excluding its folder would hide every image. */
    atRoot: Boolean = false,
    baseHue: Float = ROOT_HUE,
) {
    val density = LocalDensity.current
    val selectionColor = MaterialTheme.colorScheme.onSurface
    var selectedBlock by remember(currentFolder) { mutableStateOf<TreemapBlock?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                val selected = selectedBlock
                val targetFolder = selected?.folder ?: currentFolder
                val isDirectFiles = selected != null && selected.isDirectFiles

                val headline = targetFolder.path.ifEmpty { stringResource(R.string.storage_map_root) }
                val size = if (isDirectFiles) targetFolder.directSize else targetFolder.size
                val count = if (isDirectFiles) targetFolder.directImageCount else targetFolder.imageCount
                val summary = summary(size, count)

                val details = if (isDirectFiles) {
                    stringResource(R.string.storage_map_card_details_direct, summary)
                } else {
                    stringResource(R.string.storage_map_card_details, summary)
                }

                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The direct files block has no folder of its own: excluding it would hide the subfolders too.
                if (targetFolder.path.isNotEmpty() && !isDirectFiles && !(atRoot && selected == null)) {
                    TextButton({ onExclude(targetFolder) }) { Text(stringResource(R.string.storage_map_exclude)) }
                }
            }
        }

        if (currentFolder.imageCount <= 0) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.storage_map_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                val widthPx = with(density) { maxWidth.toPx().toDouble() }
                val heightPx = with(density) { maxHeight.toPx().toDouble() }
                val gapPx = with(density) { 1.5.dp.toPx().toDouble() }
                val minSizePx = with(density) { 3.dp.toPx().toDouble() }

                val blocks = remember(currentFolder, baseHue, widthPx, heightPx) {
                    layoutTreemap(
                        folder = currentFolder,
                        bounds = MapRect(0.0, 0.0, widthPx, heightPx),
                        gap = gapPx,
                        minSize = minSizePx,
                        baseHue = baseHue,
                    )
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("storage_map")
                        .pointerInput(blocks) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val hit = topLevelBlockAt(blocks, offset.x.toDouble(), offset.y.toDouble())
                                    if (hit != null && hit.isSameBlockAs(selectedBlock) && !hit.isDirectFiles && hit.folder != currentFolder) {
                                        onZoomIn(hit.folder)
                                    } else {
                                        selectedBlock = if (hit?.isSameBlockAs(selectedBlock) == true) null else hit
                                    }
                                },
                            )
                        },
                ) {
                    // The selection keeps the old pixel rect when the map is laid out again.
                    val selected = blocks.firstOrNull { it.depth == 0 && it.isSameBlockAs(selectedBlock) }
                    for (block in blocks) {
                        val rect = block.rect
                        val r = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                        val topLeft = Offset(rect.left.toFloat(), rect.top.toFloat())
                        val size = Size(rect.width.toFloat(), rect.height.toFloat())

                        val lightness = (0.50f + block.depth * 0.08f).coerceAtMost(0.85f)
                        val color = Color.hsl(block.hue, SATURATION, lightness)
                            .copy(alpha = if (selected == null || selected.rect.encloses(rect)) 1f else DIMMED_ALPHA)

                        drawRoundRect(
                            color = color,
                            topLeft = topLeft,
                            size = size,
                            cornerRadius = r,
                            style = Fill,
                        )
                    }

                    if (selected != null) {
                        // Drawn last and inset by half the stroke, so no block covers it or clips it.
                        val stroke = 4.dp.toPx()
                        val rect = selected.rect
                        drawRoundRect(
                            color = selectionColor,
                            topLeft = Offset(rect.left.toFloat() + stroke / 2, rect.top.toFloat() + stroke / 2),
                            size = Size(
                                (rect.width.toFloat() - stroke).coerceAtLeast(0f),
                                (rect.height.toFloat() - stroke).coerceAtLeast(0f),
                            ),
                            cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                            style = Stroke(width = stroke),
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            val subfolders = currentFolder.children
            if (subfolders.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.storage_map_no_subfolders),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(subfolders) { index, folder ->
                        val iconColor = Color.hsl(childHue(baseHue, index), SATURATION, 0.50f)
                        ListItem(
                            headlineContent = { Text(folder.name) },
                            supportingContent = { Text(summary(folder.size, folder.imageCount)) },
                            leadingContent = {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .background(iconColor, RoundedCornerShape(4.dp)),
                                )
                            },
                            modifier = Modifier.clickable {
                                onZoomIn(folder)
                            },
                        )
                    }
                }
            }
        }
    }
}
