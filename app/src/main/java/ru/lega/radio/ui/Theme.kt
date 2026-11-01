package ru.lega.radio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD1FF),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF1E4C66),
    onPrimaryContainer = Color(0xFFD6EEFF),
    secondary = Color(0xFF9CCFB0),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF212936),
    onSurfaceVariant = Color(0xFF9BA7B4),
    outline = Color(0xFF3A4654),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF12557A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3ECFB),
    onPrimaryContainer = Color(0xFF062231),
    secondary = Color(0xFF3F6A52),
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF141A20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141A20),
    surfaceVariant = Color(0xFFE7EDF3),
    onSurfaceVariant = Color(0xFF4C5862),
    outline = Color(0xFFBFC9D3),
)

@Composable
fun LegaRadioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
