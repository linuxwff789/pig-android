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
import androidx.compose.ui.unit.sp
import com.linuxwff.pigapp.UiPack

private val PigTypography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
)

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
        typography = PigTypography,
        content = content,
    )
}
