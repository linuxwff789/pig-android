package com.linuxwff.pigapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linuxwff.pigapp.AppUiState
import com.linuxwff.pigapp.AppViewModel
import com.linuxwff.pigapp.UiHeader
import com.linuxwff.pigapp.UiHeaderElement
import com.linuxwff.pigapp.resolveUiPath
import com.linuxwff.pigapp.UiHeaderRow
import com.linuxwff.pigapp.formatRpcValue

/**
 * Renders `layout/chat.json` → `header`. Every element is opt-in here, so a
 * pack can reorder, hide, resize or replace any part of the built-in header.
 */
@Composable
fun CustomPigHeader(
    header: UiHeader,
    ui: AppUiState,
    vm: AppViewModel,
    onMenu: () -> Unit,
    onModel: () -> Unit,
    onReconnect: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onNewSession: () -> Unit,
    onPackAction: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val base = when (header.background) {
        "surface" -> scheme.surface
        "transparent" -> Color.Transparent
        else -> scheme.background
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(base)
            .statusBarsPadding(),
    ) {
        if (header.background == "gradient") {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                scheme.primary.copy(alpha = header.gradientAlpha),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = header.heightDp.dp)
                .padding(horizontal = header.paddingH.dp, vertical = header.paddingV.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val body = header.body
            if (body != null) {
                UiNodeView(
                    node = body,
                    windowId = "__header__",
                    vm = vm,
                    onAction = { action ->
                        when {
                            action.startsWith("header:") -> runHeaderAction(
                                action.removePrefix("header:"),
                                onMenu,
                                onModel,
                                onReconnect,
                                onStop,
                                onSettings,
                                onNewSession,
                                onPackAction,
                            )
                            action.startsWith("pack:") -> onPackAction(action.removePrefix("pack:"))
                            else -> vm.handleWindowAction("__header__", action)
                        }
                    },
                )
            } else {
                header.rows.forEach { row ->
                    HeaderRow(
                        row = row,
                        ui = ui,
                        onMenu = onMenu,
                        onModel = onModel,
                        onReconnect = onReconnect,
                        onStop = onStop,
                        onSettings = onSettings,
                        onNewSession = onNewSession,
                        onPackAction = onPackAction,
                    )
                }
            }
            if (header.divider) {
                HorizontalDivider(color = scheme.outline.copy(alpha = 0.22f))
            }
        }
    }
}

@Composable
private fun HeaderRow(
    row: UiHeaderRow,
    ui: AppUiState,
    onMenu: () -> Unit,
    onModel: () -> Unit,
    onReconnect: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onNewSession: () -> Unit,
    onPackAction: (String) -> Unit,
) {
    val arrangement = when (row.align) {
        "center" -> Arrangement.spacedBy(row.spacingDp.dp, Alignment.CenterHorizontally)
        "end" -> Arrangement.spacedBy(row.spacingDp.dp, Alignment.End)
        else -> Arrangement.spacedBy(row.spacingDp.dp)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = row.heightDp.dp),
        horizontalArrangement = arrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.elements.forEach { element ->
            if (!headerElementVisible(element, ui)) return@forEach
            when {
                element.type == "spacer" -> Spacer(
                    Modifier.weight(element.weight.takeIf { it > 0f } ?: 1f),
                )
                element.weight > 0f -> Box(Modifier.weight(element.weight)) {
                    HeaderElement(element, ui, onMenu, onModel, onReconnect, onStop, onSettings, onNewSession, onPackAction)
                }
                else -> HeaderElement(element, ui, onMenu, onModel, onReconnect, onStop, onSettings, onNewSession, onPackAction)
            }
        }
    }
}

@Composable
private fun HeaderElement(
    element: UiHeaderElement,
    ui: AppUiState,
    onMenu: () -> Unit,
    onModel: () -> Unit,
    onReconnect: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onNewSession: () -> Unit,
    onPackAction: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val modifier = Modifier.padding(element.padding.dp)
    val bindText = element.bind.takeIf { it.isNotBlank() }?.let { path ->
        formatRpcValue(
            resolveUiPath(ui, path),
            element.format,
            element.text.ifBlank { element.label },
        )
    }
    val label = bindText ?: element.text.ifBlank { element.label }
    val color = headerColor(element.color, scheme)
    val action = element.action
    val clickable = Modifier.then(
        if (action.isNotBlank()) {
            Modifier.clickable {
                runHeaderAction(action, onMenu, onModel, onReconnect, onStop, onSettings, onNewSession, onPackAction)
            }
        } else {
            Modifier
        },
    )
    when (element.type) {
        "menu" -> IconButton(
            onClick = onMenu,
            modifier = modifier.size((element.size.takeIf { it > 0 } ?: 22).dp),
        ) {
            Icon(packIcon(element.icon.ifBlank { "menu" }, "menu"), null, modifier = Modifier.size((element.size.takeIf { it > 0 } ?: 22).dp))
        }

        "brand" -> Row(
            modifier.then(clickable),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (element.avatar) {
                BrandAvatar((element.avatarSize.takeIf { it > 0 } ?: 36).dp)
                Spacer(Modifier.width(8.dp))
            }
            Column {
                Text(
                    label.ifBlank { ui.titleOverride ?: packText("header.title", "Pig") },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (element.online) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConnectionDot(ui.connected)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (ui.connected) packText("header.online", "在线") else packText("settings.offline", "离线"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ui.connected) scheme.tertiary else scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        "title" -> Text(
            label.ifBlank { ui.titleOverride ?: packText("header.title", "Pig") },
            modifier = modifier,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        "model" -> Row(
            modifier.then(clickable),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label.ifBlank { ui.model.ifBlank { packText("header.modelEmpty", "点击选择模型") } },
                style = MaterialTheme.typography.labelSmall,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                packIcon("chevronDown", "down"),
                null,
                modifier = Modifier.size(14.dp),
                tint = color,
            )
        }

        "usage" -> Text(
            label.ifBlank { defaultUsage(ui) },
            modifier = modifier,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )

        "cache" -> Text(
            label.ifBlank { defaultCache(ui) },
            modifier = modifier,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )

        "connection" -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            ConnectionDot(ui.connected)
            Spacer(Modifier.width(5.dp))
            Text(
                label.ifBlank {
                    if (ui.connected) packText("header.online", "在线") else packText("settings.offline", "离线")
                },
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }

        "status" -> Text(
            label.ifBlank { statusLabel(ui.status) },
            modifier = modifier,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )

        "stop" -> IconButton(
            onClick = onStop,
            modifier = modifier.size((element.size.takeIf { it > 0 } ?: 22).dp),
        ) {
            Icon(packIcon(element.icon.ifBlank { "stop" }, "stop"), null, modifier = Modifier.size((element.size.takeIf { it > 0 } ?: 22).dp), tint = scheme.error)
        }

        "reconnect" -> IconButton(
            onClick = onReconnect,
            modifier = modifier.size((element.size.takeIf { it > 0 } ?: 22).dp),
        ) {
            Icon(packIcon(element.icon.ifBlank { "refresh" }, "refresh"), null, modifier = Modifier.size((element.size.takeIf { it > 0 } ?: 22).dp), tint = color)
        }

        "settings" -> IconButton(
            onClick = onSettings,
            modifier = modifier.size((element.size.takeIf { it > 0 } ?: 22).dp),
        ) {
            Icon(packIcon(element.icon.ifBlank { "settings" }, "settings"), null, modifier = Modifier.size((element.size.takeIf { it > 0 } ?: 22).dp), tint = color)
        }

        "newSession" -> IconButton(
            onClick = onNewSession,
            modifier = modifier.size((element.size.takeIf { it > 0 } ?: 22).dp),
        ) {
            Icon(packIcon(element.icon.ifBlank { "add" }, "add"), null, modifier = Modifier.size((element.size.takeIf { it > 0 } ?: 22).dp), tint = color)
        }

        "icon" -> Icon(
            packIcon(element.icon.ifBlank { "more" }, element.icon.ifBlank { "more" }),
            contentDescription = label.takeIf { it.isNotBlank() },
            modifier = modifier
                .size((element.size.takeIf { it > 0 } ?: 18).dp)
                .then(clickable),
            tint = color,
        )

        "text" -> Text(
            label,
            modifier = modifier.then(clickable),
            style = headerTextStyle(element.style),
            color = if (element.style == "danger") scheme.error else color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        "pill" -> InfoPill(
            text = label,
            modifier = modifier.then(clickable),
            color = color,
        )

        "divider" -> Box(
            Modifier
                .padding(horizontal = 2.dp)
                .width(1.dp)
                .height((element.size.takeIf { it > 0 } ?: 18).dp)
                .background(scheme.outline.copy(alpha = 0.45f)),
        )

        else -> Text(
            "[${element.type}]",
            modifier = modifier,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.error,
        )
    }
}

private fun headerElementVisible(element: UiHeaderElement, ui: AppUiState): Boolean {
    if (!element.visible) return false
    return when (element.condition) {
        "busy" -> ui.busy
        "idle" -> !ui.busy
        "connected" -> ui.connected
        "offline" -> !ui.connected
        "streaming" -> ui.streamingId != null
        "compacting" -> ui.status == "compacting" || ui.contextCompacted
        else -> true
    }
}

private fun runHeaderAction(
    action: String,
    onMenu: () -> Unit,
    onModel: () -> Unit,
    onReconnect: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onNewSession: () -> Unit,
    onPackAction: (String) -> Unit,
) {
    when {
        action == "menu" -> onMenu()
        action == "model" -> onModel()
        action == "reconnect" -> onReconnect()
        action == "stop" || action == "abort" -> onStop()
        action == "settings" -> onSettings()
        action == "new" || action == "new_session" -> onNewSession()
        action.startsWith("pack:") -> onPackAction(action.removePrefix("pack:"))
        action.startsWith("header:") -> runHeaderAction(
            action.removePrefix("header:"),
            onMenu,
            onModel,
            onReconnect,
            onStop,
            onSettings,
            onNewSession,
            onPackAction,
        )
    }
}

private fun headerColor(name: String, scheme: androidx.compose.material3.ColorScheme): Color = when (name) {
    "primary" -> scheme.primary
    "secondary" -> scheme.secondary
    "tertiary" -> scheme.tertiary
    "onSurface" -> scheme.onSurface
    "error" -> scheme.error
    "success" -> scheme.tertiary
    "warning" -> scheme.secondary
    else -> scheme.onSurfaceVariant
}

@Composable
private fun headerTextStyle(style: String) = when (style) {
    "body" -> MaterialTheme.typography.bodySmall
    "heading" -> MaterialTheme.typography.titleSmall
    "mono" -> MaterialTheme.typography.labelMedium.copy(fontFamily = packCodeFont())
    "muted" -> MaterialTheme.typography.labelSmall
    "danger" -> MaterialTheme.typography.labelSmall
    else -> MaterialTheme.typography.labelSmall
}

@Composable
private fun defaultUsage(ui: AppUiState): String {
    if (ui.contextCompacted) return packText("header.compacted", "已压缩")
    val percent = ui.contextPercent ?: return ""
    return if (ui.contextWindow > 0) {
        "${"%.0f".format(percent)}% · ${headerTokens(ui.contextTokens)}/${headerTokens(ui.contextWindow)}"
    } else {
        "${"%.0f".format(percent)}%"
    }
}

@Composable
private fun defaultCache(ui: AppUiState): String = ui.cacheHitRate?.let {
    packText("header.cache", "缓存 {rate}%").replace("{rate}", "%.0f".format(it))
} ?: ""

private fun headerTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%.1f".format(tokens / 1_000_000f).removeSuffix(".0") + "M"
    tokens >= 1000 -> "${tokens / 1000}K"
    else -> tokens.toString()
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    "thinking" -> packText("status.thinking", "思考中")
    "working" -> packText("status.working", "工作中")
    "compacting" -> packText("status.compacting", "压缩上下文")
    "retrying" -> packText("status.retrying", "重试中")
    "switching" -> packText("status.switching", "切换中")
    "restoring" -> packText("status.restoring", "恢复中")
    else -> status
}
