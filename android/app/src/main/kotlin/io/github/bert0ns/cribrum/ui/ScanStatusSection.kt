package io.github.bert0ns.cribrum.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.pipeline.Coverage
import io.github.bert0ns.cribrum.pipeline.DETECTORS
import io.github.bert0ns.cribrum.pipeline.EMBEDDING_STEP
import io.github.bert0ns.cribrum.pipeline.GROUP_DETECTORS
import io.github.bert0ns.cribrum.pipeline.ScanReport

/**
 * The scan's part of the settings' "Scans and storage" (docs/design.md §8): the last finished scan and its failures,
 * then the steps that have not analysed every image they need, or one row saying every step is done. The failing file
 * names are one tap away; no path, no detector version and no internal name is shown.
 */
fun LazyListScope.scanStatusSection(coverage: List<Coverage>, lastScan: ScanReport?, lastScanFailed: Boolean, showingFiles: Boolean, onToggleFiles: () -> Unit) {
    item {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_scan_status_last)) },
            modifier = if (lastScan == null || lastScan.unreadableFiles.isEmpty()) Modifier else Modifier.toggleFiles(showingFiles, onToggleFiles),
            supportingContent = {
                val context = LocalContext.current
                Column {
                    if (lastScan == null) {
                        Text(stringResource(R.string.settings_scan_status_none))
                    } else {
                        Text(DateUtils.formatDateTime(context, lastScan.finishedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH))
                        lastScan.durationMs?.let { Text(stringResource(R.string.settings_scan_status_duration, if (it < 60_000) "${it / 1000} s" else DateUtils.formatElapsedTime(it / 1000))) }
                        Text(pluralStringResource(R.plurals.settings_scan_status_unreadable, lastScan.unreadable, lastScan.unreadable))
                        if (lastScan.unreadableFiles.isNotEmpty()) Text(stringResource(R.string.settings_scan_status_unreadable_hint))
                    }
                    if (lastScanFailed) {
                        Text(stringResource(R.string.settings_scan_status_failed))
                    }
                }
            },
        )
    }
    val failed = lastScan?.failures?.values?.sum() ?: 0
    if (lastScan != null) {
        item {
            ListItem(
                headlineContent = {
                    Text(if (failed == 0) stringResource(R.string.settings_scan_status_no_failures) else pluralStringResource(R.plurals.settings_scan_status_failures, failed, failed))
                },
                supportingContent = if (failed == 0) null else ({ Text(stringResource(R.string.settings_scan_status_failures_hint)) }),
                modifier = if (failed == 0) Modifier else Modifier.toggleFiles(showingFiles, onToggleFiles),
            )
        }
    }
    if (lastScan != null && showingFiles) {
        items(lastScan.files) { file ->
            val step = stepName(file.step)
            // A failure of the trash's purge has no file of its own: its step says what failed.
            val text = when {
                step == null -> file.name
                file.name.isEmpty() -> step
                else -> stringResource(R.string.settings_scan_status_file, step, file.name)
            }
            ListItem(headlineContent = { Text(text) })
        }
        if (failed > lastScan.files.size) {
            item { ListItem(headlineContent = { Text(pluralStringResource(R.plurals.settings_scan_status_more, failed - lastScan.files.size, failed - lastScan.files.size)) }) }
        }
        items(lastScan.unreadableFiles) { name -> ListItem(headlineContent = { Text(stringResource(R.string.settings_scan_status_file, stringResource(R.string.settings_scan_status_unreadable_step), name)) }) }
        if (lastScan.unreadable > lastScan.unreadableFiles.size) {
            item { ListItem(headlineContent = { Text(pluralStringResource(R.plurals.settings_scan_status_more, lastScan.unreadable - lastScan.unreadableFiles.size, lastScan.unreadable - lastScan.unreadableFiles.size)) }) }
        }
    }
    // A step that is done says nothing a user needs: only the ones still missing images get a row.
    val missing = coverage.filter { it.analysed < it.needed }
    if (missing.isEmpty()) {
        item { ListItem(headlineContent = { Text(stringResource(R.string.settings_scan_status_all_done)) }) }
        return
    }
    item {
        Text(
            stringResource(R.string.settings_scan_status_hint),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    items(missing, key = { it.id }) { row ->
        val rowFailed = lastScan?.failures?.get(row.id) ?: 0
        // A step with no name for the user, such as a new detector, has no row: its id means nothing to them.
        val name = stepName(row.id) ?: return@items
        ListItem(
            headlineContent = { Text(name) },
            supportingContent = {
                Column {
                    Text(pluralStringResource(R.plurals.settings_scan_status_row, row.needed, row.analysed, row.needed))
                    if (rowFailed > 0) Text(pluralStringResource(R.plurals.settings_scan_status_row_failed, rowFailed, rowFailed))
                }
            },
        )
    }
}

/** A row that shows or hides the file names, and says which of the two it is doing. */
@Composable
private fun Modifier.toggleFiles(showingFiles: Boolean, onToggleFiles: () -> Unit): Modifier {
    val state = stringResource(if (showingFiles) R.string.settings_scan_status_files_shown else R.string.settings_scan_status_files_hidden)
    return this
        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.settings_scan_status_toggle_files), onClick = onToggleFiles)
        .semantics { stateDescription = state }
}

/** A detector with a category goes by the category's name; a new one without a name yet has none. */
@Composable
private fun stepName(id: String): String? {
    val category = (DETECTORS.map { it.id to it.category } + GROUP_DETECTORS.map { it.id to it.category }).firstOrNull { it.first == id }?.second
    return when {
        category != null -> categoryName(category)
        id == EMBEDDING_STEP -> stringResource(R.string.settings_scan_status_embedding)
        id == "faces" -> stringResource(R.string.settings_scan_status_faces)
        id == "metadata" -> stringResource(R.string.settings_scan_status_metadata)
        id == "trash" -> stringResource(R.string.settings_scan_status_trash)
        else -> null
    }
}
