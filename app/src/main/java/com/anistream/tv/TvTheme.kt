package com.anistream.tv

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colours taken from the Anne Bonny icon: night black, crimson hair, gold trim. Always dark on a TV. */
object TvColors {
    val Background = Color(0xFF0A0A0E)
    val Surface = Color(0xFF16161C)
    val SurfaceHigh = Color(0xFF24242C)
    val Accent = Color(0xFFE0443C)
    val Gold = Color(0xFFD9A54A)
    val Text = Color(0xFFF3EFE9)
    val TextDim = Color(0xFFA29E98)
    val Focus = Color(0xFFF3EFE9)
}

/** Overscan-safe margins (Android TV guidance: 48dp sides, 27dp top and bottom). */
object TvInsets {
    val Horizontal = 48.dp
    val Vertical = 27.dp
}

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = TvColors.Accent,
        onPrimary = Color.White,
        secondary = TvColors.Gold,
        background = TvColors.Background,
        onBackground = TvColors.Text,
        surface = TvColors.Surface,
        onSurface = TvColors.Text,
        surfaceVariant = TvColors.SurfaceHigh,
        onSurfaceVariant = TvColors.TextDim,
        error = Color(0xFFFF6B5E),
    )
    MaterialTheme(colorScheme = scheme) {
        // Plain Text has no Surface behind it on these screens, so set the default text colour here.
        CompositionLocalProvider(LocalContentColor provides TvColors.Text, content = content)
    }
}
