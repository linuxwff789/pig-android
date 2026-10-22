package com.linuxwff.pigapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linuxwff.pigapp.AppUiState
import com.linuxwff.pigapp.AppViewModel
import com.linuxwff.pigapp.ChatMsg
import com.linuxwff.pigapp.RelayLauncher
import com.linuxwff.pigapp.Role
import com.linuxwff.pigapp.SessionInfo
import com.linuxwff.pigapp.TypeScale
import com.linuxwff.pigapp.UiLayout
import com.linuxwff.pigapp.UiRequest
import com.linuxwff.pigapp.toolPresentation
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PigApp(vm: AppViewModel) {
    val ui = vm.ui
    var draft by rememberSaveable { mutableStateOf("") }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val submit: () -> Unit = {
        if (draft.isNotBlank() && !ui.busy) {
            vm.sendPrompt(draft)
            draft = ""
        }
    }

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
                    drawerContainerColor = MaterialTheme.colorScheme.background,
                ) {
                    DrawerContent(vm, ui) { scope.launch { drawerState.close() } }
                }
            },
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    PigHeader(
                        ui = ui,
                        onMenu = { scope.launch { drawerState.open() } },
                        onModel = vm::openModelPicker,
                        onReconnect = vm::reconnect,
                        onStop = vm::abort,
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
                            value = draft,
                            onValueChange = { draft = it },
                            busy = ui.busy,
                            connected = ui.connected,
                            layout = ui.uiPack.layout,
                            cornerRadius = ui.uiPack.theme.cornerRadius,
                            onSubmit = submit,
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
                    AnimatedVisibility(
                        visible = ui.error != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        ui.error?.let { ErrorBanner(it, vm::dismissError) }
                    }
                    AnimatedVisibility(
                        visible = ui.status.isNotBlank(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        StatusBar(ui.status)
                    }
                    MessageList(
                        ui = ui,
                        cornerRadius = ui.uiPack.theme.cornerRadius,
                        modifier = Modifier.weight(1f),
                        onSuggestion = { if (!ui.busy) vm.sendPrompt(it) },
                    )
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
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
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
    ModelPickerDialog(vm)
    if (ui.setup) SetupDialog(ui, vm)
}

@Composable
private fun PigHeader(
    ui: AppUiState,
    onMenu: () -> Unit,
    onModel: () -> Unit,
    onReconnect: () -> Unit,
    onStop: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.background)
            .statusBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(scheme.primary.copy(alpha = 0.13f), Color.Transparent),
                        ),
                    ),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMenu) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "菜单",
                        tint = scheme.onSurfaceVariant,
                    )
                }
                BrandAvatar(36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ui.titleOverride ?: "Pig",
                            style = MaterialTheme.typography.titleLarge.copy(brush = brandBrush()),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        ConnectionDot(ui.connected)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (ui.connected) "在线" else "离线",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ui.connected) scheme.tertiary else scheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onModel)
                            .padding(top = 1.dp, bottom = 1.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            ui.model.ifBlank { "点击选择模型" },
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ui.connected) scheme.secondary else scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (ui.uiPack.layout.showUsage) {
                            ui.contextPercent?.let { percent ->
                                val usage = buildString {
                                    append("  ·  %.0f%%".format(percent))
                                    if (ui.contextWindow > 0) {
                                        append(" · ")
                                        append(formatTokens(ui.contextTokens))
                                        append('/')
                                        append(formatTokens(ui.contextWindow))
                                    }
                                }
                                Text(
                                    usage,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (percent >= 80) scheme.error else scheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                }
                if (ui.busy) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = "停止", tint = scheme.primary)
                    }
                }
                IconButton(onClick = onReconnect) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "重连",
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            scheme.primary.copy(alpha = 0.35f),
                            scheme.tertiary.copy(alpha = 0.25f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun SetupDialog(ui: AppUiState, vm: AppViewModel) {
    val context = LocalContext.current
    val command = """
        pkg install -y curl
        curl -fsSL https://github.com/linuxwff789/pig-android/releases/latest/download/install-termux.sh | sh
    """.trimIndent()
    val termuxInstalled = RelayLauncher.isTermuxInstalled(context)
    val permitted = RelayLauncher.hasPermission(context)
    AlertDialog(
        onDismissRequest = vm::dismissSetup,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandAvatar(30.dp)
                Spacer(Modifier.width(10.dp))
                Text("Termux 后端还没准备好")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Pig App 只是前端：pig 和 pig-relay 要装在 Termux 里。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!termuxInstalled) {
                    Text(
                        "1. 先从 F-Droid 安装 Termux（Play 版已停更）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "2. 打开 Termux，运行下方命令；装完再回 App 授权并重试。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text("1. 在 Termux 里运行：", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "（第一次用 Termux 可能要先等 pkg 初始化）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                ) {
                    SelectionContainer {
                        Text(
                            command,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                Text(
                    "脚本会启用 allow-external-apps、安装 pig 和 pig-relay；装完打开一次 Termux。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "「一键安装」只在 allow-external-apps 已开启时可用；没反应就手动跑上面的命令。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ui.setupHint?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when {
                !termuxInstalled -> TextButton(onClick = vm::openTermuxInstallPage) {
                    Text("安装 Termux")
                }
                !permitted -> TextButton(onClick = vm::openAppSettings) {
                    Text("权限设置")
                }
                else -> TextButton(onClick = { vm.bootstrap() }) { Text("一键安装") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Pig install", command))
                }) { Text("复制命令") }
                TextButton(onClick = {
                    vm.dismissSetup()
                    vm.reconnect()
                }) { Text("重试") }
                TextButton(onClick = vm::dismissSetup) { Text("稍后") }
            }
        },
    )
}

@Composable
private fun DrawerContent(vm: AppViewModel, ui: AppUiState, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val corner = ui.uiPack.theme.cornerRadius
    var sessionQuery by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<SessionInfo?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SessionInfo?>(null) }
    val shownSessions = remember(ui.sessions, sessionQuery) {
        val query = sessionQuery.trim()
        if (query.isEmpty()) {
            ui.sessions
        } else {
            ui.sessions.filter { session ->
                session.name.contains(query, true) ||
                    session.preview.contains(query, true) ||
                    session.id.contains(query, true) ||
                    session.cwd.contains(query, true)
            }
        }
    }
    Column(
        Modifier
            .fillMaxHeight()
            .padding(horizontal = 16.dp)
            .padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(corner.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            scheme.primary.copy(alpha = 0.34f),
                            scheme.tertiary.copy(alpha = 0.10f),
                        ),
                    ),
                )
                .border(1.dp, scheme.primary.copy(alpha = 0.28f), RoundedCornerShape(corner.dp))
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandAvatar(44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Pig",
                            style = MaterialTheme.typography.titleLarge.copy(brush = brandBrush()),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Termux 里的本地 coding agent",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoPill(
                        text = if (ui.connected) "在线" else "离线",
                        color = if (ui.connected) scheme.tertiary else scheme.onSurfaceVariant,
                    )
                    InfoPill(
                        text = ui.model.substringAfter('/').ifBlank { "无模型" },
                        color = scheme.secondary,
                    )
                    if (ui.sessionId.isNotBlank()) {
                        InfoPill(text = "会话 ${ui.sessionId.take(6)}")
                    }
                }
            }
        }

        GradientButton(
            text = "新会话",
            icon = Icons.Default.Add,
            cornerRadius = corner,
            modifier = Modifier.fillMaxWidth(),
        ) {
            vm.newSession()
            onClose()
        }
        OutlinedButton(
            onClick = {
                vm.connect()
                onClose()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(corner.dp),
        ) {
            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("启动 / 重连 relay")
        }

        if (ui.uiPack.nav.isNotEmpty()) {
            SectionLabel("面板")
            ui.uiPack.nav.forEach { item ->
                OutlinedButton(
                    onClick = {
                        vm.openNavItem(item)
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(corner.dp),
                ) {
                    Icon(
                        navIcon(item.icon),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("最近会话", Modifier.weight(1f))
            IconButton(
                onClick = { vm.requestSessions() },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "刷新会话",
                    modifier = Modifier.size(15.dp),
                    tint = scheme.onSurfaceVariant,
                )
            }
        }
        if (ui.sessionsLoading) {
            LinearProgressIndicator(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
        }
        OutlinedTextField(
            value = sessionQuery,
            onValueChange = { sessionQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape((corner / 2).coerceAtLeast(8).dp),
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = scheme.onSurfaceVariant,
                )
            },
            placeholder = { Text("搜索会话…", style = MaterialTheme.typography.labelSmall) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = scheme.primary.copy(alpha = 0.6f),
                unfocusedBorderColor = scheme.outline.copy(alpha = 0.25f),
            ),
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(shownSessions, key = { it.path }) { session ->
                SessionRow(
                    session = session,
                    current = session.id == ui.sessionId,
                    cornerRadius = corner,
                    onClick = {
                        vm.switchSession(session.path)
                        onClose()
                    },
                    onRename = {
                        renameTarget = session
                        renameText = session.name
                    },
                    onDelete = { deleteTarget = session },
                )
            }
            if (shownSessions.isEmpty()) {
                item {
                    Text(
                        if (ui.sessions.isEmpty()) "没有可切换的会话" else "没有匹配的会话",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        TextButton(
            onClick = {
                vm.clearLocal()
                onClose()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("清空本地记录", style = MaterialTheme.typography.labelSmall)
        }

        Surface(
            color = scheme.surface.copy(alpha = 0.6f),
            shape = RoundedCornerShape((corner / 2).coerceAtLeast(8).dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    "UI pack: ${ui.uiPack.name} v${ui.uiPack.version} · ${ui.uiPackFiles} files",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    "${ui.uiPackHash.take(8).ifBlank { "no pack" }} · cwd ${vm.cwd}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ui.uiPackError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.error)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        renameTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text("重命名会话") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("名称（留空清除）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.renameSession(target, renameText)
                            renameTarget = null
                        },
                    ) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { renameTarget = null }) { Text("取消") }
                },
            )
        }
        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("删除会话？") },
                text = {
                    Text(
                        "将删除「${target.name.ifBlank { target.preview.ifBlank { target.id.take(8) } }}" +
                            "」的会话文件，不可恢复。",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.deleteSession(target)
                            deleteTarget = null
                        },
                    ) { Text("删除", color = scheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun navIcon(name: String) = when (name.lowercase()) {
    "refresh" -> Icons.Default.Refresh
    "add" -> Icons.Default.Add
    "settings", "build" -> Icons.Default.Build
    else -> Icons.Default.Build
}

@Composable
private fun SessionRow(
    session: SessionInfo,
    current: Boolean,
    cornerRadius: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = session.name.ifBlank { session.preview.ifBlank { "未命名会话" } }
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(cornerRadius.dp)
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (current) scheme.primary.copy(alpha = 0.13f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(26.dp)
                .clip(CircleShape)
                .background(if (current) brandBrush() else SolidColor(Color.Transparent)),
        )
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = if (current) scheme.primary else scheme.onSurface,
            )
            Text(
                "${session.id.take(8)} · ${DateUtils.getRelativeTimeSpanString(session.updated)}" +
                    " · ${session.size / 1024}K",
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (current) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = scheme.primary,
            )
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "会话操作",
                    modifier = Modifier.size(16.dp),
                    tint = scheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("重命名") },
                    leadingIcon = {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                    },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (current) "当前会话不可删除" else "删除") },
                    enabled = !current,
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = if (current) scheme.onSurfaceVariant else scheme.error,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    ui: AppUiState,
    cornerRadius: Int,
    modifier: Modifier = Modifier,
    onSuggestion: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val last = ui.messages.lastOrNull()
    val showTyping = ui.busy && (
        last == null ||
            last.role == Role.USER ||
            (last.role == Role.ASSISTANT && last.text.isBlank())
        )

    LaunchedEffect(
        ui.messages.size,
        last?.text?.length,
        last?.toolResult?.length,
        showTyping,
    ) {
        if (ui.messages.isNotEmpty()) {
            runCatching { listState.scrollToItem(listState.layoutInfo.totalItemsCount) }
        }
    }

    if (ui.messages.isEmpty()) {
        EmptyState(onSuggestion)
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(ui.messages, key = { it.id }) { message ->
            MessageRow(
                message = message,
                assistantLabel = ui.uiPack.layout.assistantLabel,
                toolMode = ui.uiPack.layout.toolCards,
                types = ui.uiPack.types,
                cornerRadius = cornerRadius,
            )
        }
        if (showTyping) {
            item(key = "typing") {
                TypingRow(ui.uiPack.layout.assistantLabel)
            }
        }
    }
}

private val suggestions = listOf(
    "这个项目是做什么的？",
    "看看有哪些可以改进的地方",
    "帮我跑一下测试",
)

@Composable
private fun EmptyState(onSuggestion: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(116.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(scheme.primary.copy(alpha = 0.22f), Color.Transparent),
                            ),
                        ),
                )
                BrandAvatar(64.dp)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "开始聊天",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "在下面输入，或试试：",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            suggestions.forEach { text ->
                Surface(
                    shape = CircleShape,
                    color = scheme.surface,
                    border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .padding(vertical = 3.dp)
                        .clickable { onSuggestion(text) },
                ) {
                    Text(
                        text,
                        modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageRow(
    message: ChatMsg,
    assistantLabel: String,
    toolMode: String,
    types: TypeScale,
    cornerRadius: Int,
) {
    when (message.role) {
        Role.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val shape = RoundedCornerShape(
                topStart = (cornerRadius + 4).dp,
                topEnd = (cornerRadius + 4).dp,
                bottomEnd = if (cornerRadius == 0) 0.dp else 5.dp,
                bottomStart = (cornerRadius + 4).dp,
            )
            val scheme = MaterialTheme.colorScheme
            SelectionContainer {
                Box(
                    Modifier
                        .widthIn(max = 520.dp)
                        .shadow(8.dp, shape, ambientColor = scheme.primary, spotColor = scheme.primary)
                        .clip(shape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    scheme.primary.copy(alpha = 0.30f),
                                    scheme.tertiary.copy(alpha = 0.16f),
                                ),
                            ),
                            shape,
                        )
                        .border(1.dp, scheme.primary.copy(alpha = 0.32f), shape)
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Role.ASSISTANT -> Row(Modifier.fillMaxWidth()) {
            BrandAvatar(24.dp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    assistantLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    PigMarkdown(
                        text = message.text.ifBlank { "…" },
                        modifier = Modifier.fillMaxWidth(),
                        types = types,
                        cornerRadius = cornerRadius,
                    )
                }
            }
        }

        Role.TOOL -> Column(Modifier.fillMaxWidth().padding(start = 33.dp)) {
            ToolCard(message, toolMode, cornerRadius)
        }

        Role.NOTICE -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                message.text,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (message.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private enum class ToolMode { COLLAPSED, SUMMARY, EXPANDED }

@Composable
private fun ToolCard(message: ChatMsg, mode: String, cornerRadius: Int) {
    var viewMode by remember(message.id, mode) {
        mutableStateOf(
            when (mode) {
                "expanded" -> ToolMode.EXPANDED
                "collapsed" -> ToolMode.COLLAPSED
                else -> ToolMode.SUMMARY
            },
        )
    }
    val view = remember(
        message.toolName,
        message.toolArgs,
        message.toolResult,
        message.toolDetails,
        message.running,
        message.isError,
    ) {
        toolPresentation(
            name = message.toolName,
            argsJson = message.toolArgs,
            result = message.toolResult,
            detailsJson = message.toolDetails,
            isError = message.isError,
            running = message.running,
        )
    }
    val scheme = MaterialTheme.colorScheme

    Surface(
        color = scheme.surface,
        shape = RoundedCornerShape(cornerRadius.dp),
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.secondary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = scheme.secondary,
                    )
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(message.toolName ?: "tool", style = MaterialTheme.typography.titleSmall)
                    Text(
                        view.headline,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    message.running -> CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                    )
                    message.isError -> InfoPill(text = "失败", color = scheme.error)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    view.metrics,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ToolModeChip("收起", viewMode == ToolMode.COLLAPSED, cornerRadius) { viewMode = ToolMode.COLLAPSED }
                ToolModeChip("摘要", viewMode == ToolMode.SUMMARY, cornerRadius) { viewMode = ToolMode.SUMMARY }
                ToolModeChip("展开", viewMode == ToolMode.EXPANDED, cornerRadius) { viewMode = ToolMode.EXPANDED }
            }

            when (viewMode) {
                ToolMode.COLLAPSED -> Unit
                ToolMode.SUMMARY -> {
                    Spacer(Modifier.height(6.dp))
                    when {
                        message.running -> Text(
                            "运行中…",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                        view.diff.isNotBlank() -> DiffBlock(
                            view.diff.lineSequence().take(6).joinToString("\n"),
                            cornerRadius,
                        )
                        view.summary.isNotBlank() -> CodeBlock(view.summary, cornerRadius)
                        view.expanded.isNotBlank() -> CodeBlock(
                            view.expanded.lineSequence().take(4).joinToString("\n"),
                            cornerRadius,
                        )
                    }
                }
                ToolMode.EXPANDED -> {
                    Spacer(Modifier.height(6.dp))
                    when {
                        view.diff.isNotBlank() -> DiffBlock(view.diff, cornerRadius)
                        view.expanded.isNotBlank() -> CodeBlock(view.expanded, cornerRadius)
                        view.summary.isNotBlank() -> CodeBlock(view.summary, cornerRadius)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolModeChip(label: String, selected: Boolean, cornerRadius: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape((cornerRadius / 3).coerceAtLeast(6).dp)
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) scheme.primary else scheme.onSurfaceVariant,
        fontWeight = if (selected) FontWeight.SemiBold else null,
        modifier = Modifier
            .padding(start = 3.dp)
            .clip(shape)
            .background(if (selected) scheme.primary.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun CodeBlock(text: String, cornerRadius: Int) {
    if (text.isBlank()) return
    val lines = text.lineSequence().take(200).toList()
    val truncated = text.lineSequence().count() > 200
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape((cornerRadius / 2).coerceAtLeast(6).dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.background.copy(alpha = 0.6f))
            .border(1.dp, scheme.outline.copy(alpha = 0.35f), shape)
            .padding(9.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        lines.forEach { line ->
            Text(
                line.ifEmpty { " " },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                softWrap = false,
            )
        }
        if (truncated) {
            Text("…", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DiffBlock(diff: String, cornerRadius: Int) {
    if (diff.isBlank()) return
    val lines = diff.lineSequence().take(200).toList()
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape((cornerRadius / 2).coerceAtLeast(6).dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.background.copy(alpha = 0.6f))
            .border(1.dp, scheme.outline.copy(alpha = 0.35f), shape)
            .padding(9.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        lines.forEach { line ->
            val color = when {
                line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF4ADE80)
                line.startsWith("-") && !line.startsWith("---") -> scheme.error
                else -> scheme.onSurfaceVariant
            }
            Text(
                line.ifEmpty { " " },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                softWrap = false,
                color = color,
            )
        }
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    connected: Boolean,
    layout: UiLayout,
    cornerRadius: Int,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val active = busy || (connected && value.isNotBlank())
    val shape = RoundedCornerShape(
        topStart = (cornerRadius + 8).dp,
        topEnd = (cornerRadius + 8).dp,
    )

    Surface(
        color = scheme.surface,
        shape = shape,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            layout.composerPlaceholder,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    },
                    maxLines = layout.composerMaxLines,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = scheme.primary.copy(alpha = 0.7f),
                        unfocusedBorderColor = scheme.outline.copy(alpha = 0.5f),
                        focusedContainerColor = scheme.surfaceVariant.copy(alpha = 0.35f),
                        unfocusedContainerColor = scheme.surfaceVariant.copy(alpha = 0.35f),
                        cursorColor = scheme.primary,
                    ),
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (layout.sendOnEnter) ImeAction.Send else ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { if (layout.sendOnEnter) onSubmit() },
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(46.dp)
                        .then(
                            if (active) {
                                Modifier.shadow(8.dp, CircleShape, ambientColor = scheme.primary, spotColor = scheme.primary)
                            } else {
                                Modifier
                            },
                        )
                        .clip(CircleShape)
                        .background(if (active) brandBrush() else SolidColor(scheme.surfaceVariant))
                        .clickable(enabled = active) {
                            if (busy) onStop() else onSubmit()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (busy) Icons.Default.Stop else Icons.Default.Send,
                        contentDescription = if (busy) "停止" else "发送",
                        modifier = Modifier.size(21.dp),
                        tint = if (active) BrandInk else scheme.onSurfaceVariant,
                    )
                }
            }
            if (!connected) {
                Text(
                    "未连接 · 点右上角重连，或从菜单启动 relay",
                    modifier = Modifier.padding(start = 6.dp, top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TypingRow(label: String) {
    Row(Modifier.fillMaxWidth()) {
        BrandAvatar(24.dp)
        Spacer(Modifier.width(9.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        CircleShape,
                    )
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TypingDots(MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun TypingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "typing")
    repeat(3) { index ->
        val alpha by transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 520, delayMillis = index * 150, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dot$index",
        )
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = alpha)),
        )
    }
}

@Composable
private fun PendingDialog(request: UiRequest, vm: AppViewModel) {
    var value by remember(request.id) { mutableStateOf(request.prefill) }

    AlertDialog(
        onDismissRequest = { vm.answer(cancelled = true) },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
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
                            shape = RoundedCornerShape(12.dp),
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
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(CircleShape)
            .background(scheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(11.dp),
            strokeWidth = 1.5.dp,
            color = scheme.secondary,
        )
        Spacer(Modifier.width(9.dp))
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.errorContainer,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = scheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭",
                    modifier = Modifier.size(16.dp),
                    tint = scheme.onErrorContainer,
                )
            }
        }
    }
}

private fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%.1f".format(tokens / 1_000_000f).removeSuffix(".0") + "M"
    tokens >= 1000 -> "${tokens / 1000}K"
    else -> "$tokens"
}
