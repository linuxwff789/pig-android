package com.linuxwff.pigapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private val PigColors = darkColorScheme(
    primary = Color(0xFF8B7CFF),
    onPrimary = Color(0xFF0B0A12),
    primaryContainer = Color(0xFF2A2350),
    onPrimaryContainer = Color(0xFFE3DEFF),
    secondary = Color(0xFF4CD7F6),
    onSecondary = Color(0xFF00202A),
    background = Color(0xFF0E1014),
    onBackground = Color(0xFFE6E8EF),
    surface = Color(0xFF161A22),
    onSurface = Color(0xFFE6E8EF),
    surfaceVariant = Color(0xFF1E2430),
    onSurfaceVariant = Color(0xFFA8B0C0),
    outline = Color(0xFF2C3442),
    error = Color(0xFFFF6B6B),
    errorContainer = Color(0xFF3A1418),
    onErrorContainer = Color(0xFFFFD9DB),
)

private val PigTypography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
)

@Composable
fun PigTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PigColors,
        typography = PigTypography,
        content = content,
    )
}
