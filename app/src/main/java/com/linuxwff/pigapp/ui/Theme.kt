package com.linuxwff.pigapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.linuxwff.pigapp.TypeScale
import com.linuxwff.pigapp.UiPack

/** The effective pack, so any component can read strings/components/extra tokens. */
val LocalUiPack = staticCompositionLocalOf { UiPack() }

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
        bodySmall = style((types.body - 3).coerceAtLeast(8)),
        labelLarge = style((types.body - 1).coerceAtLeast(8), FontWeight.Medium),
        labelMedium = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = (types.label + 1).coerceAtLeast(8).sp,
            lineHeight = ((types.label + 1) * 1.35f).sp,
        ),
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
    val onPrimary = t.onPrimary
        ?: if (t.primary.luminance() > 0.55f) Color(0xFF0B0A12) else Color.White
    val onSecondary = t.onSecondary
        ?: if (t.secondary.luminance() > 0.55f) Color(0xFF00202A) else Color.White
    val onAccent = t.onAccent
        ?: if (t.accent.luminance() > 0.55f) Color(0xFF04131A) else Color.White
    CompositionLocalProvider(LocalUiPack provides pack) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = t.primary,
            onPrimary = onPrimary,
            primaryContainer = t.primary.copy(alpha = 0.22f).compositeOver(t.background),
            onPrimaryContainer = t.text,
            secondary = t.secondary,
            onSecondary = onSecondary,
            secondaryContainer = t.secondary.copy(alpha = 0.18f).compositeOver(t.background),
            onSecondaryContainer = t.text,
            tertiary = t.accent,
            onTertiary = onAccent,
            tertiaryContainer = t.accent.copy(alpha = 0.18f).compositeOver(t.background),
            onTertiaryContainer = t.text,
            background = t.background,
            onBackground = t.text,
            surface = t.surface,
            onSurface = t.text,
            surfaceVariant = t.surfaceVariant,
            onSurfaceVariant = t.textMuted,
            surfaceContainer = t.surface,
            surfaceContainerHigh = t.surfaceVariant,
            surfaceContainerHighest = t.surfaceVariant,
            surfaceBright = t.surfaceVariant,
            surfaceDim = t.background,
            outline = t.outline ?: t.textMuted.copy(alpha = 0.4f),
            outlineVariant = t.outlineVariant ?: t.textMuted.copy(alpha = 0.18f),
            error = t.danger,
            errorContainer = t.danger.copy(alpha = 0.16f).compositeOver(t.background),
            onErrorContainer = t.text,
            scrim = t.scrim,
        ),
        typography = pigTypography(pack.types),
        content = content,
    )
    }
}
