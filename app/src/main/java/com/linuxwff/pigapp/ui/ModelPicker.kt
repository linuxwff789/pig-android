package com.linuxwff.pigapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

    AlertDialog(
        onDismissRequest = vm::closeModelPicker,
        title = { Text("选择模型") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索 provider 或模型") },
                    singleLine = true,
                )
                if (vm.ui.thinkingLevels.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "思考档位 · ${vm.ui.thinkingLevel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            )
                        }
                    }
                }
                if (vm.modelsLoading) {
                    LinearProgressIndicator(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                    )
                }
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                ) {
                    if (filtered.isEmpty() && !vm.modelsLoading) {
                        item {
                            Text(
                                "没有匹配的模型",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(filtered, key = { "${it.provider}/${it.id}" }) { model ->
                        ModelRow(
                            model = model,
                            selected = "${model.provider}/${model.id}" == vm.ui.model,
                            cornerRadius = vm.ui.uiPack.theme.cornerRadius,
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
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape((cornerRadius / 2).coerceAtLeast(4).dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${model.provider}/${model.id}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                buildString {
                    append("ctx ")
                    append(formatContext(model.contextWindow))
                    if (model.reasoning) append(" · think")
                    if (model.images) append(" · img")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Text("●", color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun formatContext(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%.1f".format(tokens / 1_000_000f).removeSuffix(".0") + "M"
    tokens >= 1000 -> "${tokens / 1000}K"
    tokens > 0 -> "$tokens"
    else -> "?"
}
