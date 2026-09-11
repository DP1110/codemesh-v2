package com.codemesh.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object CodeMeshColors {
    val Background = Color(0xFF0d0d1a)
    val Panel = Color(0xFF13131f)
    val PanelBorder = Color(0x0Dffffff)
    val Primary = Color(0xFF00d4ff)
    val Success = Color(0xFF00ff88)
    val Warning = Color(0xFFff8800)
    val UserBubbleBg = Color(0x1A00d4ff)
    val UserBubbleBorder = Color(0x3300d4ff)
    val AiBubbleBg = Color(0xFF1a1a2e)
    val AiBubbleBorder = Color(0x0Dffffff)
    val CodeBlockBg = Color(0xFF0a0a14)
    val InlineCodeBg = Color(0x10ffffff)
    val MutedText = Color(0xFFAAAAAA)
}

private val DarkColorScheme = darkColorScheme(
    primary = CodeMeshColors.Primary,
    background = CodeMeshColors.Background,
    surface = CodeMeshColors.Panel,
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

val AppTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.5.sp
    )
)

@Composable
fun CodeMeshTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content
    )
}
