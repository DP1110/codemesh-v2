package com.codemesh.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

// ─── Theme Style Enum ───────────────────────────────────────────
enum class ThemeStyle { CLAUDE, CHATGPT }

// ─── Theme Color Palette ────────────────────────────────────────
data class CodeMeshPalette(
    val base: Color,
    val surface: Color,
    val text: Color,
    val accent: Color,
    val button: Color,
    val buttonText: Color,
    val muted: Color,
    val codeBg: Color,
    val error: Color,
    val divider: Color,
    val style: ThemeStyle
)

// ─── Claude Style (warm terracotta) ─────────────────────────────
val ClaudePalette = CodeMeshPalette(
    base       = Color(0xFF262624),
    surface    = Color(0xFF30302E),
    text       = Color(0xFFFAF9F5),
    accent     = Color(0xFFD97757),
    button     = Color(0xFFD97757),
    buttonText = Color(0xFF1A1A18),
    muted      = Color(0xFF8A8A85),
    codeBg     = Color(0xFF1E1E1C),
    error      = Color(0xFFE57373),
    divider    = Color(0xFF3A3A38),
    style      = ThemeStyle.CLAUDE
)

// ─── ChatGPT Style (cool teal) ─────────────────────────────────
val ChatGptPalette = CodeMeshPalette(
    base       = Color(0xFF212121),
    surface    = Color(0xFF2F2F2F),
    text       = Color(0xFFECECEC),
    accent     = Color(0xFF10A37F),
    button     = Color(0xFFFFFFFF),
    buttonText = Color(0xFF212121),
    muted      = Color(0xFF8E8E8E),
    codeBg     = Color(0xFF1A1A1A),
    error      = Color(0xFFE57373),
    divider    = Color(0xFF3A3A3A),
    style      = ThemeStyle.CHATGPT
)

// ─── CompositionLocal ───────────────────────────────────────────
val LocalCodeMeshPalette = compositionLocalOf { ClaudePalette }

// ─── Accessor ───────────────────────────────────────────────────
object AppTheme {
    val colors: CodeMeshPalette
        @Composable get() = LocalCodeMeshPalette.current

    val isClaude: Boolean
        @Composable get() = LocalCodeMeshPalette.current.style == ThemeStyle.CLAUDE

    val isChatGpt: Boolean
        @Composable get() = LocalCodeMeshPalette.current.style == ThemeStyle.CHATGPT

    // Animation durations differ per theme
    val screenEnterDuration: Int
        @Composable get() = if (isClaude) 600 else 350

    val screenEnterOffset: Int
        @Composable get() = if (isClaude) 10 else 4
}

// ─── Theme Provider ─────────────────────────────────────────────
@Composable
fun CodeMeshThemeProvider(
    themeStyle: ThemeStyle,
    content: @Composable () -> Unit
) {
    val palette = when (themeStyle) {
        ThemeStyle.CLAUDE -> ClaudePalette
        ThemeStyle.CHATGPT -> ChatGptPalette
    }
    CompositionLocalProvider(LocalCodeMeshPalette provides palette) {
        content()
    }
}
