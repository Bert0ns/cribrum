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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.bert0ns.cribrum.R

@Composable
fun StorageMapScreen(
    currentFolder: Folder,
    onZoomIn: (Folder) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
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
                val imageSizes = stringResource(R.string.storage_map_image_sizes)

                val details = listOfNotNull(
                    if (isDirectFiles) stringResource(R.string.storage_map_direct_files) else null,
                    Formatter.formatShortFileSize(context, size),
                    pluralStringResource(R.plurals.storage_map_images, count, count),
                    imageSizes,
                ).joinToString(" \u2022 ")

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
            }
        }

        if (currentFolder.size <= 0L) {
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

                val blocks = remember(currentFolder, widthPx, heightPx) {
                    layoutTreemap(
                        folder = currentFolder,
                        bounds = MapRect(0.0, 0.0, widthPx, heightPx),
                        gap = gapPx,
                        minSize = minSizePx,
                    )
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(blocks) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val hit = topLevelBlockAt(blocks, offset.x.toDouble(), offset.y.toDouble())
                                    if (hit != null && hit == selectedBlock && !hit.isDirectFiles) {
                                        onZoomIn(hit.folder)
                                    } else {
                                        selectedBlock = if (selectedBlock == hit) null else hit
                                    }
                                },
                            )
                        },
                ) {
                    for (block in blocks) {
                        val rect = block.rect
                        val r = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                        val topLeft = Offset(rect.left.toFloat(), rect.top.toFloat())
                        val size = Size(rect.width.toFloat(), rect.height.toFloat())

                        val hue = folderHue(block.hueIndex)
                        val lightness = (0.50f + block.depth * 0.08f).coerceAtMost(0.85f)
                        val color = if (block.isDirectFiles && block.depth > 0) {
                            Color.hsl(hue, 0.40f, lightness)
                        } else {
                            Color.hsl(hue, 0.70f, lightness)
                        }

                        drawRoundRect(
                            color = color,
                            topLeft = topLeft,
                            size = size,
                            cornerRadius = r,
                            style = Fill,
                        )

                        if (selectedBlock == block) {
                            drawRoundRect(
                                color = Color.White,
                                topLeft = topLeft,
                                size = size,
                                cornerRadius = r,
                                style = Stroke(width = 3.dp.toPx()),
                            )
                        }
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
                        val hue = folderHue(index)
                        val iconColor = Color.hsl(hue, 0.80f, 0.45f)
                        ListItem(
                            headlineContent = { Text(folder.name) },
                            supportingContent = {
                                Text(
                                    Formatter.formatShortFileSize(context, folder.size) + " \u2022 " +
                                        pluralStringResource(R.plurals.storage_map_images, folder.imageCount, folder.imageCount),
                                )
                            },
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
