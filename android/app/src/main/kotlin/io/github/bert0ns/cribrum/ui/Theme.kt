package io.github.bert0ns.cribrum.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * Material's baseline colors, light or dark as the system is set (docs/design.md §8). No dynamic colors from the
 * wallpaper, so the marks of the review look the same on every phone.
 */
@Composable
fun CribrumTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}

/** A dialog's confirm button for an action that deletes or is hard to take back, in the error color. */
@Composable
fun destructive(): ButtonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)

/** The widest a screen of text and lists gets, centered, so lines stay readable on an unfolded Fold or a tablet. */
fun Modifier.readableWidth(): Modifier = fillMaxWidth().wrapContentWidth().widthIn(max = 640.dp)

/**
 * Buttons side by side, the first one taking the room the others leave, or one under the other at full width when
 * their text does not fit on one line, as with a large font.
 */
@Composable
fun Buttons(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val widths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val fits = widths.sum() + gap * (measurables.size - 1) <= constraints.maxWidth
        if (fits) {
            val first = constraints.maxWidth - widths.drop(1).sum() - gap * (measurables.size - 1)
            val placeables = measurables.mapIndexed { index, it ->
                val width = if (index == 0) first else widths[index]
                it.measure(Constraints.fixedWidth(width))
            }
            layout(constraints.maxWidth, placeables.maxOf { it.height }) {
                var x = 0
                for (placeable in placeables) {
                    placeable.placeRelative(x, 0)
                    x += placeable.width + gap
                }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(constraints.maxWidth)) }
            layout(constraints.maxWidth, placeables.sumOf { it.height } + gap * (placeables.size - 1)) {
                var y = 0
                for (placeable in placeables) {
                    placeable.placeRelative(0, y)
                    y += placeable.height + gap
                }
            }
        }
    }
}
