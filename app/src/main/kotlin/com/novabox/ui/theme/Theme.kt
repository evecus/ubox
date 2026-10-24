package com.novabox.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * NovaBox 暗色主题 (对照 Flutter 版 ThemeData:
 * ColorScheme.fromSeed(seedColor: Colors.blueGrey, brightness: Brightness.dark)).
 * 应用整体只有暗色 (播放器/海报场景), 不跟随系统亮色.
 */
private val NovaBoxDarkColors = darkColorScheme(
    primary = Color(0xFF90A4AE),
    onPrimary = Color(0xFF102027),
    primaryContainer = Color(0xFF37474F),
    onPrimaryContainer = Color(0xFFCFD8DC),
    secondary = Color(0xFFB0BEC5),
    onSecondary = Color(0xFF1B2C33),
    secondaryContainer = Color(0xFF33454D),
    onSecondaryContainer = Color(0xFFD3E3E9),
    tertiary = Color(0xFF1890FF),
    onTertiary = Color(0xFF00325A),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE3E7EA),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE3E7EA),
    surfaceVariant = Color(0xFF41484D),
    onSurfaceVariant = Color(0xFFC1C9CE),
    surfaceContainerLowest = Color(0xFF0B0F12),
    surfaceContainerLow = Color(0xFF181C20),
    surfaceContainer = Color(0xFF1C2024),
    surfaceContainerHigh = Color(0xFF262A2E),
    surfaceContainerHighest = Color(0xFF313539),
    error = Color(0xFFCF6679),
    onError = Color(0xFF1A1214),
    outline = Color(0xFF8B9198),
    outlineVariant = Color(0xFF41484D),
    inverseSurface = Color(0xFFE3E7EA),
    inverseOnSurface = Color(0xFF2A3136),
)

/** 直播页原版视觉规范 (对照 shape_live_focus / LiveChannelItemAdapter). */
object LiveColors {
    val Blue = Color(0xFF1890FF)
    val SelectedBg = Color(0x331890FF)
    val BadgeBg = Color(0x6C3D3D3D)
}

@Composable
fun NovaBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NovaBoxDarkColors,
        content = content,
    )
}
