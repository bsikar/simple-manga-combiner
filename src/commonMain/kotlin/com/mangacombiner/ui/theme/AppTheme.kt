package com.mangacombiner.ui.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppTheme {
    E_INK, LIGHT, DARK, MIDNIGHT, FOREST, OCEAN, GRUVBOX_DARK, GRUVBOX_LIGHT
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

private val GruvboxDarkColorPalette = darkColors(
    primary = Color(0xFF83a598),
    primaryVariant = Color(0xFF458588),
    secondary = Color(0xFFb16286),
    background = Color(0xFF282828),
    surface = Color(0xFF3c3836),
    error = Color(0xFFfb4934),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFFebdbb2),
    onSurface = Color(0xFFebdbb2),
    onError = Color.Black
)

private val GruvboxLightColorPalette = lightColors(
    primary = Color(0xFF458588),
    primaryVariant = Color(0xFF076678),
    secondary = Color(0xFFb16286),
    background = Color(0xFFfbf1c7),
    surface = Color(0xFFebdbb2),
    error = Color(0xFFcc241d),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF3c3836),
    onSurface = Color(0xFF3c3836),
    onError = Color.White
)

private val EInkColorPalette = lightColors(
    primary = Color(0xFF000000),
    primaryVariant = Color(0xFF333333),
    secondary = Color(0xFF555555),
    background = Color.White,
    surface = Color(0xFFF5F5F5),
    error = Color(0xFF000000),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black,
    onError = Color.White
)


@Composable
fun AppTheme(
    settingsTheme: AppTheme,
    content: @Composable () -> Unit
) {
    val colors = when (settingsTheme) {
        AppTheme.E_INK -> EInkColorPalette
        AppTheme.LIGHT -> LightColorPalette
        AppTheme.OCEAN -> OceanColorPalette
        AppTheme.DARK -> DarkColorPalette
        AppTheme.MIDNIGHT -> MidnightColorPalette
        AppTheme.FOREST -> ForestColorPalette
        AppTheme.GRUVBOX_DARK -> GruvboxDarkColorPalette
        AppTheme.GRUVBOX_LIGHT -> GruvboxLightColorPalette
    }

    MaterialTheme(
        colors = colors,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}

