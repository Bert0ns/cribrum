// One photo and its compressed copy under a draggable line (docs/design.md §8): the original on the left of the line, the copy on its right.
package io.github.bert0ns.cribrum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.bert0ns.cribrum.R
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** The steps a screen reader's increment and decrement move the line by: a tenth of the width. */
internal const val LINE_STEPS = 9

/** Puts [line] at [target] within the photo, with a light tick when it reaches an edge. */
internal fun moveLine(line: MutableFloatState, target: Float, haptics: HapticFeedback) {
    val next = target.coerceIn(0f, 1f)
    if (next != line.floatValue && (next == 0f || next == 1f)) haptics.performHapticFeedback(Haptic.TICK.type)
    line.floatValue = next
}

/**
 * [original] under [copy], both fitted and zoomed by the same [zoom], so the same pixels line up. The copy
 * shows to the right of [line], a share of the width. Without a [copy] only the original shows. One finger on
 * the handle moves the line; the gestures of [zoomGestures] apply anywhere else. [tiled] holds the tiles of both layers.
 */
@Composable
internal fun CompareSlider(original: File, copy: File?, zoom: Zoom, line: MutableFloatState, ratio: Float, tiled: Boolean, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val description = stringResource(if (copy == null) R.string.compress_preview_before_description else R.string.compress_compare_image)
    val state = zoomLabel(zoom)
    Box(
        modifier.fillMaxWidth().aspectRatio(ratio).clipToBounds().zoomGestures(zoom).dragLine(line, haptics, enabled = copy != null).semantics {
            contentDescription = description
            stateDescription = state
        },
    ) {
        TiledImage(original, zoom, Modifier.fillMaxSize(), tiled)
        if (copy != null) {
            TiledImage(
                copy,
                zoom,
                Modifier.fillMaxSize().drawWithContent { clipRect(left = size.width * line.floatValue) { this@drawWithContent.drawContent() } },
                tiled,
            )
            Handle(zoom, line, haptics)
        }
    }
}

private val HANDLE = 48.dp

/**
 * One finger that goes down on the handle moves the line. It sits after [zoomGestures] on the same node, so it sees
 * the pointers first and all of them: a second finger hands the gesture to the pinch, and a finger elsewhere is the zoom's.
 */
private fun Modifier.dragLine(line: MutableFloatState, haptics: HapticFeedback, enabled: Boolean) = pointerInput(enabled, haptics) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val half = HANDLE.toPx() / 2
        if (abs(down.position.x - line.floatValue * size.width) > half || abs(down.position.y - size.height / 2f) > half) return@awaitEachGesture
        down.consume()
        do {
            val event = awaitPointerEvent()
            if (event.changes.size > 1) break // a pinch: the zoom takes it
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            moveLine(line, line.floatValue + change.positionChange().x / size.width, haptics)
            change.consume()
        } while (change.pressed)
    }
}

/** The line, and the handle on it that takes the drag. The handle is the only part of the line that does. */
@Composable
private fun Handle(zoom: Zoom, line: MutableFloatState, haptics: HapticFeedback) {
    val description = stringResource(R.string.compress_compare_slider)
    Box(Modifier.fillMaxSize().offset { IntOffset((line.floatValue * zoom.size.width).roundToInt() - 1.dp.roundToPx(), 0) }) {
        Box(Modifier.width(2.dp).fillMaxHeight().shadow(2.dp).background(Color.White))
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(-(HANDLE / 2 - 1.dp).roundToPx(), 0) }
                .size(HANDLE)
                .semantics {
                    contentDescription = description
                    progressBarRangeInfo = ProgressBarRangeInfo(line.floatValue, 0f..1f, LINE_STEPS)
                    setProgress {
                        moveLine(line, it, haptics)
                        true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surface, CircleShape).shadow(2.dp, CircleShape), Arrangement.Center, Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(16.dp))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp))
            }
        }
    }
}
