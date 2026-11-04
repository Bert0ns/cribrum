package io.github.bert0ns.cribrum.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Material's baseline colors, light or dark as the system is set (docs/design.md §8). No dynamic colors from the
 * wallpaper, so the marks of the review look the same on every phone.
 */
@Composable
fun CribrumTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}
