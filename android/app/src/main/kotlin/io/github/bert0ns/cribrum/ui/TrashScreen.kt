// The trash (docs/design.md §9): what is in it, how long it stays, and the way back.
package io.github.bert0ns.cribrum.ui

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.folders.isStickerFolder
import io.github.bert0ns.cribrum.trash.TrashedImage
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

private const val MILLIS_PER_DAY = 86_400_000.0

@Composable
fun TrashScreen(
    trashed: List<TrashedImage>,
    selected: Set<String>,
    onSelect: (TrashedImage) -> Unit,
    onSelectAll: (List<TrashedImage>, Boolean) -> Unit,
    onRestore: () -> Unit,
    /**
     * Gets the trash items the user confirmed deleting for good, by their file in the trash: the selection, or the whole
     * trash. An original and its compressed copy share a path, so the path cannot tell them apart.
     */
    onDelete: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var fullSize by remember { mutableStateOf<TrashedImage?>(null) }
    // With a selection the button deletes only it, and only an empty selection empties the trash.
    val targets = if (selected.isEmpty()) trashed else trashed.filter { it.file.path in selected }
    Column(modifier.fillMaxSize()) {
        if (trashed.isEmpty()) {
            Text(stringResource(R.string.trash_empty), Modifier.padding(16.dp))
            return@Column
        }
        // How long the trash keeps images is in Settings. Here the hint says how to look before restoring.
        Text(stringResource(R.string.trash_hint), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        // Stickers apart, as in the review: thousands of them would bury the photos.
        val (stickers, others) = trashed.partition { isStickerFolder(it.original.path) }
        val sections = listOf(R.plurals.trash_section_images to others, R.plurals.trash_section_stickers to stickers)
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.weight(1f)) {
            for ((title, section) in sections.filter { it.second.isNotEmpty() }) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "section-$title") {
                    val all = section.all { it.file.path in selected }
                    Row(Modifier.padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(pluralStringResource(title, section.size, section.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton({ onSelectAll(section, !all) }) {
                            Text(stringResource(if (all) R.string.deselect_all else R.string.select_all))
                        }
                    }
                }
                trashItems(section, selected, onSelect) { fullSize = it }
            }
        }
        // With nothing selected, emptying the trash is the only action. With a selection, deleting it for good weighs
        // less than Restore: a text button, in the error color.
        if (selected.isEmpty()) {
            OutlinedButton(
                { confirmDelete = true },
                Modifier.fillMaxWidth().padding(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.empty_trash)) }
        } else {
            Buttons(Modifier.fillMaxWidth().padding(16.dp)) {
                Button(onRestore) { Text(stringResource(R.string.restore, selected.size)) }
                TextButton({ confirmDelete = true }, colors = destructive()) { Text(stringResource(R.string.delete_selected, selected.size)) }
            }
        }
    }
    if (confirmDelete) {
        val files = targets.map { it.file.path }.toSet()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(pluralStringResource(R.plurals.empty_trash_title, targets.size, targets.size)) },
            text = { Text(stringResource(R.string.empty_trash_body, Formatter.formatShortFileSize(LocalContext.current, targets.sumOf { it.original.size }))) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    onDelete(files)
                }, colors = destructive()) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // A restore or a delete can take the image out of the trash while it is open.
    LaunchedEffect(trashed, fullSize) {
        if (fullSize != null && fullSize !in trashed) fullSize = null
    }
    fullSize?.let { start ->
        FullSizeViewer(trashed.map { it.file.path }, start.file.path, onClose = { fullSize = null }) { page ->
            val item = trashed.getOrNull(page) ?: return@FullSizeViewer
            FullSizeToggle(item.file.path in selected, stringResource(R.string.tile_selected), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary) { onSelect(item) }
            FullSizeBar(item.original.path, item.favorite) {
                Text(pluralStringResource(R.plurals.trash_tile_days_left, daysLeft(item).toInt(), daysLeft(item)), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun daysLeft(item: TrashedImage) = ceil(Duration.between(Instant.now(), item.purgeAt).toMillis() / MILLIS_PER_DAY).toLong().coerceAtLeast(0)

private fun LazyGridScope.trashItems(trashed: List<TrashedImage>, selected: Set<String>, onSelect: (TrashedImage) -> Unit, onHold: (TrashedImage) -> Unit) {
    items(trashed, key = { it.file.path }) { item ->
        val daysLeft = daysLeft(item)
        val isSelected = item.file.path in selected
        val open = stringResource(R.string.action_open_full_size)
        val daysText = stringResource(R.string.days_left, daysLeft)
        val spokenDays = pluralStringResource(R.plurals.trash_tile_days_left, daysLeft.toInt(), daysLeft)
        val description = stringResource(R.string.tile_description, File(item.original.path).name, spokenDays)
        Box(
            Modifier.aspectRatio(1f).padding(2.dp)
                .border(if (isSelected) 3.dp else 0.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                .combinedClickable(role = Role.Checkbox, onClick = { onSelect(item) }, onLongClick = { onHold(item) }, onLongClickLabel = open)
                .semantics {
                    contentDescription = description
                    this.selected = isSelected
                    customActions = listOf(
                        CustomAccessibilityAction(open) {
                            onHold(item)
                            true
                        },
                    )
                },
        ) {
            TracedAsyncImage(File(item.file.path), "Trash.tile", null, Modifier.fillMaxSize(), tile = true)
            if (isSelected) SelectedCheck(Modifier.align(Alignment.TopEnd).padding(4.dp))
            Text(
                daysText,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).clearAndSetSemantics {}.background(Color.Black.copy(alpha = 0.6f)).padding(4.dp),
            )
        }
    }
}
