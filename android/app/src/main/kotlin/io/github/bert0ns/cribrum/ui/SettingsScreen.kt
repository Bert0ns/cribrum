package io.github.bert0ns.cribrum.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.trash.TrashRetention

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenStorageMap: () -> Unit = {},
    retentionDays: Int = TrashRetention.DEFAULT_DAYS,
    onRetentionChange: (Int) -> Unit = {},
    pendingRetention: RetentionChange? = null,
    onConfirmRetention: () -> Unit = {},
    onCancelRetention: () -> Unit = {},
    excludedFolders: List<String> = emptyList(),
    onRemoveExcludedFolder: (String) -> Unit = {},
    onDeleteEmbeddings: () -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    @Suppress("DEPRECATION")
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }
    val websiteUrl = stringResource(R.string.settings_website_url)
    val sourceUrl = stringResource(R.string.settings_source_url)
    var choosingRetention by remember { mutableStateOf(false) }
    var confirmingEmbeddings by remember { mutableStateOf(false) }

    if (confirmingEmbeddings) {
        AlertDialog(
            onDismissRequest = { confirmingEmbeddings = false },
            title = { Text(stringResource(R.string.settings_delete_embeddings_title)) },
            text = { Text(stringResource(R.string.settings_delete_embeddings_confirm)) },
            confirmButton = {
                TextButton({
                    confirmingEmbeddings = false
                    onDeleteEmbeddings()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton({ confirmingEmbeddings = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (pendingRetention != null) {
        AlertDialog(
            onDismissRequest = onCancelRetention,
            title = { Text(pluralStringResource(R.plurals.empty_trash_title, pendingRetention.expiring, pendingRetention.expiring)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.settings_trash_retention_confirm,
                        pendingRetention.expiring,
                        pendingRetention.expiring,
                        pendingRetention.days,
                    ),
                )
            },
            confirmButton = { TextButton(onConfirmRetention) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onCancelRetention) { Text(stringResource(R.string.cancel)) } },
        )
    }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Text(
                text = stringResource(R.string.settings_about),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_version, version ?: "")) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_website)) },
                supportingContent = { Text(websiteUrl) },
                modifier = Modifier.clickable { uriHandler.openUri(websiteUrl) },
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_source)) },
                supportingContent = { Text(sourceUrl) },
                modifier = Modifier.clickable { uriHandler.openUri(sourceUrl) },
            )
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
        item {
            Text(
                text = stringResource(R.string.trash),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_trash_retention)) },
                supportingContent = {
                    Column {
                        Text(pluralStringResource(R.plurals.settings_trash_days, retentionDays, retentionDays))
                        Text(stringResource(R.string.settings_trash_retention_applies))
                    }
                    DropdownMenu(choosingRetention, { choosingRetention = false }) {
                        for (days in TrashRetention.CHOICES) {
                            DropdownMenuItem(
                                text = { Text(pluralStringResource(R.plurals.settings_trash_days, days, days)) },
                                onClick = {
                                    choosingRetention = false
                                    onRetentionChange(days)
                                },
                            )
                        }
                    }
                },
                modifier = Modifier.clickable { choosingRetention = true },
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_uninstall_warning)) },
            )
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.storage_map)) },
                modifier = Modifier.clickable { onOpenStorageMap() },
            )
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
        item {
            Text(
                text = stringResource(R.string.settings_excluded_folders),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        if (excludedFolders.isEmpty()) {
            item { ListItem(headlineContent = { Text(stringResource(R.string.settings_no_excluded_folders)) }) }
        }
        items(excludedFolders, key = { it }) { folder ->
            ListItem(
                headlineContent = { Text(folder) },
                trailingContent = {
                    IconButton({ onRemoveExcludedFolder(folder) }) {
                        Icon(Icons.Default.Close, stringResource(R.string.remove))
                    }
                },
            )
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_delete_embeddings)) },
                supportingContent = { Text(stringResource(R.string.settings_delete_embeddings_summary)) },
                modifier = Modifier.clickable { confirmingEmbeddings = true },
            )
        }
    }
}
