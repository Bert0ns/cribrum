package io.github.bert0ns.cribrum.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bert0ns.cribrum.R

@Composable
fun Destination.label(): String = stringResource(
    when (this) {
        Destination.Clean -> R.string.nav_clean
        Destination.Compress -> R.string.nav_compress
        Destination.Settings -> R.string.nav_settings
    },
)

val Destination.icon: ImageVector
    get() = when (this) {
        Destination.Clean -> BroomIcon
        Destination.Compress -> CompressIcon
        Destination.Settings -> Icons.Filled.Settings
    }

/** Two arrows pointing at each other. The core icon set has no compress icon and the extended one is large. */
private val CompressIcon = ImageVector.Builder("Compress", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 11f)
        lineTo(8f, 7f)
        horizontalLineTo(11f)
        verticalLineTo(3f)
        horizontalLineTo(13f)
        verticalLineTo(7f)
        horizontalLineTo(16f)
        close()
        moveTo(12f, 13f)
        lineTo(8f, 17f)
        horizontalLineTo(11f)
        verticalLineTo(21f)
        horizontalLineTo(13f)
        verticalLineTo(17f)
        horizontalLineTo(16f)
        close()
    }
}.build()

/**
 * A broom: a handle over a head of bristles, with three cuts between them. A check circle read as "done" and was the
 * review's kept badge too. The core icon set has no broom and the extended one is large.
 */
private val BroomIcon = ImageVector.Builder("Broom", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        // The handle.
        moveTo(11f, 2f)
        horizontalLineTo(13f)
        verticalLineTo(9f)
        horizontalLineTo(11f)
        close()
        // The head, wider at the bottom.
        moveTo(7f, 10f)
        horizontalLineTo(17f)
        lineTo(20f, 16f)
        verticalLineTo(22f)
        horizontalLineTo(4f)
        verticalLineTo(16f)
        close()
        // The cuts between the bristles: with even-odd filling they are holes in the head.
        for (x in listOf(8f, 11.25f, 14.5f)) {
            moveTo(x, 17f)
            horizontalLineTo(x + 1.5f)
            verticalLineTo(22f)
            horizontalLineTo(x)
            close()
        }
    }
}.build()
