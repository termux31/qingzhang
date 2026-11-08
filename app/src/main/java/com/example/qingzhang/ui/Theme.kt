package com.example.qingzhang.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AppBackground = Color(0xFFF7F8FA)

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EEE7),
    onPrimaryContainer = Color(0xFF103F36),
    secondary = Color(0xFF59645F),
    tertiary = Color(0xFF16836B),
    background = AppBackground,
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F2F2),
    onBackground = Color(0xFF19201E),
    onSurface = Color(0xFF19201E),
    outline = Color(0xFFDDE3E0),
    error = Color(0xFFD94F4F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF83D4C0),
    primaryContainer = Color(0xFF244F45),
    tertiary = Color(0xFF83D4C0),
    background = Color(0xFF111715),
    surface = Color(0xFF19201E),
    surfaceVariant = Color(0xFF252D2A),
    onBackground = Color(0xFFE0E6E3),
    onSurface = Color(0xFFE0E6E3),
)

@Composable
fun QingZhangTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
