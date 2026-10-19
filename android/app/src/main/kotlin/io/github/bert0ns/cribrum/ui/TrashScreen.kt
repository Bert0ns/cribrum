// The trash (docs/design.md §9): what is in it, how long it stays, and the way back.
package io.github.bert0ns.cribrum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.trash.TrashedImage
import java.io.File
import java.time.Duration
import java.time.Instant

@Composable
fun TrashScreen(
    trashed: List<TrashedImage>,
    selected: Set<String>,
    onSelect: (TrashedImage) -> Unit,
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
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.weight(1f)) {
            items(trashed, key = { it.file.path }) { item ->
                val daysLeft = Duration.between(Instant.now(), item.purgeAt).toDays().coerceAtLeast(0)
                Box(
                    Modifier.aspectRatio(1f).padding(2.dp)
                        .border(if (item.original.path in selected) 3.dp else 0.dp, if (item.original.path in selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onSelect(item) },
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
            confirmButton = { TextButton({ confirmEmpty = false; onEmpty() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ confirmEmpty = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
