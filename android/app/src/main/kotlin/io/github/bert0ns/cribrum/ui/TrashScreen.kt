// The trash (docs/design.md §9): what is in it, how long it stays, and the way back.
package io.github.bert0ns.cribrum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.folders.isStickerFolder
import io.github.bert0ns.cribrum.trash.TrashedImage
import java.io.File
import java.time.Duration
import java.time.Instant

@Composable
fun TrashScreen(
    trashed: List<TrashedImage>,
    selected: Set<String>,
    onSelect: (TrashedImage) -> Unit,
    onSelectAll: (List<TrashedImage>, Boolean) -> Unit,
    onRestore: () -> Unit,
    onEmpty: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmEmpty by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        if (trashed.isEmpty()) {
            Text(stringResource(R.string.trash_empty), Modifier.padding(16.dp))
            return@Column
        }
        Text(stringResource(R.string.trash_hint), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        // Stickers apart, as in the review: thousands of them would bury the photos.
        val (stickers, others) = trashed.partition { isStickerFolder(it.original.path) }
        val sections = listOf(R.plurals.trash_section_images to others, R.plurals.trash_section_stickers to stickers)
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.weight(1f)) {
            for ((title, section) in sections.filter { it.second.isNotEmpty() }) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "section-$title") {
                    val all = section.all { it.original.path in selected }
                    Row(Modifier.padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(pluralStringResource(title, section.size, section.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton({ onSelectAll(section, !all) }) {
                            Text(stringResource(if (all) R.string.deselect_all else R.string.select_all))
                        }
                    }
                }
                trashItems(section, selected, onSelect)
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onRestore, Modifier.weight(1f), enabled = selected.isNotEmpty()) { Text(stringResource(R.string.restore, selected.size)) }
            OutlinedButton({ confirmEmpty = true }, Modifier.weight(1f)) { Text(stringResource(R.string.empty_trash)) }
        }
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text(pluralStringResource(R.plurals.empty_trash_title, trashed.size, trashed.size)) },
            text = { Text(stringResource(R.string.empty_trash_body)) },
            confirmButton = {
                TextButton({
                    confirmEmpty = false
                    onEmpty()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton({ confirmEmpty = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun LazyGridScope.trashItems(trashed: List<TrashedImage>, selected: Set<String>, onSelect: (TrashedImage) -> Unit) {
    items(trashed, key = { it.file.path }) { item ->
        val daysLeft = Duration.between(Instant.now(), item.purgeAt).toDays().coerceAtLeast(0)
        val isSelected = item.original.path in selected
        Box(
            Modifier.aspectRatio(1f).padding(2.dp)
                .border(if (isSelected) 3.dp else 0.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                .selectable(isSelected, role = Role.Checkbox) { onSelect(item) },
        ) {
            AsyncImage(File(item.file.path), File(item.original.path).name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text(
                stringResource(R.string.days_left, daysLeft),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp),
            )
        }
    }
}
