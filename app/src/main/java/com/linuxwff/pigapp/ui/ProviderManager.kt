package com.linuxwff.pigapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linuxwff.pigapp.AppUiState
import com.linuxwff.pigapp.AppViewModel
import com.linuxwff.pigapp.ProviderInfo
import com.linuxwff.pigapp.ProviderModel

/** API spellings the custom-endpoints extension accepts, with their labels. */
private val providerApis = listOf(
    "openai-completions" to "OpenAI Chat Completions",
    "openai-responses" to "OpenAI Responses",
    "anthropic-messages" to "Anthropic Messages",
    "google-generative-ai" to "Google Generative AI",
    "google-vertex" to "Google Vertex",
    "mistral-conversations" to "Mistral Conversations",
    "azure-openai-responses" to "Azure OpenAI Responses",
    "openai-codex-responses" to "OpenAI Codex Responses",
    "bedrock-converse-stream" to "Bedrock Converse Stream",
)

/**
 * Native model-provider manager.
 *
 * The UI pack owns only the entry point (`nav.json` → `window: native:providers`);
 * reading and writing `~/.pig/agent/custom-endpoints.json`, fetching `/models`
 * and picking which models to enable all happen here and in the relay, so the
 * pack cannot break the form with a layout change.
 */
@Composable
fun ProviderManagerDialog(vm: AppViewModel, ui: AppUiState, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<ProviderInfo?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProviderInfo?>(null) }
    // Opening the editor from a card's "刷新" starts the fetch right away and
    // pops the model picker when it lands.
    var refreshOnOpen by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.96f),
                shape = RoundedCornerShape(ui.uiPack.theme.cornerRadius.dp),
                color = scheme.surface,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (editing != null || creating) {
                                packText("provider.editTitle", "编辑供应商")
                            } else {
                                packText("provider.title", "模型供应商")
                            },
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (editing == null && !creating) {
                            IconButton(onClick = vm::requestProviders, enabled = !ui.providerBusy) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = packText("provider.refreshList", "刷新列表"),
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                if (editing != null || creating) {
                                    editing = null
                                    creating = false
                                    refreshOnOpen = false
                                } else {
                                    onDismiss()
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = packText("a11y.close", "关闭"),
                            )
                        }
                    }
                    if (ui.providerBusy) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                        )
                    }
                    if (editing != null || creating) {
                        ProviderEditor(
                            vm = vm,
                            ui = ui,
                            initial = editing,
                            startRefresh = refreshOnOpen,
                            modifier = Modifier.weight(1f),
                            onBack = {
                                editing = null
                                creating = false
                                refreshOnOpen = false
                            },
                            onDelete = { deleting = it },
                        )
                    } else {
                        ProviderList(
                            vm = vm,
                            ui = ui,
                            modifier = Modifier.weight(1f),
                            onEdit = { editing = it },
                            onCreate = { creating = true },
                            onDelete = { deleting = it },
                            onRefresh = { provider ->
                                refreshOnOpen = true
                                editing = provider
                                vm.refreshProviderModels(provider, apiKey = "")
                            },
                        )
                    }
                }
            }
        }
    }
    deleting?.let { provider ->
        PackAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(packText("provider.deleteTitle", "删除供应商")) },
            text = {
                Text(
                    packText(
                        "provider.deleteBody",
                        "从 custom-endpoints.json 删除 {id}？当前会话会在 pig 重启后失去它。",
                    ).replace("{id}", provider.id),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    editing = null
                    creating = false
                    vm.deleteProvider(provider.id)
                }) {
                    Text(packText("provider.deleteAction", "删除"), color = scheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(packText("dialog.cancel", "取消"))
                }
            },
        )
    }
}

@Composable
private fun ProviderList(
    vm: AppViewModel,
    ui: AppUiState,
    modifier: Modifier = Modifier,
    onEdit: (ProviderInfo) -> Unit,
    onCreate: () -> Unit,
    onDelete: (ProviderInfo) -> Unit,
    onRefresh: (ProviderInfo) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            packText(
                "provider.hint",
                "保存到 custom-endpoints.json；改动会在重启 pig 后生效。",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        if (ui.providersOffline) {
            Text(
                packText("provider.offlineHint", "离线模式（PIG_OFFLINE）：无法刷新模型列表"),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.error,
            )
        }
        if (!ui.providerRemote && ui.connected) {
            Text(
                packText(
                    "provider.needRelay",
                    "需要新版 relay 才能管理供应商：重装 Termux 组件后重启 relay",
                ),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.error,
            )
        }
        if (ui.providerMessage.isNotBlank()) {
            Text(
                ui.providerMessage,
                style = MaterialTheme.typography.labelSmall,
                color = if (ui.providerMessageError) scheme.error else scheme.primary,
            )
        }
        OutlinedButton(
            onClick = onCreate,
            enabled = ui.providerRemote && !ui.providerBusy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape((ui.uiPack.theme.cornerRadius / 2).coerceAtLeast(8).dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(packText("provider.add", "新建供应商"))
        }
        if (ui.providersLoading && ui.providers.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp))
            }
        } else if (ui.providers.isEmpty()) {
            Text(
                packText("provider.empty", "还没有自定义供应商。"),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        ui.providers.forEach { provider ->
            ProviderCard(
                provider = provider,
                enabled = ui.providerRemote && !ui.providerBusy,
                onEdit = { onEdit(provider) },
                onRefresh = { onRefresh(provider) },
                onDelete = { onDelete(provider) },
            )
        }
        if (ui.providersModelsFile.isNotEmpty()) {
            HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
            Text(
                packText(
                    "provider.modelsFile",
                    "models.json 里的供应商（这里只读）：{ids}",
                ).replace("{ids}", ui.providersModelsFile.joinToString(", ")),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (ui.providersPath.isNotBlank()) {
            Text(
                ui.providersPath.substringAfterLast('/'),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderInfo,
    enabled: Boolean,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    provider.name.ifBlank { provider.id },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    provider.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                provider.baseUrl.ifBlank { "（没有 baseUrl）" },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(provider.api.ifBlank { "openai-completions" })
                    append(" · ")
                    append(
                        if (provider.auto) {
                            packText("provider.modeAuto", "自动全部")
                        } else {
                            packText("provider.modeManual", "手动选择")
                        },
                    )
                    append(" · ")
                    append(provider.models.size)
                    append(" ")
                    append(packText("provider.models", "个模型"))
                    if (!provider.hasKey) {
                        append(" · ")
                        append(packText("provider.noKey", "无 Key"))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (provider.models.isEmpty()) scheme.error else scheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onEdit, enabled = enabled) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(packText("provider.edit", "编辑"))
                }
                TextButton(onClick = onRefresh, enabled = enabled) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(packText("provider.refresh", "刷新"))
                }
                TextButton(onClick = onDelete, enabled = enabled) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = scheme.error,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(packText("provider.delete", "删除"), color = scheme.error)
                }
            }
        }
    }
}

@Composable
private fun ProviderEditor(
    vm: AppViewModel,
    ui: AppUiState,
    initial: ProviderInfo?,
    startRefresh: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onDelete: (ProviderInfo) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val creating = initial == null
    var id by remember { mutableStateOf(initial?.id ?: "") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var api by remember { mutableStateOf(initial?.api?.ifBlank { "openai-completions" } ?: "openai-completions") }
    var modelsUrl by remember { mutableStateOf(initial?.modelsUrl ?: "") }
    var auto by remember { mutableStateOf(initial?.auto ?: true) }
    var apiKey by remember { mutableStateOf("") }
    var clearKey by remember { mutableStateOf(false) }
    // Everything ever fetched is the picker's source; `selected` is the subset
    // that will be written as the provider's model list.
    var allModels by remember {
        mutableStateOf(
            initial?.cachedModels?.ifEmpty { initial?.models ?: emptyList() } ?: emptyList(),
        )
    }
    var selected by remember {
        mutableStateOf(
            if (initial == null || initial.auto) {
                allModels.map { it.id }.toSet()
            } else {
                initial.models.map { it.id }.toSet()
            },
        )
    }
    var showApiPicker by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var waitForPicker by remember { mutableStateOf(startRefresh) }
    val startEpoch = remember { ui.providerEpoch }
    val draftId = id.trim().lowercase()

    // The relay answers a refresh asynchronously; fold its list in and, when
    // the user asked for it, open the picker in the same beat.
    LaunchedEffect(ui.providerBusy, ui.providerModelsFor, ui.providerModels, ui.providerModelsAt) {
        if (ui.providerBusy) return@LaunchedEffect
        if (ui.providerModelsFor != draftId || ui.providerModels.isEmpty()) return@LaunchedEffect
        allModels = ui.providerModels
        if (auto) {
            selected = allModels.map { it.id }.toSet()
        } else {
            val available = allModels.map { it.id }.toSet()
            val kept = selected.intersect(available)
            selected = if (kept.isEmpty()) available else kept
        }
        if (waitForPicker) {
            waitForPicker = false
            showModelPicker = true
        }
    }
    LaunchedEffect(ui.providerEpoch) {
        if (ui.providerEpoch != startEpoch) onBack()
    }

    val draft = ProviderInfo(
        id = id,
        name = name,
        baseUrl = baseUrl,
        api = api,
        hasKey = initial?.hasKey ?: false,
        auto = auto,
        modelsUrl = modelsUrl,
        models = allModels,
        cachedModels = allModels,
        fetchedAt = ui.providerModelsAt,
    )
    val canSave = draft.id.isNotBlank() && draft.baseUrl.isNotBlank() &&
        !ui.providerBusy && (auto || selected.isNotEmpty())

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.providerMessage.isNotBlank()) {
                Text(
                    ui.providerMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ui.providerMessageError) scheme.error else scheme.primary,
                )
            }
            OutlinedTextField(
                value = id,
                onValueChange = { value -> id = value.filter { it.isLetterOrDigit() || it in "._-" }.lowercase().take(32) },
                label = { Text(packText("provider.id", "ID")) },
                placeholder = { Text("my-endpoint") },
                singleLine = true,
                enabled = creating,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text(packText("provider.name", "显示名称")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it.trim().take(400) },
                label = { Text(packText("provider.baseUrl", "Base URL")) },
                placeholder = { Text("https://api.example.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it.trim().take(400); if (it.isNotBlank()) clearKey = false },
                label = { Text(packText("provider.apiKey", "API Key")) },
                placeholder = {
                    Text(
                        if (initial?.hasKey == true) {
                            packText("provider.keySaved", "已保存，留空保持不变")
                        } else {
                            "sk-… 或 \$ENV"
                        },
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (initial?.hasKey == true && apiKey.isBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = clearKey, onCheckedChange = { clearKey = it })
                    Text(
                        packText("provider.clearKey", "清除已保存的 API Key"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(packText("provider.apiType", "API 类型"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        providerApis.firstOrNull { it.first == api }?.second ?: api,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showApiPicker = true }) {
                    Text(packText("provider.change", "更改"))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        packText("provider.autoAll", "自动获取全部模型"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        packText(
                            "provider.autoAllHint",
                            "从 {baseUrl}/models 获取并在启动时注册全部模型；关掉后只启用你勾选的模型",
                        ).replace("{baseUrl}", baseUrl.ifBlank { "…" }),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = auto,
                    onCheckedChange = { checked ->
                        auto = checked
                        if (checked) selected = allModels.map { it.id }.toSet()
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        waitForPicker = true
                        vm.refreshProviderModels(draft, apiKey)
                    },
                    enabled = !ui.providerBusy && baseUrl.isNotBlank(),
                ) {
                    Text(packText("provider.refreshModels", "刷新模型列表"))
                }
                OutlinedButton(
                    onClick = { showModelPicker = true },
                    enabled = !ui.providerBusy && allModels.isNotEmpty(),
                ) {
                    Text(
                        packText("provider.pickModels", "选择模型（{n}）")
                            .replace("{n}", selected.size.toString()),
                    )
                }
            }
            if (!auto) {
                Text(
                    packText("provider.selectedCount", "启用 {selected} / 可用 {total} 个模型")
                        .replace("{selected}", selected.size.toString())
                        .replace("{total}", allModels.size.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected.isEmpty()) scheme.error else scheme.onSurfaceVariant,
                )
            }
            if (auto) {
                OutlinedTextField(
                    value = modelsUrl,
                    onValueChange = { modelsUrl = it.trim().take(400) },
                    label = { Text(packText("provider.modelsUrl", "自定义 models 地址（可选）")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
            Text(
                packText(
                    "provider.secretHint",
                    "Key 以明文写入 custom-endpoints.json（在 Termux 私有目录里）；也支持 \$ENV 或 !命令。",
                ),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!creating) {
                TextButton(
                    onClick = { onDelete(initial!!) },
                    enabled = !ui.providerBusy,
                ) {
                    Text(packText("provider.delete", "删除"), color = scheme.error)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack, enabled = !ui.providerBusy) {
                Text(packText("dialog.cancel", "取消"))
            }
            Button(
                onClick = {
                    vm.saveProvider(
                        draft = draft.copy(id = draftId),
                        apiKey = apiKey,
                        clearKey = clearKey,
                        allModels = allModels,
                        selected = allModels.filter { it.id in selected },
                    )
                },
                enabled = canSave,
            ) {
                Text(packText("provider.save", "保存"))
            }
        }
    }

    if (showApiPicker) {
        ApiPickerDialog(
            current = api,
            onPick = {
                api = it
                showApiPicker = false
            },
            onDismiss = { showApiPicker = false },
        )
    }
    if (showModelPicker) {
        ProviderModelPickerDialog(
            models = allModels,
            initial = selected,
            onConfirm = { picked ->
                selected = picked
                // Picking a subset means manual mode: leaving auto on would
                // register every fetched model again.
                if (picked.size < allModels.size) auto = false
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }
}

@Composable
private fun ApiPickerDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    PackAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(packText("provider.apiType", "API 类型")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                providerApis.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(value) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = current == value, onClick = { onPick(value) })
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                value,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(packText("dialog.close", "关闭"))
            }
        },
    )
}

/**
 * The "selection window" for models: a searchable, checkable list. It is opened
 * after a refresh (so the user sees what the endpoint actually returns) or from
 * 选择模型 with the cached list.
 */
@Composable
private fun ProviderModelPickerDialog(
    models: List<ProviderModel>,
    initial: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(initial) }
    val filtered = remember(query, models) {
        if (query.isBlank()) {
            models
        } else {
            models.filter {
                it.id.contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true)
            }
        }
    }
    PackAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                packText("provider.pickTitle", "选择要启用的模型（{n}/{total}）")
                    .replace("{n}", picked.size.toString())
                    .replace("{total}", models.size.toString()),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(packText("provider.searchModels", "搜索模型")) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { picked = filtered.map { it.id }.toSet() }) {
                        Text(packText("provider.selectAll", "全选"))
                    }
                    TextButton(onClick = { picked = emptySet() }) {
                        Text(packText("provider.selectNone", "清空"))
                    }
                }
                HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                if (filtered.isEmpty()) {
                    Text(
                        packText("provider.noModels", "没有匹配的模型"),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(filtered, key = { it.id }) { model ->
                        val checked = model.id in picked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    picked = if (checked) picked - model.id else picked + model.id
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    picked = if (on) picked + model.id else picked - model.id
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    model.name.ifBlank { model.id },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    buildString {
                                        append(model.id)
                                        if (model.contextWindow > 0) {
                                            append(" · ctx ")
                                            append(formatContext(model.contextWindow))
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(picked) },
                enabled = picked.isNotEmpty(),
            ) {
                Text(
                    packText("provider.enable", "启用 {n} 个").replace("{n}", picked.size.toString()),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(packText("dialog.cancel", "取消"))
            }
        },
    )
}

private fun formatContext(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%.1f".format(tokens / 1_000_000f).removeSuffix(".0") + "M"
    tokens >= 1000 -> "${tokens / 1000}K"
    else -> tokens.toString()
}
