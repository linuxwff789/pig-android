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
    val models = vm.models
    val filtered = remember(query, models) {
        if (query.isBlank()) {
            models
        } else {
            models.filter {
                "${it.provider}/${it.id} ${it.name}".contains(query.trim(), ignoreCase = true)
            }
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
                    Text("选择模型", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (vm.modelsLoading) "正在加载模型…" else "${filtered.size} 个可用",
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
                    placeholder = { Text("搜索 provider 或模型") },
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
                if (vm.ui.thinkingLevels.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "思考档位 · ${vm.ui.thinkingLevel}",
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
                }
                if (vm.modelsLoading) {
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
                                "没有匹配的模型",
                                modifier = Modifier.padding(vertical = 10.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(filtered, key = { "${it.provider}/${it.id}" }) { model ->
                        ModelRow(
                            model = model,
                            selected = "${model.provider}/${model.id}" == vm.ui.model,
                            cornerRadius = corner,
                            onClick = { vm.selectModel(model) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = vm::closeModelPicker) { Text("关闭") }
        },
        dismissButton = {
            TextButton(onClick = vm::requestModels) { Text("刷新") }
        },
    )
}

@Composable
private fun ModelRow(model: ModelInfo, selected: Boolean, cornerRadius: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape((cornerRadius - 4).coerceAtLeast(8).dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(shape)
            .background(if (selected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
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
            Text(
                buildString {
                    append("ctx ")
                    append(formatContext(model.contextWindow))
                    if (model.reasoning) append(" · think")
                    if (model.images) append(" · img")
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
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

private fun formatContext(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%.1f".format(tokens / 1_000_000f).removeSuffix(".0") + "M"
    tokens >= 1000 -> "${tokens / 1000}K"
    tokens > 0 -> "$tokens"
    else -> "?"
}
