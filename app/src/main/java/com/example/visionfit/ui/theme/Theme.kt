package com.example.visionfit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Lime500,
    onPrimary = Ink900,
    primaryContainer = Lime300,
    onPrimaryContainer = Ink900,
    secondary = Sky500,
    onSecondary = Ink900,
    tertiary = Coral500,
    onTertiary = Ink900,
    background = Ink900,
    onBackground = Color.White,
    surface = Ink800,
    onSurface = Color.White,
    surfaceVariant = Ink700,
    onSurfaceVariant = Ink100,
    surfaceContainer = Ink700,
    surfaceContainerHigh = Ink600,
    surfaceContainerHighest = Ink500,
    surfaceContainerLow = Ink800,
    surfaceContainerLowest = Ink900,
    outline = Ink400,
    outlineVariant = Ink500,
    error = Coral500,
    onError = Ink900,
    errorContainer = Color(0xFF3A1A1F),
    onErrorContainer = Coral500
)

private val LightColors = lightColorScheme(
    primary = Lime300,
    onPrimary = Ink900,
    primaryContainer = Lime500,
    onPrimaryContainer = Ink900,
    secondary = Sky500,
    onSecondary = Ink900,
    tertiary = Coral500,
    onTertiary = Color.White,
    background = PaperWhite,
    onBackground = Ink900,
    surface = Color.White,
    onSurface = Ink900,
    surfaceVariant = SoftSurface,
    onSurfaceVariant = Ink400,
    surfaceContainer = SoftSurface,
    surfaceContainerHigh = Color(0xFFE3E8EE),
    surfaceContainerHighest = Color(0xFFD7DDE5),
    surfaceContainerLow = Color.White,
    surfaceContainerLowest = Color.White,
    outline = Ink200,
    outlineVariant = Color(0xFFE3E8EE),
    error = Coral500,
    onError = Color.White,
    errorContainer = Color(0xFFFFE3E3),
    onErrorContainer = Coral500
)

@Composable
fun VisionFitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
