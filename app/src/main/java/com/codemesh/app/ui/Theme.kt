package com.codemesh.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Legacy accessor — kept for backward compatibility during migration.
// New code should use AppTheme.colors instead.
object CodeMeshColors {
    val Background @Composable get() = AppTheme.colors.base
    val Surface @Composable get() = AppTheme.colors.surface
    val Primary @Composable get() = AppTheme.colors.accent
    val MutedText @Composable get() = AppTheme.colors.muted
    val TextColor @Composable get() = AppTheme.colors.text
    val CodeBg @Composable get() = AppTheme.colors.codeBg
    val Error @Composable get() = AppTheme.colors.error
    val Divider @Composable get() = AppTheme.colors.divider
}

@Composable
fun CodeMeshMaterialTheme(content: @Composable () -> Unit) {
    val c = AppTheme.colors
    val colorScheme = darkColorScheme(
        primary = c.accent,
        onPrimary = c.buttonText,
        secondary = c.accent,
        background = c.base,
        surface = c.surface,
        onSurface = c.text,
        onBackground = c.text,
        error = c.error,
        outline = c.divider
    )
    MaterialTheme(colorScheme = colorScheme) {
        content()
    }
}
