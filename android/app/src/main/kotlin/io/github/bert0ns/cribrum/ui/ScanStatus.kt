package io.github.bert0ns.cribrum.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bert0ns.cribrum.R
import io.github.bert0ns.cribrum.pipeline.Indexing
import io.github.bert0ns.cribrum.pipeline.ScanProgress

/** The running scan with a progress bar, or a button that starts one. */
@Composable
fun ScanStatus(progress: ScanProgress?, onScan: () -> Unit, modifier: Modifier = Modifier, indexEmpty: Boolean = false, heat: Heat? = null, compressing: Boolean = false) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            progress == null -> {
                if (indexEmpty) Text(stringResource(R.string.scan_empty))
                // A scan does not start under a compression (design §9), so the button waits for it to end.
                if (compressing) Text(stringResource(R.string.compress_scan_blocked)) else OutlinedButton(onClick = onScan) { Text(stringResource(R.string.scan_now)) }
            }
            progress.total == 0 -> {
                PhaseLine(progress.step, heat)
                Text(stringResource(R.string.scan_step_scan, progress.done))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            else -> {
                PhaseLine(progress.step, heat)
                Text(stringResource(R.string.scan_step, stringResource(Indexing.phaseTitle(progress.step)), progress.done, progress.total))
                LinearProgressIndicator({ progress.done.toFloat() / progress.total }, Modifier.fillMaxWidth())
                // An empty line while the estimate is hidden, so the list below does not jump at each phase change.
                Text(progress.leftMs?.let { Indexing.timeLeft(LocalResources.current, it) }.orEmpty())
            }
        }
    }
}

/** "Phase 3 of 6" and the heat beside it, or on its own line when space is tight. */
@Composable
private fun PhaseLine(step: String, heat: Heat?) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val resources = LocalResources.current
        val textMeasurer = rememberTextMeasurer()
        val textStyle = LocalTextStyle.current
        val density = LocalDensity.current
        val wraps = remember(constraints.maxWidth, density, textStyle, resources) {
            val worstPhase = resources.getString(R.string.scan_phase_number, Indexing.PHASES, Indexing.PHASES)
            val worstHeat = resources.getString(
                R.string.scan_heat_headroom,
                resources.getString(R.string.scan_heat_moderate),
                100,
            )
            val phaseWidth = textMeasurer.measure(worstPhase, textStyle).size.width
            val heatWidth = textMeasurer.measure(worstHeat, textStyle).size.width
            val extra = with(density) { (HEAT_ICON + HEAT_ICON_SPACING + HEAT_GAP).roundToPx() }
            phaseWidth + heatWidth + extra > constraints.maxWidth
        }
        if (wraps) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Indexing.phaseText(resources, step))
                // An empty line until the first reading, so the list below does not jump when the heat shows.
                if (heat != null) HeatIndicator(heat) else Text("")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Indexing.phaseText(resources, step))
                if (heat != null) {
                    HeatIndicator(heat)
                }
            }
        }
    }
}

@Composable
private fun HeatIndicator(heat: Heat) {
    val level = stringResource(
        when (heat.level) {
            HeatLevel.NORMAL -> R.string.scan_heat_normal
            HeatLevel.LIGHT -> R.string.scan_heat_light
            HeatLevel.MODERATE -> R.string.scan_heat_moderate
            HeatLevel.SEVERE -> R.string.scan_heat_severe
        },
    )
    val text = heat.headroomPercent?.let {
        stringResource(R.string.scan_heat_headroom, level, it)
    } ?: level

    // One description for the icon and the text, so a screen reader says "Heat: moderate · 12%".
    val description = stringResource(R.string.scan_heat, text)
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HEAT_ICON_SPACING),
    ) {
        Icon(
            imageVector = Icons.Outlined.Thermostat,
            contentDescription = null,
            modifier = Modifier.size(HEAT_ICON),
        )
        Text(text)
    }
}

private val HEAT_ICON = 16.dp
private val HEAT_ICON_SPACING = 4.dp

/** The least room the measurement leaves between the phase and the heat on one line. */
private val HEAT_GAP = 8.dp

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
