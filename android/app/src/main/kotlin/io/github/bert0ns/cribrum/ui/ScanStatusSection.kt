package io.github.bert0ns.cribrum.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
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
 * The "Scan status" block of the settings (docs/design.md §8): per step, the images analysed out of
 * the images that need it, the failures of the last finished scan, and the version. The failing file
 * names are one tap away; no path is shown.
 */
fun LazyListScope.scanStatusSection(coverage: List<Coverage>, lastScan: ScanReport?, lastScanFailed: Boolean, showingFiles: Boolean, onToggleFiles: () -> Unit) {
    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
    item {
        Text(
            text = stringResource(R.string.settings_scan_status),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
        )
    }
    item { ListItem(headlineContent = { Text(stringResource(R.string.settings_scan_status_hint)) }) }
    item {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_scan_status_last)) },
            supportingContent = {
                val context = LocalContext.current
                Column {
                    if (lastScan == null) {
                        Text(stringResource(R.string.settings_scan_status_none))
                    } else {
                        Text(DateUtils.formatDateTime(context, lastScan.finishedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH))
                        Text(pluralStringResource(R.plurals.settings_scan_status_unreadable, lastScan.unreadable, lastScan.unreadable))
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
                modifier = if (failed == 0) {
                    Modifier
                } else {
                    val state = stringResource(if (showingFiles) R.string.settings_scan_status_files_shown else R.string.settings_scan_status_files_hidden)
                    Modifier
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.settings_scan_status_toggle_files), onClick = onToggleFiles)
                        .semantics { stateDescription = state }
                },
            )
        }
    }
    if (lastScan != null && showingFiles) {
        items(lastScan.files) { file -> ListItem(headlineContent = { Text(stringResource(R.string.settings_scan_status_file, file.step, file.name)) }) }
        if (failed > lastScan.files.size) {
            item { ListItem(headlineContent = { Text(pluralStringResource(R.plurals.settings_scan_status_more, failed - lastScan.files.size, failed - lastScan.files.size)) }) }
        }
    }
    items(coverage, key = { it.id }) { row ->
        val rowFailed = lastScan?.failures?.get(row.id) ?: 0
        ListItem(
            headlineContent = { Text(stepName(row.id)) },
            supportingContent = {
                Column {
                    Text(pluralStringResource(R.plurals.settings_scan_status_row, row.needed, row.analysed, row.needed, row.version))
                    if (rowFailed > 0) Text(pluralStringResource(R.plurals.settings_scan_status_row_failed, rowFailed, rowFailed))
                }
            },
        )
    }
}

/** A detector with a category goes by the category's name; a new one without a name yet shows its id. */
@Composable
private fun stepName(id: String): String {
    val category = (DETECTORS.map { it.id to it.category } + GROUP_DETECTORS.map { it.id to it.category }).firstOrNull { it.first == id }?.second
    return when {
        category != null -> categoryName(category)
        id == EMBEDDING_STEP -> stringResource(R.string.settings_scan_status_embedding)
        id == "faces" -> stringResource(R.string.settings_scan_status_faces)
        else -> id
    }
}
