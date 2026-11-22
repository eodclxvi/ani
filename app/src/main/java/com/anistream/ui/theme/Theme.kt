package com.anistream.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { System, Dark, Light }

object ThemePreference {
    var mode by mutableStateOf(ThemeMode.Dark)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("ani_prefs", Context.MODE_PRIVATE)
        mode = try {
            ThemeMode.valueOf(prefs.getString("theme", ThemeMode.Dark.name)!!)
        } catch (_: Exception) {
            ThemeMode.Dark
        }
    }

    fun set(context: Context, newMode: ThemeMode) {
        mode = newMode
        context.getSharedPreferences("ani_prefs", Context.MODE_PRIVATE)
            .edit().putString("theme", newMode.name).apply()
    }

    fun cycle(context: Context) {
        val next = when (mode) {
            ThemeMode.Dark -> ThemeMode.Light
            ThemeMode.Light -> ThemeMode.System
            ThemeMode.System -> ThemeMode.Dark
        }
        set(context, next)
    }
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    secondary = Color(0xFFCCC2DC),
    surface = Color(0xFF1C1B1F),
    background = Color(0xFF1C1B1F),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFF625B71),
    surface = Color(0xFFFFFBFE),
    background = Color(0xFFFFFBFE),
)

@Composable
fun AniStreamTheme(content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (ThemePreference.mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> systemDark
    }

    val colors = when {
        Build.VERSION.SDK_INT >= 31 && isDark -> dynamicDarkColorScheme(LocalContext.current)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(LocalContext.current)
        isDark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
