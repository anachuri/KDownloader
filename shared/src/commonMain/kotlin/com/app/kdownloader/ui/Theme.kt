package com.app.kdownloader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0F766E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF042F2E),
    background = Color(0xFFF5F6FB),
    onBackground = Color(0xFF14151C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14151C),
    surfaceVariant = Color(0xFFEEF0F7),
    onSurfaceVariant = Color(0xFF5B5F6E),
    outline = Color(0xFFB9BDCB),
    outlineVariant = Color(0xFFDDE0EA),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9B4FF),
    onPrimary = Color(0xFF232060),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF134E4A),
    onSecondaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF0D0F16),
    onBackground = Color(0xFFE8E9F0),
    surface = Color(0xFF171A24),
    onSurface = Color(0xFFE8E9F0),
    surfaceVariant = Color(0xFF232735),
    onSurfaceVariant = Color(0xFFB4B8C7),
    outline = Color(0xFF4A4F62),
    outlineVariant = Color(0xFF323748),
    error = Color(0xFFFFB4AB),
)

@Composable
fun KDownloaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
