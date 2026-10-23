package com.linuxwff.pigapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linuxwff.pigapp.AppViewModel
import com.linuxwff.pigapp.ModelInfo

@Composable
fun ModelPickerDialog(vm: AppViewModel) {
    if (!vm.showModelPicker) return
    var query by rememberSaveable { mutableStateOf("") }
    var contextTarget by remember { mutableStateOf<ModelInfo?>(null) }
    val models = vm.models
    val filtered = remember(query, models) {
        models.filter { model ->
            query.isBlank() ||
                "${model.provider}/${model.id} ${model.name}".contains(
                    query.trim(),
                    ignoreCase = true,
                )
        }
    }
    val scheme = MaterialTheme.colorScheme
    val corner = vm.ui.uiPack.theme.cornerRadius

    AlertDialog(
        onDismissRequest = vm::closeModelPicker,
        shape = RoundedCornerShape(26.dp),
        containerColor = scheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(packText("model.title", "选择模型"), style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (vm.ui.modelSwitchPending) {
                            packText("model.switching", "切换中…")
                        } else if (vm.modelsLoading) {
                            packText("model.loading", "正在加载模型…")
                        } else {
                            packText("model.count", "{count} 个可用")
                                .replace("{count}", filtered.size.toString())
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (vm.ui.model.isNotBlank()) {
                    InfoPill(
                        text = vm.ui.model.substringAfter('/'),
                        color = scheme.secondary,
                    )
                }
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(packText("model.search", "搜索 provider 或模型")) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = scheme.primary.copy(alpha = 0.7f),
                        unfocusedBorderColor = scheme.outline.copy(alpha = 0.5f),
                        focusedContainerColor = scheme.surfaceVariant.copy(alpha = 0.35f),
                        unfocusedContainerColor = scheme.surfaceVariant.copy(alpha = 0.35f),
                        cursorColor = scheme.primary,
                    ),
                )
                if (vm.ui.thinkingLevels.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        packText("model.thinking", "思考档位 · {level}")
                            .replace("{level}", vm.ui.thinkingLevel),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        vm.ui.thinkingLevels.forEach { level ->
                            FilterChip(
                                selected = vm.ui.thinkingLevel == level,
                                enabled = !vm.ui.modelSwitchPending,
                                onClick = { vm.setThinkingLevel(level) },
                                label = { Text(level) },
                                shape = CircleShape,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = scheme.primary.copy(alpha = 0.18f),
                                    selectedLabelColor = scheme.primary,
                                ),
                            )
                        }
                    }
                    if (vm.ui.thinkingLevel == "off" && vm.ui.upstreamIgnoresOff) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            packText(
                                "model.offIgnored",
                                "该上游在 off 档仍会返回思考内容；顶栏显示的是请求档位。",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    if (vm.ui.thinkingLevels.size <= 1) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            packText(
                                "model.thinkingUnsupported",
                                "当前模型不支持思考档位；换一个支持 reasoning 的模型即可。",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        packText("model.thinkingUnsupported", "当前模型不支持思考档位。"),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (vm.modelsLoading || vm.ui.modelSwitchPending) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                ) {
                    if (filtered.isEmpty() && !vm.modelsLoading) {
                        item {
                            Text(
                                packText("model.empty", "没有匹配的模型"),
                                modifier = Modifier.padding(vertical = 10.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(filtered, key = { "${it.provider}/${it.id}" }) { model ->
                        val overridden = vm.contextWindowFor(model.provider, model.id)
                        ModelRow(
                            model = model,
                            selected = "${model.provider}/${model.id}" == vm.ui.model,
                            cornerRadius = corner,
                            enabled = !vm.ui.modelSwitchPending,
                            contextOverride = overridden,
                            onClick = { vm.selectModel(model) },
                            onEditContext = { contextTarget = model },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = vm::closeModelPicker) {
                Text(packText("a11y.close", "关闭"))
            }
        },
        dismissButton = {
            TextButton(onClick = vm::requestModels) {
                Text(packText("a11y.refresh", "刷新"))
            }
        },
    )

    contextTarget?.let { model ->
        ContextWindowDialog(
            model = model,
            override = vm.contextWindowFor(model.provider, model.id),
            onSave = { tokens ->
                vm.setContextWindow(model.provider, model.id, tokens)
                contextTarget = null
            },
            onReset = {
                vm.setContextWindow(model.provider, model.id, 0)
                contextTarget = null
            },
            onDismiss = { contextTarget = null },
        )
    }
}

@Composable
private fun ModelRow(
    model: ModelInfo,
    selected: Boolean,
    cornerRadius: Int,
    enabled: Boolean,
    contextOverride: Int,
    onClick: () -> Unit,
    onEditContext: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape((cornerRadius - 4).coerceAtLeast(8).dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(shape)
            .background(if (selected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(scheme.tertiary.copy(alpha = 0.14f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        model.provider,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.tertiary,
                    )
                }
                if (model.reasoning) {
                    Spacer(Modifier.width(5.dp))
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(scheme.secondary.copy(alpha = 0.14f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            packText("model.reasoning", "思考"),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.secondary,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    model.id,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else null,
                    color = if (selected) scheme.primary else scheme.onSurface,
                )
            }
            val window = if (contextOverride > 0) contextOverride else model.contextWindow
            Row(
                Modifier
                    .clip(CircleShape)
                    .clickable(enabled = enabled, onClick = onEditContext)
                    .padding(vertical = 2.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append("ctx ")
                        append(formatContext(window))
                        if (contextOverride > 0) {
                            append(" · ")
                            append(packText("model.contextCustom", "自定义"))
                        }
                        if (model.reasoning) append(" · think")
                        if (model.images) append(" · img")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (contextOverride > 0) scheme.primary else scheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.Edit,
                    contentDescription = packText("model.contextEdit", "设置上下文长度"),
                    modifier = Modifier.size(12.dp),
                    tint = if (contextOverride > 0) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = scheme.primary,
            )
        }
    }
}

@Composable
private fun ContextWindowDialog(
    model: ModelInfo,
    override: Int,
    onSave: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var text by remember {
        mutableStateOf(if (override > 0) override.toString() else "")
    }
    val parsed = parseTokenCount(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = scheme.surface,
        title = { Text(packText("model.contextTitle", "上下文长度")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${model.provider}/${model.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    packText(
                        "model.contextHint",
                        "provider 上报：{value}。上报不准时可手动指定，支持 128K / 1M；留空恢复自动检测。",
                    ).replace(
                        "{value}",
                        if (model.contextWindow > 0) formatContext(model.contextWindow) else "未知",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input.filter { it.isDigit() || it.equals('k', true) || it.equals('m', true) }
                    },
                    singleLine = true,
                    label = { Text("tokens") },
                    placeholder = { Text("例如 131072 / 128K / 1M") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = scheme.primary.copy(alpha = 0.7f),
                        unfocusedBorderColor = scheme.outline.copy(alpha = 0.5f),
                    ),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("32K", "64K", "128K", "200K", "256K", "512K", "1M").forEach { preset ->
                        FilterChip(
                            selected = text.equals(preset, ignoreCase = true),
                            onClick = { text = preset },
                            label = { Text(preset) },
                            shape = CircleShape,
                        )
                    }
                }
                Text(
                    if (parsed > 0) {
                        "= $parsed tokens (${formatContext(parsed)})"
                    } else {
                        packText("model.contextAuto", "留空 = 使用 provider 上报值")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (parsed > 0) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = parsed > 0, onClick = { onSave(parsed) }) {
                Text(packText("dialog.save", "保存"))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) {
                    Text(packText("model.contextReset", "恢复自动"))
                }
                TextButton(onClick = onDismiss) {
                    Text(packText("dialog.cancel", "取消"))
                }
            }
        },
    )
}

private fun parseTokenCount(raw: String): Int {
    val text = raw.trim()
    if (text.isEmpty()) return 0
    val multiplier = when {
        text.endsWith("k", true) -> 1000
        text.endsWith("m", true) -> 1_000_000
        else -> 1
    }
    val number = if (multiplier == 1) text else text.dropLast(1)
    val value = number.toLongOrNull() ?: return 0
    val tokens = value * multiplier
    return if (tokens in 1..Int.MAX_VALUE) tokens.toInt() else 0
}

private fun formatContext(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%.1f".format(tokens / 1_000_000f).removeSuffix(".0") + "M"
    tokens >= 1000 -> "${tokens / 1000}K"
    tokens > 0 -> "$tokens"
    else -> "?"
}
