package io.github.bert0ns.cribrum.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.pipeline.Indexing
import io.github.bert0ns.cribrum.pipeline.ScanProgress

/**
 * The running scan with a progress bar, or a button that starts one: filled while nothing has been scanned, with
 * when the last scan ended beside it otherwise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanStatus(
    progress: ScanProgress?,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
    indexEmpty: Boolean = false,
    heat: Heat? = null,
    compressing: Boolean = false,
    lastScanAt: Long? = null,
) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            // A scan does not start under a compression (design §9), so the button waits for it to end.
            progress == null && compressing -> Text(stringResource(R.string.compress_scan_blocked))
            progress == null && indexEmpty -> {
                Text(stringResource(R.string.scan_empty))
                Button(onClick = onScan) { Text(stringResource(R.string.scan_now)) }
            }
            progress == null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onScan) { Text(stringResource(R.string.scan_now)) }
                if (lastScanAt != null) {
                    val context = LocalContext.current
                    Text(
                        stringResource(R.string.scan_last, DateUtils.formatDateTime(context, lastScanAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            progress.total == 0 -> {
                Text(Indexing.phaseText(LocalResources.current, progress.step))
                Text(stringResource(R.string.scan_step_scan, progress.done))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                HeatLine(heat)
            }
            else -> {
                Text(Indexing.phaseText(LocalResources.current, progress.step))
                Text(stringResource(R.string.scan_step, stringResource(Indexing.phaseTitle(progress.step)), progress.done, progress.total))
                LinearProgressIndicator({ progress.done.toFloat() / progress.total }, Modifier.fillMaxWidth())
                // An empty line while the estimate is hidden, so the list below does not jump at each phase change.
                Text(progress.leftMs?.let { Indexing.timeLeft(LocalResources.current, it) }.orEmpty())
                HeatLine(heat)
            }
        }
    }
}

/** A line only while the phone is hot enough to slow the scan down: at a moderate or severe level. */
@Composable
private fun HeatLine(heat: Heat?) {
    if (heat == null || heat.level < HeatLevel.MODERATE) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HEAT_ICON_SPACING),
    ) {
        Icon(
            imageVector = Icons.Outlined.Thermostat,
            contentDescription = null,
            modifier = Modifier.size(HEAT_ICON),
        )
        Text(stringResource(R.string.scan_heat_slow), style = MaterialTheme.typography.bodyMedium)
    }
}

private val HEAT_ICON = 16.dp
private val HEAT_ICON_SPACING = 4.dp

private val Icons.Outlined.Thermostat: ImageVector
    get() = thermostatVector

private val thermostatVector: ImageVector by lazy {
    materialIcon(name = "Outlined.Thermostat") {
        materialPath {
            moveTo(15.0f, 13.0f)
            verticalLineTo(5.0f)
            curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
            reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
            verticalLineToRelative(8.0f)
            curveToRelative(-1.21f, 0.91f, -2.0f, 2.36f, -2.0f, 4.0f)
            curveToRelative(0.0f, 2.76f, 2.24f, 5.0f, 5.0f, 5.0f)
            reflectiveCurveToRelative(5.0f, -2.24f, 5.0f, -5.0f)
            curveToRelative(0.0f, -1.64f, -0.79f, -3.09f, -2.0f, -4.0f)
            close()
            moveTo(12.0f, 4.0f)
            curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
            verticalLineToRelative(1.0f)
            horizontalLineToRelative(-1.0f)
            verticalLineToRelative(1.0f)
            horizontalLineToRelative(1.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-1.0f)
            verticalLineToRelative(1.0f)
            horizontalLineToRelative(1.0f)
            verticalLineToRelative(1.0f)
            horizontalLineToRelative(-1.0f)
            verticalLineToRelative(1.0f)
            horizontalLineToRelative(1.0f)
            curveToRelative(0.0f, 0.42f, 0.13f, 0.81f, 0.35f, 1.15f)
            lineToRelative(0.42f, 0.63f)
            lineToRelative(0.67f, 0.35f)
            curveTo(14.28f, 14.6f, 15.0f, 15.75f, 15.0f, 17.0f)
            curveToRelative(0.0f, 1.65f, -1.35f, 3.0f, -3.0f, 3.0f)
            reflectiveCurveToRelative(-3.0f, -1.35f, -3.0f, -3.0f)
            curveToRelative(0.0f, -1.25f, 0.72f, -2.4f, 1.56f, -2.87f)
            lineToRelative(0.67f, -0.35f)
            lineToRelative(0.42f, -0.63f)
            curveToRelative(0.22f, -0.34f, 0.35f, -0.73f, 0.35f, -1.15f)
            verticalLineTo(5.0f)
            curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
            close()
        }
    }
}

@Preview(name = "Default")
@Preview(name = "Narrow (280 dp)", widthDp = 280)
@Preview(name = "Italian", locale = "it")
@Preview(name = "Large font (1.5x)", fontScale = 1.5f)
@Preview(name = "Narrow Italian large font", widthDp = 280, locale = "it", fontScale = 1.5f)
@Composable
private fun ScanStatusPreview() {
    ScanStatus(
        progress = ScanProgress("detectors", 1200, 10220, leftMs = 3 * 60_000L),
        onScan = {},
        heat = Heat(HeatLevel.MODERATE, 12),
    )
}
