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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.linuxwff.pigapp.UiPack
import java.io.File

/** The effective pack, so any component can read strings/components/extra tokens. */
val LocalUiPack = staticCompositionLocalOf { UiPack() }

/** Resolve a built-in family name or a materialized `assets/...` font file. */
fun packFontFamily(pack: UiPack, name: String): FontFamily {
    val key = name.trim()
    if (key.isNotBlank()) {
        val path = pack.fontFiles[key]
            ?: pack.fontFiles["assets/$key"]
            ?: pack.fontFiles[key.removePrefix("assets/")]
        if (path != null) {
            val file = File(path)
            if (file.isFile) {
                runCatching { FontFamily(Font(file)) }.getOrNull()?.let { return it }
            }
        }
    }
    return when (key.lowercase()) {
        "serif" -> FontFamily.Serif
        "mono", "monospace", "code" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        else -> FontFamily.SansSerif
    }
}

private fun fontWeight(value: Int): FontWeight = FontWeight(value.coerceIn(100, 900))

private fun pigTypography(pack: UiPack): Typography {
    val types = pack.types
    val bodyFamily = packFontFamily(pack, types.fontFamily)
    val headingFamily = packFontFamily(
        pack,
        types.headingFontFamily.ifBlank { types.fontFamily },
    )
    val labelFamily = packFontFamily(pack, types.labelFontFamily)
    fun style(
        size: Int,
        weight: FontWeight? = null,
        lineHeight: Float = types.lineHeight,
        family: FontFamily = bodyFamily,
        spacing: Float = types.letterSpacing,
    ): TextStyle = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        lineHeight = (size * lineHeight).sp,
        fontWeight = weight,
        letterSpacing = spacing.sp,
    )

    return Typography(
        headlineLarge = style(types.h1, fontWeight(types.headingWeight), 1.25f, headingFamily),
        headlineMedium = style(types.h2, fontWeight(types.headingWeight), 1.25f, headingFamily),
        headlineSmall = style(types.h3, fontWeight(types.headingWeight), 1.3f, headingFamily),
        titleLarge = style(types.h4, fontWeight(types.headingWeight), 1.3f, headingFamily),
        titleMedium = style(types.h5, fontWeight(types.headingWeight), 1.3f, headingFamily),
        titleSmall = style(types.h6, fontWeight(types.headingWeight), 1.3f, headingFamily),
        bodyLarge = style(types.body, fontWeight(types.bodyWeight)),
        bodyMedium = style((types.body - 1).coerceAtLeast(8), fontWeight(types.bodyWeight)),
        bodySmall = style((types.body - 3).coerceAtLeast(8), fontWeight(types.bodyWeight)),
        labelLarge = style((types.body - 1).coerceAtLeast(8), fontWeight(types.labelWeight), family = labelFamily),
        labelMedium = style(
            (types.label + 1).coerceAtLeast(8),
            fontWeight(types.labelWeight),
            1.35f,
            labelFamily,
        ),
        labelSmall = style(
            types.label,
            fontWeight(types.labelWeight),
            1.4f,
            labelFamily,
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
        typography = pigTypography(pack),
        content = content,
    )
    }
}
