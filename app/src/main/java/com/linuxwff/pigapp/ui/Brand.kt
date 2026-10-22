package com.linuxwff.pigapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Text/icon color for anything painted with the bright brand gradient. */
val BrandInk = Color(0xFF0A0C12)

@Composable
fun brandBrush(alpha: Float = 1f): Brush {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    return remember(primary, tertiary, alpha) {
        Brush.linearGradient(
            listOf(
                primary.copy(alpha = alpha),
                tertiary.copy(alpha = alpha),
            ),
        )
    }
}

/** The pig badge: bright gradient disc with a soft glow. */
@Composable
fun BrandAvatar(size: Dp = 34.dp, emoji: String? = null, shadow: Boolean = true) {
    val glyph = emoji ?: packText("brand.emoji", "🐷")
    val shape = CircleShape
    val primary = MaterialTheme.colorScheme.primary
    var modifier = Modifier.size(size)
    if (shadow) {
        modifier = modifier.shadow(size * 0.25f, shape, ambientColor = primary, spotColor = primary)
    }
    Box(
        modifier
            .clip(shape)
            .background(brandBrush(), shape)
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = (size.value * 0.5f).sp)
    }
}

@Composable
fun ConnectionDot(connected: Boolean, size: Dp = 7.dp) {
    val color = if (connected) {
        MaterialTheme.colorScheme.tertiary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/** Pill-shaped primary action painted with the brand gradient. */
@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    cornerRadius: Int = 18,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius.dp)
    val brush: Brush = if (enabled) {
        brandBrush()
    } else {
        SolidColor(MaterialTheme.colorScheme.surfaceVariant)
    }
    val ink = if (enabled) BrandInk else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .clip(shape)
            .background(brush, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(17.dp), tint = ink)
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = ink,
        )
    }
}

/** Small translucent tag used for model / session / connection chips. */
@Composable
fun InfoPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
) {
    val shape = CircleShape
    val base = modifier
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), shape)
        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 9.dp, vertical = 3.dp)
    Row(base, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}
