package io.github.bert0ns.cribrum.ui

import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class HeatLevel { NORMAL, LIGHT, MODERATE, SEVERE }

/** What the heat line shows: the level, and how far the phone is from heavy throttling when it is known. */
data class Heat(val level: HeatLevel, val headroomPercent: Int?)

/**
 * The worse of the thermal status and the thermal headroom (docs/design.md §4), or null when there is
 * nothing to show: no headroom and a status of none. A headroom that is not above 0 (0 or NaN) is no reading.
 */
internal fun heatOf(status: Int, headroom: Float?): Heat? {
    val ratio = headroom?.takeIf { it > 0 }
    val byStatus = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> HeatLevel.NORMAL
        PowerManager.THERMAL_STATUS_LIGHT -> HeatLevel.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> HeatLevel.MODERATE
        else -> HeatLevel.SEVERE
    }
    if (ratio == null) return if (byStatus == HeatLevel.NORMAL) null else Heat(byStatus, null)
    val byHeadroom = when {
        ratio >= 1f -> HeatLevel.SEVERE
        ratio >= 0.95f -> HeatLevel.MODERATE
        ratio >= 0.85f -> HeatLevel.LIGHT
        else -> HeatLevel.NORMAL
    }
    return Heat(maxOf(byStatus, byHeadroom), Math.round((1 - ratio) * 100).coerceIn(0, 100))
}

private const val HEADROOM_EVERY_MS = 10_000L

/**
 * The phone's heat while [running], or null. The status comes from a listener and the headroom from one
 * loop every 10 seconds (the API returns NaN when called more often), off the main thread. Both stop
 * when [running] turns false, the app leaves the screen or the composable goes. It only informs:
 * nothing here touches the scan.
 *
 * The headroom is saved and has no key on [running]: unfolding or rotating recreates the activity, the
 * progress starts null there, and the read right after returns NaN, so a key would lose the last good
 * value for 10 seconds. A value from an earlier scan is replaced by the first read, which is at once.
 */
@Composable
fun rememberHeat(running: Boolean): Heat? {
    val power = LocalContext.current.getSystemService(PowerManager::class.java)
    var status by remember(running) { mutableIntStateOf(PowerManager.THERMAL_STATUS_NONE) }
    var headroom by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    LifecycleStartEffect(running) {
        if (!running) return@LifecycleStartEffect onStopOrDispose {}
        status = power.currentThermalStatus
        val listener = PowerManager.OnThermalStatusChangedListener { status = it }
        power.addThermalStatusListener(listener)
        onStopOrDispose { power.removeThermalStatusListener(listener) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val read = withContext(Dispatchers.Default) { power.getThermalHeadroom(0) }
                if (read > 0) headroom = read // a NaN or 0 read keeps the last good value
                delay(HEADROOM_EVERY_MS)
            }
        }
    }
    return if (running) heatOf(status, headroom) else null
}
