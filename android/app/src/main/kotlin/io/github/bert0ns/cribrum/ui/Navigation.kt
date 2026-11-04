package io.github.bert0ns.cribrum.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
        Destination.Map -> R.string.nav_map
        Destination.Settings -> R.string.nav_settings
    },
)

val Destination.icon: ImageVector
    get() = when (this) {
        Destination.Clean -> Icons.Filled.CheckCircle
        Destination.Compress -> CompressIcon
        Destination.Map -> Icons.Filled.Place
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
