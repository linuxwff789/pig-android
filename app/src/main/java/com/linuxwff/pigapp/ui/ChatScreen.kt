package com.linuxwff.pigapp.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linuxwff.pigapp.AppUiState
import com.linuxwff.pigapp.AppViewModel
import com.linuxwff.pigapp.ChatMsg
import com.linuxwff.pigapp.Role
import com.linuxwff.pigapp.SessionInfo
import com.linuxwff.pigapp.UiLayout
import com.linuxwff.pigapp.UiRequest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PigApp(vm: AppViewModel) {
    val ui = vm.ui
    val subtitle = buildString {
        append(
            when {
                ui.connected && ui.model.isNotBlank() -> ui.model
                ui.connected -> "connected"
                else -> "offline"
            },
        )
        if (ui.uiPack.layout.showUsage) {
            ui.contextPercent?.let { append("  ·  %.1f%%".format(it)) }
        }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    BackHandler(enabled = ui.windows.isNotEmpty()) { vm.closeTopWindow() }

    LaunchedEffect(ui.toast) {
        val toast = ui.toast
        if (toast != null) {
            snackbar.showSnackbar(toast)
            vm.consumeToast()
        }
    }

    Box(Modifier.fillMaxSize()) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(ui.uiPack.layout.sidebarWidthDp.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
            ) {
                DrawerContent(vm, ui) { scope.launch { drawerState.close() } }
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(ui.titleOverride ?: "Pig", fontWeight = FontWeight.SemiBold)
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (ui.connected) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "menu")
                        }
                    },
                    actions = {
                        if (ui.busy) {
                            IconButton(onClick = vm::abort) {
                                Icon(Icons.Default.Stop, contentDescription = "stop")
                            }
                        }
                        IconButton(onClick = vm::reconnect) {
                            Icon(Icons.Default.Refresh, contentDescription = "reconnect")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
            bottomBar = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding(),
                ) {
                    InlineWindows(ui.windows, "aboveComposer", vm)
                    WidgetPane(ui.widgets, "aboveEditor", vm)
                    Composer(
                        busy = ui.busy,
                        connected = ui.connected,
                        layout = ui.uiPack.layout,
                        onSend = vm::sendPrompt,
                        onStop = vm::abort,
                    )
                    WidgetPane(ui.widgets, "belowEditor", vm)
                    InlineWindows(ui.windows, "belowComposer", vm)
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                ui.error?.let { ErrorBanner(it, vm::dismissError) }
                if (ui.status.isNotBlank()) StatusBar(ui.status)
                MessageList(ui, Modifier.weight(1f))
            }
        }
    }
    WindowLayer(ui.windows, vm)
    SheetWindows(ui.windows, vm)
    }

    ui.pendingSwitch?.let { session ->
        val title = session.name.ifBlank { session.preview.ifBlank { "未命名会话" } }
        AlertDialog(
            onDismissRequest = vm::cancelSwitch,
            title = { Text("切换会话") },
            text = {
                Text(
                    "「$title」最近 1 分钟内被修改，可能正被另一个 pig 进程使用。" +
                        "两个进程写同一个会话文件可能损坏它。",
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmSwitch) { Text("仍要切换") }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelSwitch) { Text("取消") }
            },
        )
    }

    ui.pending?.let { PendingDialog(it, vm) }
}

@Composable
private fun DrawerContent(vm: AppViewModel, ui: AppUiState, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxHeight()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("PIG", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            if (ui.connected) "127.0.0.1:${vm.port}" else "not connected",
            style = MaterialTheme.typography.labelSmall,
            color = if (ui.connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            ui.model.ifBlank { "no model" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.sessionId.isNotBlank()) {
            Text(
                "session ${ui.sessionId.take(8)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outline,
        )

        OutlinedButton(
            onClick = {
                vm.connect()
                onClose()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("启动 / 重连 relay")
        }
        OutlinedButton(
            onClick = {
                vm.newSession()
                onClose()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("新会话")
        }
        TextButton(
            onClick = {
                vm.clearLocal()
                onClose()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("清空本地记录")
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "最近会话",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.requestSessions() }) {
                Text("刷新", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (ui.sessionsLoading) {
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(ui.sessions, key = { it.path }) { session ->
                SessionRow(session, session.id == ui.sessionId) {
                    vm.switchSession(session.path)
                    onClose()
                }
            }
            if (ui.sessions.isEmpty()) {
                item {
                    Text(
                        "没有可切换的会话",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "UI pack: ${ui.uiPack.name} v${ui.uiPack.version}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "${ui.uiPackFiles} files · ${ui.uiPackHash.take(8).ifBlank { "no pack" }}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ui.uiPackError?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "cwd ${vm.cwd}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SessionRow(session: SessionInfo, current: Boolean, onClick: () -> Unit) {
    val title = session.name.ifBlank { session.preview.ifBlank { "未命名会话" } }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = if (current) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            "${session.id.take(8)} · ${DateUtils.getRelativeTimeSpanString(session.updated)}",
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageList(ui: AppUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(
        ui.messages.size,
        ui.messages.lastOrNull()?.text?.length,
        ui.messages.lastOrNull()?.toolResult?.length,
    ) {
        if (ui.messages.isNotEmpty()) {
            runCatching { listState.scrollToItem(ui.messages.lastIndex) }
        }
    }

    if (ui.messages.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🐷", fontSize = 46.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "连接 Termux 里的 pig-relay 开始",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(ui.messages, key = { it.id }) { message ->
            MessageRow(message, ui.uiPack.layout.assistantLabel, ui.uiPack.layout.toolCards)
        }
    }
}

@Composable
private fun MessageRow(message: ChatMsg, assistantLabel: String, toolMode: String) {
    when (message.role) {
        Role.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.widthIn(max = 520.dp),
            ) {
                SelectionContainer {
                    Text(
                        message.text,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        Role.ASSISTANT -> Column(Modifier.fillMaxWidth()) {
            Text(
                assistantLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    message.text.ifBlank { "…" },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        Role.TOOL -> ToolCard(message, toolMode)

        Role.NOTICE -> Text(
            message.text,
            style = MaterialTheme.typography.labelSmall,
            color = if (message.isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ToolCard(message: ChatMsg, mode: String) {
    var expanded by remember(message.id, mode) { mutableStateOf(mode != "collapsed") }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    message.toolName ?: "tool",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                when {
                    message.running -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    message.isError -> Text(
                        "failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(
                        "done",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (expanded) {
                if (message.toolArgs.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message.toolArgs,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (message.toolResult.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        message.toolResult,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 40,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    busy: Boolean,
    connected: Boolean,
    layout: UiLayout,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(layout.composerPlaceholder) },
                maxLines = layout.composerMaxLines,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(
                    imeAction = if (layout.sendOnEnter) ImeAction.Send else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (layout.sendOnEnter && text.isNotBlank() && !busy) {
                            onSend(text)
                            text = ""
                        }
                    },
                ),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    if (busy) {
                        onStop()
                    } else {
                        onSend(text)
                        text = ""
                    }
                },
                enabled = busy || (connected && text.isNotBlank()),
            ) {
                Icon(
                    if (busy) Icons.Default.Stop else Icons.Default.Send,
                    contentDescription = if (busy) "stop" else "send",
                )
            }
        }
    }
}

@Composable
private fun PendingDialog(request: UiRequest, vm: AppViewModel) {
    var value by remember(request.id) { mutableStateOf(request.prefill) }

    AlertDialog(
        onDismissRequest = { vm.answer(cancelled = true) },
        title = { Text(request.title.ifBlank { request.method }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                request.message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                when (request.method) {
                    "select" -> request.options.forEach { option ->
                        OutlinedButton(
                            onClick = { vm.answer(value = option) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(option)
                        }
                    }
                    "input" -> OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(request.placeholder ?: "") },
                    )
                    "editor" -> OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp),
                        maxLines = 12,
                    )
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (request.method) {
                "select" -> TextButton(onClick = { vm.answer(cancelled = true) }) { Text("取消") }
                "confirm" -> TextButton(onClick = { vm.answer(confirmed = true) }) { Text("允许") }
                else -> TextButton(onClick = { vm.answer(value = value) }) { Text("确定") }
            }
        },
        dismissButton = {
            when (request.method) {
                "confirm" -> TextButton(onClick = { vm.answer(confirmed = false) }) { Text("拒绝") }
                "select" -> Unit
                else -> TextButton(onClick = { vm.answer(cancelled = true) }) { Text("取消") }
            }
        },
    )
}

@Composable
private fun StatusBar(status: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .width(52.dp)
                .height(3.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
