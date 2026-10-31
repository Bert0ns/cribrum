package io.github.bert0ns.cribrum.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.pipeline.Indexing
import io.github.bert0ns.cribrum.pipeline.ScanProgress

/** The running scan with a progress bar, or a button that starts one. */
@Composable
fun ScanStatus(progress: ScanProgress?, onScan: () -> Unit, modifier: Modifier = Modifier, indexEmpty: Boolean = false) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            progress == null -> {
                if (indexEmpty) Text(stringResource(R.string.scan_empty))
                OutlinedButton(onClick = onScan) { Text(stringResource(R.string.scan_now)) }
            }
            progress.total == 0 -> {
                Text(Indexing.phaseText(LocalResources.current, progress.step))
                Text(stringResource(R.string.scan_step_scan, progress.done))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            else -> {
                Text(Indexing.phaseText(LocalResources.current, progress.step))
                Text(stringResource(R.string.scan_step, stringResource(Indexing.phaseTitle(progress.step)), progress.done, progress.total))
                LinearProgressIndicator({ progress.done.toFloat() / progress.total }, Modifier.fillMaxWidth())
                // An empty line while the estimate is hidden, so the list below does not jump at each phase change.
                Text(progress.leftMs?.let { Indexing.timeLeft(LocalResources.current, it) }.orEmpty())
            }
        }
    }
}

@Preview
@Composable
private fun ScanStatusPreview() {
    ScanStatus(ScanProgress("detectors", 1200, 10220, leftMs = 3 * 60_000L), onScan = {})
}
