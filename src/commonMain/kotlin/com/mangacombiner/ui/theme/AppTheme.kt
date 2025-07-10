package com.mangacombiner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppTheme {
    LIGHT, DARK, MIDNIGHT, FOREST, OCEAN, SYSTEM
}

private val LightColorPalette = lightColors(
    primary = Color(0xFF6200EE),
    primaryVariant = Color(0xFF3700B3),
    secondary = Color(0xFF03DAC6),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF2F2F2),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color.Black,
    onSurface = Color.Black,
)

private val DarkColorPalette = darkColors(
    primary = Color(0xFFBB86FC),
    primaryVariant = Color(0xFF3700B3),
    secondary = Color(0xFF03DAC6),
    background = Color(0xFF121212),
    surface = Color(0xFF2A2A2A),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
)

private val MidnightColorPalette = darkColors(
    primary = Color(0xFF7F52FF),
    primaryVariant = Color(0xFF532FBF),
    secondary = Color(0xFF00C896),
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
)

private val ForestColorPalette = darkColors(
    primary = Color(0xFF4CAF50),
    primaryVariant = Color(0xFF388E3C),
    secondary = Color(0xFF8BC34A),
    background = Color(0xFF1B262C),
    surface = Color(0xFF22303C),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
)

private val OceanColorPalette = lightColors(
    primary = Color(0xFF0288D1),
    primaryVariant = Color(0xFF01579B),
    secondary = Color(0xFF00BCD4),
    background = Color(0xFFE0F7FA),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color.Black,
    onSurface = Color.Black,
)


@Composable
fun AppTheme(
    settingsTheme: AppTheme,
    systemLightTheme: AppTheme,
    systemDarkTheme: AppTheme,
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()

    // Determine the active theme based on user settings
    val activeTheme = if (settingsTheme == AppTheme.SYSTEM) {
        if (isSystemDark) systemDarkTheme else systemLightTheme
    } else {
        settingsTheme
    }

    // Get the color palette for the active theme
    val colors = when (activeTheme) {
        AppTheme.LIGHT -> LightColorPalette
        AppTheme.OCEAN -> OceanColorPalette
        AppTheme.DARK -> DarkColorPalette
        AppTheme.MIDNIGHT -> MidnightColorPalette
        AppTheme.FOREST -> ForestColorPalette
        // This case should not be reachable if the settings UI prevents saving 'SYSTEM'
        // for system-specific themes. But as a fallback, we handle it.
        AppTheme.SYSTEM -> if (isSystemDark) DarkColorPalette else LightColorPalette
    }

    MaterialTheme(
        colors = colors,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}