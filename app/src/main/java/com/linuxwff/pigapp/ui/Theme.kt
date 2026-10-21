package com.linuxwff.pigapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.linuxwff.pigapp.TypeScale
import com.linuxwff.pigapp.UiPack

private fun pigTypography(types: TypeScale): Typography {
    fun style(size: Int, weight: FontWeight? = null, lineHeight: Float = types.lineHeight): TextStyle =
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = size.sp,
            lineHeight = (size * lineHeight).sp,
            fontWeight = weight,
        )

    return Typography(
        headlineLarge = style(types.h1, FontWeight.Bold, 1.25f),
        headlineMedium = style(types.h2, FontWeight.SemiBold, 1.25f),
        headlineSmall = style(types.h3, FontWeight.SemiBold, 1.3f),
        titleLarge = style(types.h4, FontWeight.SemiBold, 1.3f),
        titleMedium = style(types.h5, FontWeight.SemiBold, 1.3f),
        titleSmall = style(types.h6, FontWeight.SemiBold, 1.3f),
        bodyLarge = style(types.body),
        bodyMedium = style((types.body - 1).coerceAtLeast(8)),
        labelSmall = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = types.label.sp,
            lineHeight = (types.label * 1.4f).sp,
        ),
    )
}

/** Theme comes from the hot-reloaded UI pack; defaults match examples/ui-pack. */
@Composable
fun PigTheme(pack: UiPack, content: @Composable () -> Unit) {
    val t = pack.theme
    val onPrimary = if (t.primary.luminance() > 0.55f) Color(0xFF0B0A12) else Color.White
    val onSecondary = if (t.secondary.luminance() > 0.55f) Color(0xFF00202A) else Color.White
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = t.primary,
            onPrimary = onPrimary,
            primaryContainer = t.primary.copy(alpha = 0.22f).compositeOver(t.background),
            onPrimaryContainer = t.text,
            secondary = t.secondary,
            onSecondary = onSecondary,
            background = t.background,
            onBackground = t.text,
            surface = t.surface,
            onSurface = t.text,
            surfaceVariant = t.surfaceVariant,
            onSurfaceVariant = t.textMuted,
            outline = t.textMuted.copy(alpha = 0.4f),
            error = t.danger,
            errorContainer = t.danger.copy(alpha = 0.18f).compositeOver(t.background),
            onErrorContainer = t.text,
        ),
        typography = pigTypography(pack.types),
        content = content,
    )
}
