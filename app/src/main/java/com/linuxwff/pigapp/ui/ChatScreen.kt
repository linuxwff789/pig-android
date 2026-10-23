package com.linuxwff.pigapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.pointer.pointerInput
import com.linuxwff.pigapp.AppUiState
import com.linuxwff.pigapp.AppViewModel
import com.linuxwff.pigapp.ChatMsg
import com.linuxwff.pigapp.LocalPackInfo
import com.linuxwff.pigapp.RelayLauncher
import com.linuxwff.pigapp.Role
import com.linuxwff.pigapp.SessionInfo
import com.linuxwff.pigapp.TypeScale
import com.linuxwff.pigapp.UiLayout
import com.linuxwff.pigapp.UiRequest
import com.linuxwff.pigapp.UiSkin
import com.linuxwff.pigapp.resolve
import com.linuxwff.pigapp.toolPresentation
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PigApp(vm: AppViewModel) {
    val ui = vm.ui
    var draft by rememberSaveable { mutableStateOf("") }
    // Each session keeps its own composer draft; switching must not carry the
    // text into another conversation.
    val drafts = remember { mutableStateMapOf<String, String>() }
    var draftPath by remember { mutableStateOf(ui.sessionFile) }
    LaunchedEffect(ui.sessionFile) {
        if (draftPath != ui.sessionFile) {
            drafts[draftPath] = draft
            draft = drafts[ui.sessionFile].orEmpty()
            draftPath = ui.sessionFile
        }
    }
    var showSettings by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showPackManager by remember { mutableStateOf(false) }
    val components = packComponents()
    val bindings = ui.uiPack.layout.bindings
    val boundTitle = bindings["header.title"]?.resolve(ui.rpcData)
    val boundSubtitle = bindings["header.subtitle"]?.resolve(ui.rpcData)
    val boundUsage = bindings["header.usage"]?.resolve(ui.rpcData)
    val boundCache = bindings["header.cache"]?.resolve(ui.rpcData)
    val commandQuery = remember(draft) {
        val trimmed = draft.trimStart()
        if (!trimmed.startsWith("/")) return@remember null
        val rest = trimmed.removePrefix("/")
        if (rest.contains(' ')) null else rest
    }
    LaunchedEffect(commandQuery != null) {
        if (commandQuery != null) vm.refreshCommands()
    }
    val commandMatches = remember(commandQuery, ui.commands) {
        if (commandQuery == null) {
            emptyList()
        } else {
            val matches = ui.commands.filter {
                it.name.contains(commandQuery, true) ||
                    it.description.contains(commandQuery, true)
            }
            // A bare "/" lists everything: keep pig's order (built-ins first) but
            // do not cut the list at a handful, or installed extensions such as
            // plan never show up. Typing narrows it, prefix matches first.
            val ordered = if (commandQuery.isEmpty()) {
                matches
            } else {
                matches.sortedWith(
                    compareByDescending<com.linuxwff.pigapp.UiCommand> {
                        it.name.startsWith(commandQuery, true)
                    }.thenBy { it.name },
                )
            }
            ordered.take(60)
        }
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val sendDraft: (Boolean) -> Unit = { steer ->
        if (draft.isNotBlank()) {
            vm.sendPrompt(draft, steer = steer)
            draft = ""
        }
    }
    val submit: () -> Unit = { sendDraft(ui.busy) }

    BackHandler(enabled = ui.windows.isNotEmpty()) { vm.closeTopWindow() }

    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) vm.requestSessions()
    }

    LaunchedEffect(ui.toast) {
        val toast = ui.toast
        if (toast != null) {
            snackbar.showSnackbar(toast)
            vm.consumeToast()
        }
    }

    Box(Modifier.fillMaxSize()) {
        val drawerAppearance = ui.uiPack.layout.drawer
        val drawerCorner = if (drawerAppearance.cornerRadius >= 0) {
            drawerAppearance.cornerRadius
        } else {
            ui.uiPack.theme.cornerRadius
        }
        val drawerScheme = MaterialTheme.colorScheme
        val drawerBackground = when (drawerAppearance.background) {
            "surface" -> drawerScheme.surface
            "transparent" -> Color.Transparent
            else -> drawerScheme.background
        }
        val drawerBrush = if (drawerAppearance.background == "gradient") {
            Brush.verticalGradient(
                listOf(
                    drawerScheme.primary.copy(alpha = drawerAppearance.gradientAlpha),
                    drawerScheme.background,
                ),
            )
        } else {
            null
        }
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(ui.uiPack.layout.sidebarWidthDp.dp),
                    drawerShape = RoundedCornerShape(drawerCorner.dp),
                    drawerContainerColor = Color.Transparent,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (drawerBrush != null) {
                                    Modifier.background(drawerBrush)
                                } else {
                                    Modifier.background(drawerBackground)
                                },
                            ),
                    ) {
                        val open = drawerState.currentValue == DrawerValue.Open ||
                            drawerState.targetValue == DrawerValue.Open
                        if (open) {
                            DrawerContent(
                                vm = vm,
                                ui = ui,
                                onClose = { scope.launch { drawerState.close() } },
                                onSettings = {
                                    scope.launch { drawerState.close() }
                                    showSettings = true
                                },
                                onOpenPacks = {
                                    scope.launch { drawerState.close() }
                                    showPackManager = true
                                },
                            )
                        }
                    }
                }
            },
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    val customHeader = ui.uiPack.layout.header
                    if (customHeader == null) {
                        PigHeader(
                            title = boundTitle ?: ui.titleOverride ?: packText("header.title", "Pig"),
                            connected = ui.connected,
                            model = ui.model,
                            modelSwitching = ui.modelSwitchPending,
                            boundSubtitle = boundSubtitle,
                            boundUsage = boundUsage,
                            boundCache = boundCache,
                            showUsage = ui.uiPack.layout.showUsage,
                            contextPercent = ui.contextPercent,
                            contextCompacted = ui.contextCompacted,
                            cacheHitRate = ui.cacheHitRate,
                            contextWindow = ui.contextWindow,
                            contextTokens = ui.contextTokens,
                            busy = ui.busy,
                            onMenu = { scope.launch { drawerState.open() } },
                            onModel = vm::openModelPicker,
                            onReconnect = vm::reconnect,
                            onStop = vm::abort,
                        )
                    } else if (customHeader.show) {
                        CustomPigHeader(
                            header = customHeader,
                            ui = ui,
                            vm = vm,
                            onMenu = { scope.launch { drawerState.open() } },
                            onModel = vm::openModelPicker,
                            onReconnect = vm::reconnect,
                            onStop = vm::abort,
                            onSettings = { showSettings = true },
                            onNewSession = vm::newSession,
                            onPackAction = { vm.runPackAction(it) },
                        )
                    } else {
                        Spacer(Modifier.statusBarsPadding())
                    }
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
                        if (commandQuery != null && commandMatches.isNotEmpty()) {
                            CommandPalette(commandMatches) { command ->
                                draft = "/${command.name} "
                            }
                        }
                        if (ui.pendingMessages > 0 && components.showPendingBanner) {
                            PendingBanner(ui.pendingMessages)
                        }
                        Composer(
                            value = draft,
                            onValueChange = { draft = it },
                            busy = ui.busy,
                            connected = ui.connected,
                            layout = ui.uiPack.layout,
                            cornerRadius = ui.uiPack.theme.cornerRadius,
                            onSubmit = submit,
                            onSteer = { sendDraft(true) },
                            onFollowUp = { sendDraft(false) },
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
                        visible = components.showStatusBar &&
                            (ui.status.isNotBlank() || ui.extensionStatuses.isNotEmpty()),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        StatusBar(ui.status, ui.extensionStatuses)
                    }
                    if (ui.readOnly && components.showReadOnlyBanner) {
                        ReadOnlyBanner()
                    }
                    MessageList(
                        messages = ui.messages,
                        busy = ui.busy,
                        streamingId = ui.streamingId,
                        hideThinking = ui.hideThinkingBlock,
                        toolMode = ui.toolMode.ifBlank { ui.uiPack.layout.toolCards },
                        assistantLabel = ui.uiPack.layout.assistantLabel,
                        chatPaddingH = ui.uiPack.layout.chatPaddingH,
                        chatPaddingV = ui.uiPack.layout.chatPaddingV,
                        chatBottomPadding = ui.uiPack.layout.chatBottomPadding,
                        messageSpacing = ui.uiPack.layout.messageSpacing,
                        emptyAvatarSize = ui.uiPack.layout.emptyAvatarSize,
                        codeBlockRadius = ui.uiPack.layout.codeBlockRadius,
                        codeBlockBorderAlpha = ui.uiPack.layout.codeBlockBorderAlpha,
                        types = ui.uiPack.types,
                        cornerRadius = ui.uiPack.theme.cornerRadius,
                        avatarShadow = ui.avatarShadow,
                        bubbleShadow = ui.bubbleShadow,
                        toolGroups = ui.uiPack.layout.toolGroups,
                        modifier = Modifier.weight(1f),
                        onSuggestion = { if (!ui.busy) vm.sendPrompt(it) },
                        onCopy = vm::copyText,
                        onToolModeAll = vm::setToolMode,
                    )
                }
            }
        }
        WindowLayer(ui.windows, vm)
        SheetWindows(ui.windows, vm)
        if (showSettings) {
            SettingsDialog(
                vm,
                ui,
                onOpenPacks = {
                    showSettings = false
                    showPackManager = true
                },
                onOpenStats = {
                    showSettings = false
                    vm.refreshStats()
                    showStats = true
                },
            ) { showSettings = false }
        }
        if (showStats) {
            StatsDialog(ui, onRefresh = vm::refreshStats) { showStats = false }
        }
        if (showPackManager) {
            PackManagerDialog(vm, ui) { showPackManager = false }
        }
    }

    ui.pendingSwitch?.let { session ->
        val title = session.name.ifBlank {
        session.preview.ifBlank { packText("session.untitled", "未命名会话") }
    }
    val components = packComponents()
        AlertDialog(
            onDismissRequest = vm::cancelSwitch,
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(packText("session.switchTitle", "切换会话")) },
            text = {
                Text(
                    packText(
                        "session.switchWarn",
                        "「{name}」最近 1 分钟内被修改，可能正被另一个 pig 进程使用。" +
                            "两个进程写同一个会话文件可能损坏它。",
                    ).replace("{name}", title),
                )
            },
            confirmButton = {
                TextButton(onClick = vm::confirmSwitch) {
                    Text(packText("session.switchConfirm", "仍要切换"))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelSwitch) {
                    Text(packText("session.cancel", "取消"))
                }
            },
        )
    }

    ui.pendingPackAction?.let { request ->
        AlertDialog(
            onDismissRequest = vm::cancelPackAction,
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(packText("pack.actionTitle", "执行 pack 动作？")) },
            text = { Text(request.message) },
            confirmButton = {
                TextButton(onClick = vm::confirmPackAction) {
                    Text(packText("pack.actionConfirm", "执行"))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelPackAction) {
                    Text(packText("dialog.cancel", "取消"))
                }
            },
        )
    }
    ui.pending?.let { PendingDialog(it, vm) }
    ModelPickerDialog(vm)
    if (ui.setup) SetupDialog(ui, vm)
}

@Composable
private fun PigHeader(
    title: String,
    connected: Boolean,
    model: String,
    modelSwitching: Boolean,
    boundSubtitle: String?,
    boundUsage: String?,
    boundCache: String?,
    showUsage: Boolean,
    contextPercent: Double?,
    contextCompacted: Boolean,
    cacheHitRate: Double?,
    contextWindow: Int,
    contextTokens: Int,
    busy: Boolean,
    onMenu: () -> Unit,
    onModel: () -> Unit,
    onReconnect: () -> Unit,
    onStop: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val components = packComponents()
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
                        packIcon("menu", "menu"),
                        contentDescription = packText("a11y.menu", "菜单"),
                        tint = scheme.onSurfaceVariant,
                    )
                }
                if (components.showHeaderAvatar) {
                    BrandAvatar(components.headerAvatarSize.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge.copy(brush = brandBrush()),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (components.showHeaderOnline) {
                            Spacer(Modifier.width(8.dp))
                            ConnectionDot(connected)
                            Spacer(Modifier.width(5.dp))
                            Text(
                                if (connected) {
                                    packText("header.online", "在线")
                                } else {
                                    packText("settings.offline", "离线")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (connected) scheme.tertiary else scheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onModel)
                            .padding(top = 1.dp, bottom = 1.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val modelText = boundSubtitle ?: model.ifBlank {
                            packText("header.modelEmpty", "点击选择模型")
                        }
                        Text(
                            if (modelSwitching) {
                                "$modelText · " + packText("model.switching", "切换中…")
                            } else {
                                modelText
                            },
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (connected) scheme.secondary else scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (showUsage) {
                            if (boundUsage != null) {
                                Text(
                                    "  ·  $boundUsage",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            } else if (contextCompacted) {
                                Text(
                                    packText("header.compacted", "  ·  已压缩"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.secondary,
                                )
                            } else {
                                contextPercent?.let { percent ->
                                    val usage = if (contextWindow > 0) {
                                        packText(
                                            "header.usage",
                                            "  ·  {percent}% · {used}/{window}",
                                        )
                                            .replace("{percent}", "%.0f".format(percent))
                                            .replace("{used}", formatTokens(contextTokens))
                                            .replace("{window}", formatTokens(contextWindow))
                                    } else {
                                        packText("header.usagePercent", "  ·  {percent}%")
                                            .replace("{percent}", "%.0f".format(percent))
                                    }
                                    Text(
                                        usage,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (percent >= 80) scheme.error else scheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (boundCache != null) {
                                Text(
                                    "  ·  $boundCache",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            } else {
                                cacheHitRate?.let { rate ->
                                    Text(
                                        packText("header.cache", "  ·  缓存 {rate}%")
                                            .replace("{rate}", "%.0f".format(rate)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(2.dp))
                        Icon(
                            packIcon("chevronDown", "down"),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                    }
                }
                if (busy) {
                    IconButton(onClick = onStop) {
                        Icon(
                            packIcon("stop", "stop"),
                            contentDescription = packText("a11y.stop", "停止"),
                            tint = scheme.primary,
                        )
                    }
                }
                IconButton(onClick = onReconnect) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = packText("a11y.reconnect", "重连"),
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
                Text(packText("setup.title", "Termux 后端还没准备好"))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    packText("setup.intro", "Pig App 只是前端：pig 和 pig-relay 要装在 Termux 里。"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!termuxInstalled) {
                    Text(
                        packText("setup.installTermuxStep", "1. 先从 F-Droid 安装 Termux（Play 版已停更）"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        packText(
                            "setup.openTermuxStep",
                            "2. 打开 Termux，运行下方命令；装完再回 App 授权并重试。",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        packText("setup.runStep", "1. 在 Termux 里运行："),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        packText("setup.pkgHint", "（第一次用 Termux 可能要先等 pkg 初始化）"),
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
                            fontFamily = packCodeFont(),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                Text(
                    packText(
                        "setup.scriptHint",
                        "脚本会启用 allow-external-apps、安装 pig 和 pig-relay；装完打开一次 Termux。",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    packText(
                        "setup.oneClickHint",
                        "「一键安装」只在 allow-external-apps 已开启时可用；没反应就手动跑上面的命令。",
                    ),
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
                    Text(packText("setup.installTermux", "安装 Termux"))
                }
                !permitted -> TextButton(onClick = vm::openAppSettings) {
                    Text(packText("setup.permissions", "权限设置"))
                }
                else -> TextButton(onClick = { vm.bootstrap() }) {
                    Text(packText("setup.oneClick", "一键安装"))
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Pig install", command))
                }) { Text(packText("setup.copyCommand", "复制命令")) }
                TextButton(onClick = {
                    vm.dismissSetup()
                    vm.reconnect()
                }) { Text(packText("setup.retry", "重试")) }
                TextButton(onClick = vm::dismissSetup) {
                    Text(packText("setup.later", "稍后"))
                }
            }
        },
    )
}

@Composable
/** Cumulative counters for the whole session file, straight from pig. */
@Composable
private fun StatsDialog(
    ui: AppUiState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val usage = ui.usage
    val cacheRate = usage.cacheRate
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(ui.uiPack.theme.cornerRadius.dp),
        containerColor = scheme.surface,
        title = { Text(packText("stats.title", "使用统计")) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatRow(
                    packText("stats.messages", "消息"),
                    packText(
                        "stats.messagesValue",
                        "用户 {user} · 助手 {assistant} · 工具 {tools}/{results} · 合计 {total}",
                    )
                        .replace("{user}", usage.userMessages.toString())
                        .replace("{assistant}", usage.assistantMessages.toString())
                        .replace("{tools}", usage.toolCalls.toString())
                        .replace("{results}", usage.toolResults.toString())
                        .replace("{total}", usage.totalMessages.toString()),
                )
                StatRow(
                    packText("stats.tokens", "Tokens"),
                    packText("stats.tokensValue", "输入 {input} · 输出 {output} · 合计 {total}")
                        .replace("{input}", statCount(usage.input))
                        .replace("{output}", statCount(usage.output))
                        .replace("{total}", statCount(usage.total)),
                )
                StatRow(
                    packText("stats.cache", "缓存"),
                    packText("stats.cacheValue", "读 {read} · 写 {write} · 命中 {rate}%")
                        .replace("{read}", statCount(usage.cacheRead))
                        .replace("{write}", statCount(usage.cacheWrite))
                        .replace("{rate}", cacheRate?.let { "%.0f".format(it) } ?: "—"),
                )
                StatRow(
                    packText("stats.cost", "费用"),
                    statCost(usage.cost),
                )
                StatRow(
                    packText("stats.context", "上下文"),
                    packText("stats.contextValue", "{used} / {window} · {percent}%")
                        .replace("{used}", formatTokens(ui.contextTokens))
                        .replace(
                            "{window}",
                            if (ui.contextWindow > 0) formatTokens(ui.contextWindow) else "—",
                        )
                        .replace(
                            "{percent}",
                            ui.contextPercent?.let { "%.1f".format(it) } ?: "—",
                        ),
                )
                Text(
                    packText(
                        "stats.hint",
                        "数字来自 pig 的会话统计，覆盖整个会话文件，不受历史只加载最近 2000 条的限制。",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(packText("stats.close", "关闭"))
            }
        },
        dismissButton = {
            TextButton(onClick = onRefresh) {
                Text(packText("stats.refresh", "刷新"))
            }
        },
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun statCount(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.1f".format(tokens / 1_000_000.0).removeSuffix(".0") + "M"
    tokens >= 1000 -> "%.1f".format(tokens / 1000.0).removeSuffix(".0") + "K"
    else -> tokens.toString()
}

private fun statCost(cost: Double): String =
    if (cost <= 0.0) "—" else "\$" + "%.4f".format(cost).trimEnd('0').trimEnd('.')

private fun SettingsDialog(
    vm: AppViewModel,
    ui: AppUiState,
    onOpenPacks: () -> Unit,
    onOpenStats: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var batteryOk by remember { mutableStateOf(RelayLauncher.isIgnoringBatteryOptimizations(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryOk = RelayLauncher.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(ui.uiPack.theme.cornerRadius.dp),
        containerColor = scheme.surface,
        title = { Text(packText("settings.title", "设置")) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(packText("settings.connection", "连接"), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (ui.connected) packText("settings.connected", "已连接") else packText("settings.offline", "离线"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ui.connected) scheme.onSurface else scheme.error,
                        )
                        Text(
                            ui.model.ifBlank { packText("header.noModel", "无模型") },
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = vm::reconnect) { Text(packText("settings.reconnect", "重连 relay")) }
                }

                HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                SettingToggle(
                    title = packText("settings.keepAlive", "后台保活"),
                    subtitle = packText("settings.keepAliveHint", "前台服务，锁屏后保持与 Termux 的连接"),
                    checked = ui.keepAlive,
                    onCheckedChange = vm::setKeepAlive,
                )
                SettingToggle(
                    title = packText("settings.wakeLock", "唤醒锁"),
                    subtitle = packText("settings.wakeLockHint", "像 termux-wake-lock 一样让 CPU 不休眠"),
                    checked = ui.wakeLock,
                    onCheckedChange = vm::setWakeLock,
                )
                SettingToggle(
                    title = packText("settings.avatarShadow", "消息头像阴影"),
                    subtitle = packText("settings.avatarShadowHint", "关闭可减少每条消息的绘制开销"),
                    checked = ui.avatarShadow,
                    onCheckedChange = vm::setAvatarShadow,
                )
                SettingToggle(
                    title = packText("settings.bubbleShadow", "气泡阴影"),
                    subtitle = packText("settings.bubbleShadowHint", "用户消息气泡的投影，默认跟随 UI pack"),
                    checked = ui.bubbleShadow,
                    onCheckedChange = vm::setBubbleShadow,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(packText("settings.battery", "电池优化"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (batteryOk) packText("settings.batteryAllowed", "已允许后台运行") else packText("settings.batteryMissing", "未加入白名单，系统可能随时清理"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (batteryOk) scheme.tertiary else scheme.error,
                        )
                    }
                    TextButton(onClick = vm::openBatterySettings) {
                        Text(if (batteryOk) packText("settings.batteryView", "查看") else packText("settings.batteryGo", "去设置"))
                    }
                }
                Text(
                    packText("settings.termuxWake", "Termux 端的 termux-wake-lock 由 App 在连接时自动获取。"),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )

                HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                Text(
                    packText("settings.session", "会话行为"),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                SettingToggle(
                    title = packText("settings.autoCompaction", "自动压缩上下文"),
                    subtitle = packText(
                        "settings.autoCompactionHint",
                        "上下文接近窗口上限时自动总结旧消息；关闭后需要手动 /compact",
                    ),
                    checked = ui.autoCompaction,
                    onCheckedChange = vm::setAutoCompaction,
                )
                SettingToggle(
                    title = packText("settings.autoRetry", "自动重试"),
                    subtitle = packText(
                        "settings.autoRetryHint",
                        "请求失败时按退避策略重试，仅对当前会话生效",
                    ),
                    checked = ui.autoRetry,
                    onCheckedChange = vm::setAutoRetry,
                )
                QueueModeRow(
                    title = packText("settings.steering", "打断消息"),
                    hint = packText(
                        "settings.steeringHint",
                        "运行中发送的消息：逐条等当前步骤结束，或一次性全部塞入",
                    ),
                    value = ui.steeringMode,
                    onPick = { vm.setQueueMode(followUp = false, mode = it) },
                )
                QueueModeRow(
                    title = packText("settings.followUp", "追问消息"),
                    hint = packText(
                        "settings.followUpHint",
                        "本轮结束后再处理的排队消息的发送方式",
                    ),
                    value = ui.followUpMode,
                    onPick = { vm.setQueueMode(followUp = true, mode = it) },
                )
                SettingToggle(
                    title = packText("settings.notifyDone", "回复完成通知"),
                    subtitle = packText(
                        "settings.notifyDoneHint",
                        "App 在后台时，本轮结束后发一条通知",
                    ),
                    checked = ui.notifyDone,
                    onCheckedChange = { vm.setNotifyPref("notifyDone", it) },
                )
                SettingToggle(
                    title = packText("settings.notifyAttention", "需要操作 / 消息提醒"),
                    subtitle = packText(
                        "settings.notifyAttentionHint",
                        "扩展等待确认或选择、扩展发出警告时提醒",
                    ),
                    checked = ui.notifyAttention,
                    onCheckedChange = { vm.setNotifyPref("notifyAttention", it) },
                )
                SettingToggle(
                    title = packText("settings.notifyError", "出错提醒"),
                    subtitle = packText(
                        "settings.notifyErrorHint",
                        "请求失败、连接错误时提醒",
                    ),
                    checked = ui.notifyError,
                    onCheckedChange = { vm.setNotifyPref("notifyError", it) },
                )
                SettingToggle(
                    title = packText("settings.notifyCompacted", "压缩完成通知"),
                    subtitle = packText(
                        "settings.notifyCompactedHint",
                        "自动压缩结束时静默提醒（默认关闭）",
                    ),
                    checked = ui.notifyCompacted,
                    onCheckedChange = { vm.setNotifyPref("notifyCompacted", it) },
                )
                Column {
                    Text(
                        packText("settings.notifySound", "通知提示音"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        packText(
                            "settings.notifySoundHint",
                            "Android 把提示音绑定在渠道上；改这里会换一个同名渠道。更多选项在系统设置里。",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            "system" to packText("settings.soundSystem", "系统默认"),
                            "silent" to packText("settings.soundSilent", "静音"),
                            "alarm" to packText("settings.soundAlarm", "闹钟"),
                            "ringtone" to packText("settings.soundRingtone", "铃声"),
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = ui.notifySound == value,
                                onClick = { vm.setNotifySound(value) },
                                label = { Text(label) },
                                shape = CircleShape,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = scheme.primary.copy(alpha = 0.18f),
                                    selectedLabelColor = scheme.primary,
                                ),
                            )
                        }
                    }
                    TextButton(onClick = vm::openNotificationSettings) {
                        Text(
                            packText("settings.notifySettings", "打开系统通知设置…"),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                SettingToggle(
                    title = packText("settings.showThinking", "显示思考过程"),
                    subtitle = packText(
                        "settings.showThinkingHint",
                        "在回答上方折叠显示模型的可见思考内容（写入 settings.json 的 hideThinkingBlock）",
                    ),
                    checked = !ui.hideThinkingBlock,
                    onCheckedChange = { vm.setHideThinkingBlock(!it) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            packText("settings.restartPig", "重启 pig"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            packText(
                                "settings.restartPigHint",
                                "安装或更新扩展后，重启才会加载新命令；运行中会等本轮结束",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = vm::restartAgentNow) {
                        Text(packText("settings.restartAction", "重启"))
                    }
                }

                HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                Text(
                    packText("settings.tuning", "压缩与重试"),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                if (!ui.settingsRemote) {
                    Text(
                        packText(
                            "settings.tuningUnsupported",
                            "这些数值写在 ~/.pig/agent/settings.json，需要新版 relay 才能读写",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                val ctxWindow = ui.contextWindow
                val basisWindow = if (ui.contextWindowActual > 0) {
                    ui.contextWindowActual
                } else {
                    ctxWindow
                }
                val reserveTokens = ui.compactionReserveTokens
                val thresholdPct = if (basisWindow > 0 && reserveTokens in 1 until basisWindow) {
                    ((basisWindow - reserveTokens) * 100.0 / basisWindow).roundToInt()
                } else {
                    0
                }
                val pendingPin = ctxWindow > 0 && basisWindow > 0 && ctxWindow != basisWindow
                QueueModeRow(
                    title = packText("settings.compactionThreshold", "自动压缩阈值"),
                    hint = if (basisWindow > 0 && reserveTokens in 1 until basisWindow) {
                        packText(
                            "settings.compactionThresholdHint",
                            "上下文用到 {percent}%（{used} / {window}）时自动总结，预留 {reserve}{pending}",
                        )
                            .replace("{percent}", thresholdPct.toString())
                            .replace("{used}", formatTokens(basisWindow - reserveTokens))
                            .replace("{window}", formatTokens(basisWindow))
                            .replace("{reserve}", formatTokens(reserveTokens))
                            .replace(
                                "{pending}",
                                if (pendingPin) {
                                    packText(
                                        "settings.compactionThresholdPending",
                                        " · PiG 仍是 {actual}，重启后按 {pinned} 生效",
                                    )
                                        .replace("{actual}", formatTokens(basisWindow))
                                        .replace("{pinned}", formatTokens(ctxWindow))
                                } else {
                                    ""
                                },
                            )
                    } else {
                        packText(
                            "settings.compactionThresholdUnknown",
                            "需要先拿到上下文窗口和当前的预留值",
                        )
                    },
                    value = if (thresholdPct > 0) thresholdPct.toString() else "",
                    onPick = if (ui.settingsRemote) {
                        { picked -> vm.setCompactionThreshold(picked.toIntOrNull() ?: 80) }
                    } else {
                        {}
                    },
                    options = listOf(
                        "60" to "60%",
                        "70" to "70%",
                        "80" to "80%",
                        "88" to "88%",
                        "90" to "90%",
                    ),
                )
                SettingNumberRow(
                    title = packText("settings.reserveTokens", "压缩预留 tokens"),
                    hint = packText(
                        "settings.reserveTokensHint",
                        "上下文用到「窗口 − 预留」时触发自动压缩，默认 16384",
                    ),
                    value = ui.compactionReserveTokens,
                    enabled = ui.settingsRemote,
                    onSet = { vm.updateRelaySettings("compaction.reserveTokens" to it) },
                )
                SettingNumberRow(
                    title = packText("settings.keepRecentTokens", "压缩后保留最近 tokens"),
                    hint = packText(
                        "settings.keepRecentTokensHint",
                        "摘要时原样保留的最近上下文，默认 20000",
                    ),
                    value = ui.compactionKeepRecentTokens,
                    enabled = ui.settingsRemote,
                    onSet = { vm.updateRelaySettings("compaction.keepRecentTokens" to it) },
                )
                SettingNumberRow(
                    title = packText("settings.retryMax", "自动重试次数"),
                    hint = packText("settings.retryMaxHint", "默认 3 次，退避基数 2s"),
                    value = ui.retryMaxRetries,
                    enabled = ui.settingsRemote,
                    onSet = { vm.updateRelaySettings("retry.maxRetries" to it) },
                )
                QueueModeRow(
                    title = packText("settings.cacheWarming", "缓存保温"),
                    hint = packText(
                        "settings.cacheWarmingHint",
                        "空闲时提前刷新 prompt 缓存：off 关闭 · idle 仅在空闲时 · streaming 也在输出时",
                    ),
                    value = ui.cacheWarming,
                    onPick = if (ui.settingsRemote) {
                        { vm.updateRelaySettings("cacheWarming" to it) }
                    } else {
                        {}
                    },
                    options = listOf(
                        "off" to packText("settings.warmingOff", "关闭"),
                        "idle" to packText("settings.warmingIdle", "空闲"),
                        "streaming" to packText("settings.warmingStreaming", "始终"),
                    ),
                )

                HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                Text(packText("settings.data", "数据"), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            packText("settings.usage", "使用统计"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            packText(
                                "settings.usageHint",
                                "本会话的 token、缓存命中与费用（来自 pig 的会话统计）",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onOpenStats) {
                        Text(packText("settings.view", "查看"))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(packText("settings.clearLocal", "清空本地记录"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            packText("settings.clearLocalHint", "只清空当前 App 显示的 transcript，不影响会话文件"),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = vm::clearLocal) {
                        Text(packText("settings.clear", "清空"), color = scheme.error)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            packText("settings.compact", "压缩上下文"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            packText("settings.compactHint", "发送 /compact，减少上下文占用"),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = vm::compact) {
                        Text(packText("settings.compactAction", "压缩"))
                    }
                }

                if (ui.uiPack.layout.showSkinSwitcher &&
                    ui.uiPack.layout.skinSwitcherPlacement in setOf("settings", "both") &&
                    ui.uiPack.skins.isNotEmpty()
                ) {
                    HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            packIcon("skin", "palette"),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            packText("skin.title", "皮肤"),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    SkinSwitcher(
                        skins = ui.uiPack.skins,
                        selectedId = ui.skinId,
                        style = ui.uiPack.layout.skinSwitcherStyle,
                        cornerRadius = ui.uiPack.theme.cornerRadius,
                        onPick = vm::selectSkin,
                    )
                }

                if (ui.uiPack.layout.showPackManager &&
                    ui.uiPack.layout.packManagerPlacement in setOf("settings", "both")
                ) {
                    HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            packIcon("folder", "folder"),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            packText("pack.title", "UI pack"),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenPacks,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(
                            (ui.uiPack.theme.cornerRadius / 2).coerceAtLeast(8).dp,
                        ),
                    ) {
                        Text(
                            packText("pack.manage", "UI pack 管理"),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (ui.activePackId.isBlank()) {
                                packText("pack.builtin", "内置")
                            } else {
                                ui.uiPack.name
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                val settingsNav = ui.uiPack.nav.filter { it.placement == "settings" }
                if (settingsNav.isNotEmpty()) {
                    HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                    Text(packText("settings.extensions", "扩展"), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    settingsNav.forEach { item ->
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                vm.openNavItem(item)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(
                                (ui.uiPack.theme.cornerRadius / 2).coerceAtLeast(8).dp,
                            ),
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

                HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                Text(packText("settings.about", "关于"), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Text(
                    packText("settings.packInfo", "UI pack: {name} v{version} · {files} files")
                        .replace("{name}", ui.uiPack.name)
                        .replace("{version}", ui.uiPack.version.toString())
                        .replace("{files}", ui.uiPackFiles.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    packText("settings.packHash", "{hash} · cwd {cwd}")
                        .replace(
                            "{hash}",
                            ui.uiPackHash.take(8).ifBlank { packText("settings.noPack", "no pack") },
                        )
                        .replace("{cwd}", vm.cwd),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
                if (ui.sessionId.isNotBlank()) {
                    Text(
                        packText("settings.sessionInfo", "会话 {id} · {file}")
                            .replace("{id}", ui.sessionId.take(8))
                            .replace("{file}", ui.sessionFile.substringAfterLast('/')),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ui.uiPackError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(packText("settings.done", "完成")) }
        },
    )
}

@Composable
private fun QueueModeRow(
    title: String,
    hint: String,
    value: String,
    onPick: (String) -> Unit,
    options: List<Pair<String, String>>? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val chips = options ?: listOf(
        "one-at-a-time" to packText("settings.queueOne", "逐条"),
        "all" to packText("settings.queueAll", "全部"),
    )
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { (mode, label) ->
                FilterChip(
                    selected = value == mode,
                    onClick = { onPick(mode) },
                    label = { Text(label) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = scheme.primary.copy(alpha = 0.18f),
                        selectedLabelColor = scheme.primary,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SettingNumberRow(
    title: String,
    hint: String,
    value: Int,
    enabled: Boolean,
    onSet: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(if (value > 0) value.toString() else "") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { if (enabled) editing = true }, enabled = enabled) {
            Text(
                if (value > 0) formatTokens(value) else "—",
                color = if (enabled) scheme.primary else scheme.onSurfaceVariant,
            )
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntOrNull()?.takeIf { it > 0 }?.let(onSet)
                    editing = false
                }) { Text(packText("dialog.save", "保存")) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) {
                    Text(packText("dialog.cancel", "取消"))
                }
            },
        )
    }
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DrawerContent(
    vm: AppViewModel,
    ui: AppUiState,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onOpenPacks: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val corner = ui.uiPack.theme.cornerRadius
    val appearance = ui.uiPack.layout.drawer
    val modules = appearance.modules
    val buttonCorner = if (appearance.buttonRadius >= 0) appearance.buttonRadius else corner
    val buttonHeight = if (appearance.buttonHeight > 0) {
        Modifier.heightIn(min = appearance.buttonHeight.dp)
    } else {
        Modifier
    }
    val components = packComponents()
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
            .padding(horizontal = appearance.paddingH.dp)
            .padding(top = appearance.paddingV.dp),
        verticalArrangement = Arrangement.spacedBy(appearance.spacing.dp),
    ) {
        if (components.showDrawerBrand && "brand" in modules) Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(corner.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            scheme.primary.copy(alpha = appearance.brandCardAlpha),
                            scheme.tertiary.copy(alpha = appearance.brandCardAlpha * 0.3f),
                        ),
                    ),
                )
                .border(1.dp, scheme.primary.copy(alpha = 0.28f), RoundedCornerShape(corner.dp))
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandAvatar(appearance.brandAvatarSize.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            packText("header.title", "Pig"),
                            style = MaterialTheme.typography.titleLarge.copy(brush = brandBrush()),
                            fontWeight = FontWeight.Bold,
                        )
                        if (appearance.brandTagline) {
                            Text(
                                packText("drawer.tagline", "Termux 里的本地 coding agent"),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (appearance.brandPills) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoPill(
                        text = if (ui.connected) {
                            packText("header.online", "在线")
                        } else {
                            packText("settings.offline", "离线")
                        },
                        color = if (ui.connected) scheme.tertiary else scheme.onSurfaceVariant,
                    )
                    InfoPill(
                        text = ui.model.substringAfter('/').ifBlank {
                            packText("header.noModel", "无模型")
                        },
                        color = scheme.secondary,
                    )
                    if (ui.sessionId.isNotBlank()) {
                        InfoPill(text = packText("drawer.sessionPill", "会话 {id}").replace("{id}", ui.sessionId.take(6)))
                    }
                }
            }
        }

        if ("newSession" in modules) GradientButton(
            text = packText("drawer.newSession", "新会话"),
            icon = packIcon("add", "add"),
            cornerRadius = buttonCorner,
            iconSize = appearance.buttonIconSize,
            modifier = Modifier.fillMaxWidth().then(buttonHeight),
        ) {
            vm.newSession()
            onClose()
        }
        OutlinedButton(
            onClick = {
                onClose()
                onSettings()
            },
            modifier = Modifier.fillMaxWidth().then(buttonHeight),
            shape = RoundedCornerShape(buttonCorner.dp),
        ) {
            Icon(
                packIcon("settings", "settings"),
                null,
                modifier = Modifier.size(appearance.buttonIconSize.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(packText("drawer.settings", "设置"))
        }

        if ("skinSwitcher" in modules &&
            ui.uiPack.layout.showSkinSwitcher &&
            ui.uiPack.layout.skinSwitcherPlacement in setOf("drawer", "both") &&
            ui.uiPack.skins.isNotEmpty()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    packIcon("skin", "palette"),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(5.dp))
                SectionLabel(
                    packText("skin.title", "皮肤"),
                    size = appearance.sectionLabelSize,
                    uppercase = appearance.sectionLabelUppercase,
                )
            }
            SkinSwitcher(
                skins = ui.uiPack.skins,
                selectedId = ui.skinId,
                style = ui.uiPack.layout.skinSwitcherStyle,
                cornerRadius = corner,
                onPick = vm::selectSkin,
            )
        }

        if ("packManager" in modules &&
            ui.uiPack.layout.showPackManager &&
            ui.uiPack.layout.packManagerPlacement in setOf("drawer", "both")
        ) {
            OutlinedButton(
                onClick = {
                    onClose()
                    onOpenPacks()
                },
                modifier = Modifier.fillMaxWidth().then(buttonHeight),
                shape = RoundedCornerShape(buttonCorner.dp),
            ) {
                Icon(
                    packIcon("folder", "folder"),
                    null,
                    modifier = Modifier.size(appearance.buttonIconSize.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    packText("pack.manage", "UI pack 管理"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (ui.activePackId.isBlank()) {
                        packText("pack.builtin", "内置")
                    } else {
                        ui.uiPack.name
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val drawerNav = ui.uiPack.nav.filter { it.placement != "settings" }
        if ("panels" in modules && components.showPanels && drawerNav.isNotEmpty()) {
            SectionLabel(
                packText("drawer.panels", "面板"),
                size = appearance.sectionLabelSize,
                uppercase = appearance.sectionLabelUppercase,
            )
            drawerNav.forEach { item ->
                OutlinedButton(
                    onClick = {
                        vm.openNavItem(item)
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth().then(buttonHeight),
                    shape = RoundedCornerShape(buttonCorner.dp),
                ) {
                    Icon(
                        navIcon(item.icon),
                        contentDescription = null,
                        modifier = Modifier.size(appearance.buttonIconSize.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        run {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(
                packText("drawer.recent", "最近会话"),
                Modifier.weight(1f),
                size = appearance.sectionLabelSize,
                uppercase = appearance.sectionLabelUppercase,
            )
            IconButton(
                onClick = { vm.requestSessions() },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    packIcon("refresh", "refresh"),
                    contentDescription = packText("a11y.refreshSessions", "刷新会话"),
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
            shape = RoundedCornerShape(
                (if (appearance.searchRadius >= 0) appearance.searchRadius else corner / 2)
                    .coerceAtLeast(8).dp,
            ),
            leadingIcon = {
                Icon(
                    packIcon("search", "search"),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = scheme.onSurfaceVariant,
                )
            },
            placeholder = {
                Text(
                    packText("drawer.search", "搜索会话…"),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
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
                    current = session.path == ui.sessionFile,
                    cornerRadius = if (appearance.sessionRowRadius >= 0) {
                        appearance.sessionRowRadius
                    } else {
                        corner
                    },
                    selectedAlpha = appearance.sessionSelectedAlpha,
                    showId = appearance.showSessionId,
                    showTime = appearance.showSessionTime,
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
                        if (ui.sessions.isEmpty()) {
                            packText("drawer.empty", "没有可切换的会话")
                        } else {
                            packText("drawer.noMatch", "没有匹配的会话")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        }
        Spacer(Modifier.height(10.dp))

        renameTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { renameTarget = null },
                title = { Text(packText("session.renameTitle", "重命名会话")) },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text(packText("session.nameLabel", "名称（留空清除）")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.renameSession(target, renameText)
                            renameTarget = null
                        },
                    ) { Text(packText("session.save", "保存")) }
                },
                dismissButton = {
                    TextButton(onClick = { renameTarget = null }) {
                        Text(packText("session.cancel", "取消"))
                    }
                },
            )
        }
        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text(packText("session.deleteTitle", "删除会话？")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            packText(
                                "session.deleteBody",
                                "将删除「{name}」的会话文件，不可恢复。",
                            ).replace(
                                "{name}",
                                target.name.ifBlank {
                                    target.preview.ifBlank { target.id.take(8) }
                                },
                            ),
                        )
                        if (System.currentTimeMillis() - target.updated < 60_000) {
                            Text(
                                packText(
                                    "session.deleteRecent",
                                    "这个会话刚刚被修改过，可能还有其他 pig 进程在使用它。",
                                ),
                                color = scheme.error,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.deleteSession(target)
                            deleteTarget = null
                        },
                    ) { Text(packText("session.delete", "删除"), color = scheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) {
                        Text(packText("session.cancel", "取消"))
                    }
                },
            )
        }
    }
}

@Composable
private fun PackManagerDialog(
    vm: AppViewModel,
    ui: AppUiState,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val corner = ui.uiPack.theme.cornerRadius
    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::importPackZip) }
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(vm::importPackTree) }
    var deleteTarget by remember { mutableStateOf<LocalPackInfo?>(null) }
    LaunchedEffect(Unit) { vm.refreshLocalPacks() }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = scheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    packIcon("folder", "folder"),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = scheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(packText("pack.manage", "UI pack 管理"))
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    packText("pack.hint", "内置 pack 由 Termux 的 ~/.pig/android-ui 推送；导入的 pack 存在 App 私有目录。"),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                PackRow(
                    title = ui.relayPackName.ifBlank {
                        packText("pack.builtinPack", "内置 pack")
                    },
                    subtitle = packText("pack.builtinHint", "来自 ~/.pig/android-ui · {files} files")
                        .replace("{files}", ui.uiPackFiles.toString()),
                    selected = ui.activePackId.isBlank(),
                    cornerRadius = corner,
                    onPick = { vm.selectPack("") },
                )

                if (ui.localPacks.isEmpty()) {
                    Text(
                        packText("pack.empty", "还没有导入的 pack"),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                } else {
                    ui.localPacks.forEach { pack ->
                        PackRow(
                            title = pack.name,
                            subtitle = packText("pack.localHint", "v{version} · {files} files")
                                .replace("{version}", pack.version.toString())
                                .replace("{files}", pack.files.toString()),
                            selected = pack.id == ui.activePackId,
                            cornerRadius = corner,
                            onPick = { vm.selectPack(pack.id) },
                            onDelete = { deleteTarget = pack },
                        )
                    }
                }

                HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                zipPicker.launch(arrayOf("application/zip", "application/octet-stream"))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape((corner / 2).coerceAtLeast(8).dp),
                    ) {
                        Icon(
                            packIcon("upload", "upload"),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            packText("pack.importZip", "导入 ZIP"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = { runCatching { dirPicker.launch(null) } },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape((corner / 2).coerceAtLeast(8).dp),
                    ) {
                        Icon(
                            packIcon("folder", "folder"),
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            packText("pack.importDir", "导入目录"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(packText("pack.done", "完成"))
            }
        },
    )
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = scheme.surface,
            title = { Text(packText("pack.deleteTitle", "删除 pack？")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        packText(
                            "pack.deleteBody",
                            "将删除「{name}」及其文件，不可恢复。",
                        ).replace("{name}", target.name),
                    )
                    if (target.id == ui.activePackId) {
                        Text(
                            packText("pack.deleteActive", "这是当前正在使用的 pack，删除后会切回内置 pack。"),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deletePack(target.id)
                        deleteTarget = null
                    },
                ) {
                    Text(packText("pack.delete", "删除"), color = scheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(packText("dialog.cancel", "取消"))
                }
            },
        )
    }
}

@Composable
private fun PackRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    cornerRadius: Int,
    onPick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (selected) scheme.primary.copy(alpha = 0.10f) else scheme.surface,
        shape = RoundedCornerShape((cornerRadius / 2).coerceAtLeast(8).dp),
        border = BorderStroke(
            1.dp,
            if (selected) scheme.primary else scheme.outline.copy(alpha = 0.5f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    packIcon("check", "check"),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = scheme.primary,
                )
            }
            if (onDelete != null) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        packIcon("delete", "delete"),
                        contentDescription = packText("pack.delete", "删除"),
                        modifier = Modifier.size(15.dp),
                        tint = scheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SkinSwitcher(
    skins: List<UiSkin>,
    selectedId: String,
    style: String,
    cornerRadius: Int,
    onPick: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    if (style == "list") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            skins.forEach { skin ->
                val selected = skin.id == selectedId
                Surface(
                    color = if (selected) {
                        scheme.primary.copy(alpha = 0.10f)
                    } else {
                        scheme.surface
                    },
                    shape = RoundedCornerShape((cornerRadius / 2).coerceAtLeast(8).dp),
                    border = BorderStroke(
                        1.dp,
                        if (selected) scheme.primary else scheme.outline.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(skin.id) },
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SkinSwatch(skin)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            skin.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (selected) {
                            Icon(
                                packIcon("check", "check"),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = scheme.primary,
                            )
                        }
                    }
                }
            }
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            skins.forEach { skin ->
                val selected = skin.id == selectedId
                Surface(
                    color = if (selected) {
                        scheme.primary.copy(alpha = 0.12f)
                    } else {
                        scheme.surface
                    },
                    shape = RoundedCornerShape((cornerRadius / 2).coerceAtLeast(8).dp),
                    border = BorderStroke(
                        1.dp,
                        if (selected) scheme.primary else scheme.outline.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.clickable { onPick(skin.id) },
                ) {
                    Row(
                        Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SkinSwatch(skin)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            skin.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) scheme.primary else scheme.onSurface,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkinSwatch(skin: UiSkin) {
    Box(
        Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(skin.theme.primary, skin.theme.accent))),
    )
}

@Composable
private fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 0,
    uppercase: Boolean = false,
) {
    Text(
        if (uppercase) text.uppercase() else text,
        modifier = modifier,
        style = if (size > 0) {
            MaterialTheme.typography.labelSmall.copy(fontSize = size.sp)
        } else {
            MaterialTheme.typography.labelSmall
        },
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
private fun SessionBadge(session: SessionInfo) {
    val scheme = MaterialTheme.colorScheme
    val (label, color) = when {
        session.busy -> packText("session.busy", "运行中") to scheme.tertiary
        session.live -> packText("session.live", "已打开") to scheme.secondary
        session.visited -> packText("session.visited", "开过") to scheme.onSurfaceVariant
        else -> packText("session.history", "历史") to scheme.onSurfaceVariant.copy(alpha = 0.75f)
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (session.busy) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(3.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun ReadOnlyBanner() {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            packIcon("history", "history"),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            packText("banner.readOnly", "历史会话 · 发送消息后启动后端"),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionRow(
    session: SessionInfo,
    current: Boolean,
    cornerRadius: Int,
    selectedAlpha: Float = 0.13f,
    showId: Boolean = true,
    showTime: Boolean = true,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = session.name.ifBlank {
        session.preview.ifBlank { packText("session.untitled", "未命名会话") }
    }
    val components = packComponents()
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(cornerRadius.dp)
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (current) scheme.primary.copy(alpha = selectedAlpha) else Color.Transparent,
            )
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
            val meta = buildList {
                if (showId) add(session.id.take(8))
                if (showTime) add(DateUtils.getRelativeTimeSpanString(session.updated).toString())
                if (components.showSessionSize) add("${session.size / 1024}K")
            }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        if (components.showSessionBadges) {
            SessionBadge(session)
            Spacer(Modifier.width(2.dp))
        }
        if (current) {
            Icon(
                packIcon("check", "check"),
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
                    packIcon("more", "more"),
                    contentDescription = packText("a11y.sessionActions", "会话操作"),
                    modifier = Modifier.size(16.dp),
                    tint = scheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(packText("session.rename", "重命名")) },
                    leadingIcon = {
                        Icon(packIcon("edit", "edit"), null, modifier = Modifier.size(16.dp))
                    },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (current) {
                                packText("session.currentNoDelete", "当前会话不可删除")
                            } else {
                                packText("session.delete", "删除")
                            },
                        )
                    },
                    enabled = !current,
                    leadingIcon = {
                        Icon(
                            packIcon("delete", "delete"),
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

private sealed interface ChatListItem {
    val key: String
    val contentType: String

    data class Single(val message: ChatMsg) : ChatListItem {
        override val key: String = message.id
        override val contentType: String = message.role.name
    }

    data class ToolGroup(
        val tools: List<ChatMsg>,
        /** Reasoning written between the calls; shown collapsed on the group. */
        val thinking: String = "",
    ) : ChatListItem {
        // Keep the key stable while more same-name tools are appended.
        override val key: String = "group:${tools.first().id}"
        override val contentType: String = "tool-group"
    }
}

/** Cards rendered at once when a tool group is expanded. */
private const val GROUP_PAGE = 25

private fun buildChatListItems(messages: List<ChatMsg>, group: Boolean): List<ChatListItem> {
    if (!group) return messages.map { ChatListItem.Single(it) }
    val out = mutableListOf<ChatListItem>()
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        val name = message.toolName
        if (message.role == Role.TOOL && !name.isNullOrBlank()) {
            // Tool steps are separated by an assistant line that only carries
            // reasoning. Treating that line as a break meant a 100-call run was
            // never grouped, so collect it into the group instead.
            var end = index
            val tools = mutableListOf<ChatMsg>()
            val thinking = StringBuilder()
            while (end < messages.size) {
                val next = messages[end]
                if (next.role == Role.TOOL && next.toolName == name) {
                    tools.add(next)
                    end++
                    continue
                }
                if (next.role == Role.ASSISTANT && next.text.isBlank() && next.thinking.isNotBlank()) {
                    if (thinking.isNotEmpty()) thinking.append("\n\n")
                    thinking.append(next.thinking)
                    end++
                    continue
                }
                break
            }
            if (tools.size >= 2) {
                out.add(ChatListItem.ToolGroup(tools.toList(), thinking.toString()))
                index = end
                continue
            }
        }
        out.add(ChatListItem.Single(message))
        index++
    }
    return out
}

@Composable
private fun ToolGroupCard(
    tools: List<ChatMsg>,
    thinking: String,
    toolMode: String,
    cornerRadius: Int,
    onToolModeAll: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val theme = packTheme()
    val appearance = LocalUiPack.current.layout.toolCard
    val name = tools.first().toolName ?: "tool"
    val running = tools.any { it.running }
    val errors = tools.count { it.isError }
    var expanded by remember(tools.first().id) { mutableStateOf(false) }
    // Expanding a 130-call run must not build 130 cards (each parses its args
    // and result) on the frame that handles the tap.
    var showAll by remember(tools.first().id) { mutableStateOf(false) }
    val groupRadius = if (appearance.groupRadius >= 0) appearance.groupRadius else cornerRadius
    val shape = RoundedCornerShape(groupRadius.dp)
    val groupBorder = if (appearance.borderWidth > 0) {
        BorderStroke(
            appearance.borderWidth.dp,
            scheme.outline.copy(
                alpha = if (appearance.borderAlpha >= 0f) {
                    appearance.borderAlpha
                } else {
                    theme.cardBorderAlpha
                },
            ),
        )
    } else {
        null
    }
    val groupBox = (appearance.iconBoxSize - 4).coerceAtLeast(16)
    val groupTitle = if (appearance.titleSize > 0) {
        MaterialTheme.typography.titleSmall.copy(fontSize = appearance.titleSize.sp)
    } else {
        MaterialTheme.typography.titleSmall
    }
    Column(Modifier.fillMaxWidth()) {
    if (thinking.isNotBlank()) {
        ThinkingBlock(id = tools.first().id + ":group", text = thinking, streaming = false)
        Spacer(Modifier.height(4.dp))
    }
    Surface(
        color = toolSurfaceColor(appearance),
        shape = shape,
        border = groupBorder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(appearance.groupPadding.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 2.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(groupBox.dp)
                        .clip(RoundedCornerShape(appearance.iconRadius.dp))
                        .background(scheme.secondary.copy(alpha = appearance.iconAlpha)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        packIcon("tool", "build"),
                        contentDescription = null,
                        modifier = Modifier.size(
                            (appearance.iconSize - 1).coerceAtLeast(8).dp,
                        ),
                        tint = scheme.secondary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        packText("tool.groupHeader", "{tool} × {count}")
                            .replace("{tool}", name)
                            .replace("{count}", tools.size.toString()),
                        style = groupTitle,
                    )
                    Text(
                        buildString {
                            append(
                                packText("tool.groupCount", "{count} 次调用")
                                    .replace("{count}", tools.size.toString()),
                            )
                            if (errors > 0) {
                                append(" · ")
                                append(
                                    packText("tool.groupErrors", "{count} 失败")
                                        .replace("{count}", errors.toString()),
                                )
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    if (expanded) {
                        packText("tool.collapse", "收起")
                    } else {
                        packText("tool.expand", "展开")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                )
                Icon(
                    packIcon(if (expanded) "chevronUp" else "chevronDown", if (expanded) "up" else "down"),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = scheme.primary,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                val limit = if (showAll) tools.size else GROUP_PAGE
                tools.take(limit).forEachIndexed { index, tool ->
                    if (index > 0) Spacer(Modifier.height(6.dp))
                    ToolCard(tool, toolMode, cornerRadius, onToolModeAll)
                }
                if (!showAll && tools.size > limit) {
                    TextButton(onClick = { showAll = true }) {
                        Text(
                            packText("tool.showAll", "显示全部 {count} 个")
                                .replace("{count}", tools.size.toString()),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMsg>,
    busy: Boolean,
    streamingId: String?,
    hideThinking: Boolean,
    toolMode: String,
    assistantLabel: String,
    chatPaddingH: Int,
    chatPaddingV: Int,
    chatBottomPadding: Int,
    messageSpacing: Int,
    emptyAvatarSize: Int,
    codeBlockRadius: Int,
    codeBlockBorderAlpha: Float,
    types: TypeScale,
    cornerRadius: Int,
    avatarShadow: Boolean,
    bubbleShadow: Boolean,
    toolGroups: Boolean,
    modifier: Modifier = Modifier,
    onSuggestion: (String) -> Unit,
    onCopy: (String) -> Unit,
    onToolModeAll: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var autoScroll by remember { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }
    val last = messages.lastOrNull()
    val showTyping = busy && (
        last == null ||
            last.role == Role.USER ||
            (last.role == Role.ASSISTANT && last.text.isBlank() && last.thinking.isBlank())
        )

    // Follow new output only while the user is parked at the bottom. A manual
    // upward drag disables it; reaching the bottom again re-enables it.
    LaunchedEffect(listState) {
        launch {
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                if (scrolling && !programmaticScroll) autoScroll = false
            }
        }
        launch {
            snapshotFlow { listState.canScrollForward }.collect { canForward ->
                if (!canForward) autoScroll = true
            }
        }
    }

    LaunchedEffect(
        messages.size,
        last?.text?.length,
        last?.thinking?.length,
        last?.toolResult?.length,
        showTyping,
    ) {
        if (messages.isNotEmpty() && autoScroll) {
            programmaticScroll = true
            try {
                runCatching {
                    listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                }
            } finally {
                programmaticScroll = false
            }
        }
    }

    if (messages.isEmpty()) {
        EmptyState(onSuggestion, emptyAvatarSize)
        return
    }

    val displayItems = remember(messages, toolGroups) {
        buildChatListItems(messages, toolGroups)
    }

    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = chatPaddingH.dp,
                end = chatPaddingH.dp,
                top = chatPaddingV.dp,
                bottom = chatBottomPadding.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(messageSpacing.dp),
        ) {
            items(displayItems, key = { it.key }, contentType = { it.contentType }) { item ->
                when (item) {
                    is ChatListItem.Single -> MessageRow(
                        message = item.message,
                        assistantLabel = assistantLabel,
                        toolMode = toolMode,
                        types = types,
                        cornerRadius = cornerRadius,
                        codeBlockRadius = codeBlockRadius,
                        codeBlockBorderAlpha = codeBlockBorderAlpha,
                        streaming = item.message.id == streamingId,
                        hideThinking = hideThinking,
                        avatarShadow = avatarShadow,
                        bubbleShadow = bubbleShadow,
                        onCopy = onCopy,
                        onToolModeAll = onToolModeAll,
                    )
                    is ChatListItem.ToolGroup -> ToolGroupCard(
                        tools = item.tools,
                        thinking = item.thinking,
                        toolMode = toolMode,
                        cornerRadius = cornerRadius,
                        onToolModeAll = onToolModeAll,
                    )
                }
            }
            if (showTyping) {
                item(key = "typing") {
                    TypingRow(assistantLabel, avatarShadow)
                }
            }
        }
        AnimatedVisibility(
            visible = !autoScroll,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 10.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
                shadowElevation = 6.dp,
                modifier = Modifier.clickable {
                    scope.launch {
                        programmaticScroll = true
                        autoScroll = true
                        try {
                            listState.animateScrollToItem(
                                (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0),
                            )
                        } finally {
                            programmaticScroll = false
                        }
                    }
                },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        packIcon("chevronDown", "down"),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        packText("chat.jumpBottom", "回到底部"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun packColor(name: String): Color = when (name) {
    "primary" -> MaterialTheme.colorScheme.primary
    "secondary" -> MaterialTheme.colorScheme.secondary
    "tertiary" -> MaterialTheme.colorScheme.tertiary
    "onSurface" -> MaterialTheme.colorScheme.onSurface
    "error" -> MaterialTheme.colorScheme.error
    "success" -> MaterialTheme.colorScheme.tertiary
    "warning" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun toolSurfaceColor(appearance: com.linuxwff.pigapp.UiToolCardAppearance): Color {
    val scheme = MaterialTheme.colorScheme
    val base = when (appearance.background) {
        "surfaceVariant" -> scheme.surfaceVariant
        "background" -> scheme.background
        "transparent" -> Color.Transparent
        else -> scheme.surface
    }
    return base.copy(alpha = appearance.backgroundAlpha)
}

private val suggestions = listOf(
    "这个项目是做什么的？",
    "看看有哪些可以改进的地方",
    "帮我跑一下测试",
)

@Composable
private fun EmptyState(onSuggestion: (String) -> Unit, avatarSize: Int) {
    val scheme = MaterialTheme.colorScheme
    val components = packComponents()
    val packSuggestions = packText(
        "empty.suggestions",
        "这个项目是做什么的？|看看有哪些可以改进的地方|帮我跑一下测试",
    ).split("|").map { it.trim() }.filter { it.isNotEmpty() }
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
                BrandAvatar(avatarSize.dp)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                packText("empty.title", "开始聊天"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (components.showSuggestions) {
                Spacer(Modifier.height(5.dp))
                Text(
                    packText("empty.hint", "在下面输入，或试试："),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                packSuggestions.forEach { text ->
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
}

@Composable
private fun CopyButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(26.dp)) {
        Icon(
            packIcon("copy", "copy"),
            contentDescription = packText("a11y.copy", "复制"),
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The model's visible reasoning. Collapsed by default (a phone screen is not
 * the place for a 3k-character chain of thought), auto-expanded while the block
 * is still streaming so the wait has something to show. Tapping remembers the
 * choice for that message only.
 */
@Composable
private fun ThinkingBlock(id: String, text: String, streaming: Boolean) {
    val style = LocalUiPack.current.layout.thinking
    if (!style.show) return
    val scheme = MaterialTheme.colorScheme
    var open by rememberSaveable(id) { mutableStateOf(!style.collapsedByDefault) }
    var userChoice by rememberSaveable(id) { mutableStateOf(false) }
    // Follow the stream until the user taps; after that their choice wins.
    LaunchedEffect(streaming) {
        if (userChoice) return@LaunchedEffect
        open = if (streaming) style.expandWhileStreaming else !style.collapsedByDefault
    }
    val radius = if (style.radius >= 0) style.radius else 10
    val background = when (style.background) {
        "surface" -> scheme.surface
        "background" -> scheme.background
        "transparent" -> Color.Transparent
        else -> scheme.surfaceVariant
    }
    val textStyle = if (style.fontSize > 0) {
        MaterialTheme.typography.bodySmall.copy(fontSize = style.fontSize.sp)
    } else {
        MaterialTheme.typography.bodySmall
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius.dp))
            .background(background.copy(alpha = style.backgroundAlpha)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    userChoice = true
                    open = !open
                }
                .padding(horizontal = style.paddingH.dp, vertical = style.paddingV.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                style.title.ifBlank { packText("chat.thinking", "思考") },
                style = MaterialTheme.typography.labelSmall,
                color = packColor(style.titleColor),
            )
            if (style.showCount) {
                Spacer(Modifier.width(6.dp))
                Text(
                    packText("chat.thinkingSize", "{count} 字")
                        .replace("{count}", text.length.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = packColor(style.textColor).copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (open) style.expandedGlyph else style.collapsedGlyph,
                style = MaterialTheme.typography.labelSmall,
                color = packColor(style.textColor),
            )
        }
        AnimatedVisibility(visible = open) {
            Text(
                text,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = style.maxHeight.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = style.paddingH.dp,
                        end = style.paddingH.dp,
                        bottom = style.paddingV.dp,
                    ),
                style = textStyle,
                fontStyle = if (style.italic) FontStyle.Italic else null,
                color = packColor(style.textColor),
            )
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
    codeBlockRadius: Int,
    codeBlockBorderAlpha: Float,
    streaming: Boolean,
    hideThinking: Boolean,
    avatarShadow: Boolean,
    bubbleShadow: Boolean,
    onCopy: (String) -> Unit,
    onToolModeAll: (String) -> Unit,
) {
    val components = packComponents()
    val theme = packTheme()
    when (message.role) {
        Role.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            val shape = theme.bubbleRadius?.let { RoundedCornerShape(it.dp) } ?: RoundedCornerShape(
                topStart = (cornerRadius + 4).dp,
                topEnd = (cornerRadius + 4).dp,
                bottomEnd = if (cornerRadius == 0) 0.dp else 5.dp,
                bottomStart = (cornerRadius + 4).dp,
            )
            val scheme = MaterialTheme.colorScheme
            val bubbleAppearance = LocalUiPack.current.layout.bubble
            var selecting by remember(message.id) { mutableStateOf(false) }
            val bubble: @Composable () -> Unit = {
                Box(
                    Modifier
                        .widthIn(max = components.bubbleMaxWidthDp.dp)
                        .then(
                            if (bubbleShadow) {
                                Modifier.shadow(
                                    theme.bubbleElevation.dp,
                                    shape,
                                    ambientColor = scheme.primary,
                                    spotColor = scheme.primary,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .clip(shape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    (theme.bubbleStart ?: scheme.primary).copy(alpha = theme.bubbleStartAlpha),
                                    (theme.bubbleEnd ?: scheme.tertiary).copy(alpha = theme.bubbleEndAlpha),
                                ),
                            ),
                            shape,
                        )
                        .then(
                            if (bubbleAppearance.borderWidth > 0) {
                                Modifier.border(
                                    bubbleAppearance.borderWidth.dp,
                                    scheme.primary.copy(alpha = theme.bubbleBorderAlpha),
                                    shape,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .padding(
                            horizontal = bubbleAppearance.paddingH.dp,
                            vertical = bubbleAppearance.paddingV.dp,
                        ),
                ) {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = packColor(bubbleAppearance.textColor),
                    )
                }
            }
            Box(
                Modifier.pointerInput(message.id) {
                    detectTapGestures(
                        onLongPress = {
                            if (components.showSelection) selecting = true
                        },
                    )
                },
            ) {
                if (selecting && components.showSelection) {
                    SelectionContainer { bubble() }
                } else {
                    bubble()
                }
            }
            if (selecting && components.showSelection) {
                TextButton(onClick = { selecting = false }) {
                    Text(packText("chat.done", "完成"))
                }
            }
            if (components.showCopy && message.text.isNotBlank()) {
                CopyButton { onCopy(message.text) }
            }
        }

        Role.ASSISTANT -> Row(Modifier.fillMaxWidth()) {
            val bubbleAppearance = LocalUiPack.current.layout.bubble
            val scheme = MaterialTheme.colorScheme
            if (components.assistantAvatar) {
                BrandAvatar(components.messageAvatarSize.dp, shadow = avatarShadow)
                Spacer(Modifier.width(9.dp))
            }
            Column(Modifier.weight(1f)) {
                var selecting by remember(message.id) { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        assistantLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (selecting && components.showSelection) {
                        TextButton(onClick = { selecting = false }) {
                            Text(packText("chat.done", "完成"))
                        }
                    }
                    if (components.showCopy && message.text.isNotBlank()) {
                        CopyButton { onCopy(message.text) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (!hideThinking && message.thinking.isNotBlank()) {
                    ThinkingBlock(
                        id = message.id,
                        text = message.thinking,
                        streaming = streaming,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                val content: @Composable () -> Unit = {
                    if (streaming) {
                        // Parsing a growing Markdown document on every delta is
                        // O(n²); show the raw text while it streams and render
                        // Markdown once the message is complete.
                        Text(
                            message.text.ifBlank { "…" },
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        AssistantMarkdown(
                            text = message.text.ifBlank { "…" },
                            modifier = Modifier.fillMaxWidth(),
                            types = types,
                            cornerRadius = cornerRadius,
                            codeBlockRadius = codeBlockRadius,
                            codeBlockBorderAlpha = codeBlockBorderAlpha,
                        )
                    }
                }
                val contentBlock: @Composable () -> Unit = {
                    if (bubbleAppearance.assistantBubble) {
                        val bubbleShape = RoundedCornerShape(
                            (theme.bubbleRadius ?: (cornerRadius + 4)).dp,
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(bubbleShape)
                                .background(
                                    scheme.surfaceVariant.copy(
                                        alpha = bubbleAppearance.assistantBackgroundAlpha,
                                    ),
                                )
                                .then(
                                    if (bubbleAppearance.borderWidth > 0) {
                                        Modifier.border(
                                            bubbleAppearance.borderWidth.dp,
                                            scheme.outline.copy(alpha = bubbleAppearance.assistantBorderAlpha),
                                            bubbleShape,
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .padding(
                                    horizontal = bubbleAppearance.paddingH.dp,
                                    vertical = bubbleAppearance.paddingV.dp,
                                ),
                        ) {
                            CompositionLocalProvider(
                                LocalContentColor provides packColor(bubbleAppearance.assistantTextColor),
                            ) {
                                content()
                            }
                        }
                    } else {
                        content()
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(message.id) {
                            detectTapGestures(
                                onLongPress = {
                                    if (components.showSelection) selecting = true
                                },
                            )
                        },
                ) {
                    if (selecting && components.showSelection) {
                        SelectionContainer { contentBlock() }
                    } else {
                        contentBlock()
                    }
                }
            }
        }

        Role.TOOL -> Column(Modifier.fillMaxWidth().padding(start = 33.dp)) {
            ToolCard(message, toolMode, cornerRadius, onToolModeAll)
        }

        Role.NOTICE -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                message.text,
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
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
private fun ToolCard(
    message: ChatMsg,
    mode: String,
    cornerRadius: Int,
    onToolModeAll: (String) -> Unit,
) {
    var viewMode by remember(message.id, mode) {
        mutableStateOf(
            when (mode) {
                "expanded" -> ToolMode.EXPANDED
                "collapsed" -> ToolMode.COLLAPSED
                else -> ToolMode.SUMMARY
            },
        )
    }
    val toolStrings = LocalUiPack.current.strings
    val view = remember(
        message.toolName,
        message.toolArgs,
        message.toolResult,
        message.toolDetails,
        message.running,
        message.isError,
        toolStrings,
    ) {
        toolPresentation(
            name = message.toolName,
            argsJson = message.toolArgs,
            result = message.toolResult,
            detailsJson = message.toolDetails,
            isError = message.isError,
            running = message.running,
            text = { key, fallback -> toolStrings.values[key] ?: fallback },
        )
    }
    val scheme = MaterialTheme.colorScheme
    val components = packComponents()
    val theme = packTheme()
    val appearance = LocalUiPack.current.layout.toolCard
    val toolRadius = if (appearance.radius >= 0) appearance.radius else cornerRadius
    val toolShape = RoundedCornerShape(toolRadius.dp)
    val toolColor = toolSurfaceColor(appearance)
    val toolBorder = if (appearance.borderWidth > 0) {
        BorderStroke(
            appearance.borderWidth.dp,
            scheme.outline.copy(
                alpha = if (appearance.borderAlpha >= 0f) {
                    appearance.borderAlpha
                } else {
                    theme.cardBorderAlpha
                },
            ),
        )
    } else {
        null
    }
    val titleStyle = if (appearance.titleSize > 0) {
        MaterialTheme.typography.titleSmall.copy(fontSize = appearance.titleSize.sp)
    } else {
        MaterialTheme.typography.titleSmall
    }
    val metricsColor = packColor(appearance.metricsColor)
    val collapsedBox = (appearance.iconBoxSize - 4).coerceAtLeast(16)

    if (viewMode == ToolMode.COLLAPSED) {
        // Collapsed is deliberately minimal: tool name only, tap to see details.
        Surface(
            color = toolColor,
            shape = toolShape,
            border = toolBorder,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { viewMode = ToolMode.SUMMARY }
                    .padding(
                        horizontal = appearance.collapsedPaddingH.dp,
                        vertical = appearance.collapsedPaddingV.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(collapsedBox.dp)
                        .clip(RoundedCornerShape(appearance.iconRadius.dp))
                        .background(scheme.secondary.copy(alpha = appearance.iconAlpha)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        packIcon("tool", "build"),
                        contentDescription = null,
                        modifier = Modifier.size(
                            (appearance.iconSize - 1).coerceAtLeast(8).dp,
                        ),
                        tint = scheme.secondary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    message.toolName ?: "tool",
                    style = titleStyle,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    message.running -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    message.isError -> InfoPill(text = packText("tool.failed", "失败"), color = scheme.error)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    packText("tool.expand", "展开"),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                )
                Icon(
                    packIcon("chevronDown", "down"),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = scheme.primary,
                )
            }
        }
        return
    }

    Surface(
        color = toolColor,
        shape = toolShape,
        border = toolBorder,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(appearance.padding.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(appearance.iconBoxSize.dp)
                        .clip(RoundedCornerShape(appearance.iconRadius.dp))
                        .background(scheme.secondary.copy(alpha = appearance.iconAlpha)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        packIcon("tool", "build"),
                        contentDescription = null,
                        modifier = Modifier.size(appearance.iconSize.dp),
                        tint = scheme.secondary,
                    )
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(message.toolName ?: "tool", style = titleStyle)
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
                    message.isError -> InfoPill(text = packText("tool.failed", "失败"), color = scheme.error)
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
                    color = metricsColor,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ToolModeChip(
                    packText("tool.collapse", "收起"),
                    viewMode == ToolMode.COLLAPSED,
                    cornerRadius,
                    onClick = { viewMode = ToolMode.COLLAPSED },
                    onLongClick = { onToolModeAll("collapsed") },
                )
                ToolModeChip(
                    packText("tool.summary", "摘要"),
                    viewMode == ToolMode.SUMMARY,
                    cornerRadius,
                    onClick = { viewMode = ToolMode.SUMMARY },
                    onLongClick = { onToolModeAll("summary") },
                )
                ToolModeChip(
                    packText("tool.expand", "展开"),
                    viewMode == ToolMode.EXPANDED,
                    cornerRadius,
                    onClick = { viewMode = ToolMode.EXPANDED },
                    onLongClick = { onToolModeAll("expanded") },
                )
            }

            when (viewMode) {
                ToolMode.COLLAPSED -> Unit
                ToolMode.SUMMARY -> {
                    Spacer(Modifier.height(6.dp))
                    when {
                        message.running -> Text(
                            packText("tool.running", "运行中…"),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                        view.diff.isNotBlank() -> DiffBlock(
                            view.diff.lineSequence().take(components.toolSummaryLines).joinToString("\n"),
                            cornerRadius,
                            components.toolSummaryLines,
                        )
                        view.summary.isNotBlank() -> CodeBlock(
                            view.summary,
                            cornerRadius,
                            components.toolSummaryLines,
                        )
                        view.expanded.isNotBlank() -> CodeBlock(
                            view.expanded,
                            cornerRadius,
                            components.toolSummaryLines,
                        )
                    }
                }
                ToolMode.EXPANDED -> {
                    Spacer(Modifier.height(6.dp))
                    when {
                        view.diff.isNotBlank() -> DiffBlock(
                            view.diff,
                            cornerRadius,
                            components.toolExpandedLines,
                        )
                        view.expanded.isNotBlank() -> CodeBlock(
                            view.expanded,
                            cornerRadius,
                            components.toolExpandedLines,
                        )
                        view.summary.isNotBlank() -> CodeBlock(
                            view.summary,
                            cornerRadius,
                            components.toolExpandedLines,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolModeChip(
    label: String,
    selected: Boolean,
    cornerRadius: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val appearance = LocalUiPack.current.layout.toolCard
    val shape = RoundedCornerShape(appearance.chipRadius.dp)
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) scheme.primary else scheme.onSurfaceVariant,
        fontWeight = if (selected) FontWeight.SemiBold else null,
        modifier = Modifier
            .padding(start = 3.dp)
            .clip(shape)
            .background(
                if (selected) scheme.primary.copy(alpha = appearance.chipAlpha) else Color.Transparent,
            )
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() },
                )
            }
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun CodeBlock(text: String, cornerRadius: Int, maxLines: Int = 200) {
    if (text.isBlank()) return
    val body = remember(text, maxLines) {
        val shown = text.lineSequence().take(maxLines).toList()
        val truncated = text.lineSequence().count() > maxLines
        shown.joinToString("\n").ifEmpty { " " } + if (truncated) "\n…" else ""
    }
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape((cornerRadius / 2).coerceAtLeast(6).dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.background.copy(alpha = 0.6f))
            .border(1.dp, scheme.outline.copy(alpha = 0.35f), shape)
            .padding(9.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            body,
            fontFamily = packCodeFont(),
            fontSize = 11.sp,
            softWrap = false,
        )
    }
}

@Composable
private fun DiffBlock(diff: String, cornerRadius: Int, maxLines: Int = 200) {
    if (diff.isBlank()) return
    val scheme = MaterialTheme.colorScheme
    val theme = packTheme()
    val annotated = remember(diff, scheme.error, scheme.onSurfaceVariant, theme.success, maxLines) {
        val lines = diff.lineSequence().take(maxLines).toList()
        val truncated = diff.lineSequence().count() > maxLines
        buildAnnotatedString {
            lines.forEachIndexed { index, line ->
                val color = when {
                    line.startsWith("+") && !line.startsWith("+++") -> theme.success
                    line.startsWith("-") && !line.startsWith("---") -> scheme.error
                    else -> scheme.onSurfaceVariant
                }
                withStyle(SpanStyle(color = color)) {
                    append(line.ifEmpty { " " })
                }
                if (index != lines.lastIndex) append("\n")
            }
            if (truncated) append("\n…")
        }
    }
    val shape = RoundedCornerShape((cornerRadius / 2).coerceAtLeast(6).dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.background.copy(alpha = 0.6f))
            .border(1.dp, scheme.outline.copy(alpha = 0.35f), shape)
            .padding(9.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            annotated,
            fontFamily = packCodeFont(),
            fontSize = 11.sp,
            softWrap = false,
        )
    }
}

@Composable
private fun CommandPalette(
    commands: List<com.linuxwff.pigapp.UiCommand>,
    onPick: (com.linuxwff.pigapp.UiCommand) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Column(
            Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            commands.forEach { command ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(command) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "/${command.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.primary,
                        fontFamily = packCodeFont(),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        command.description,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (commands.size > 6) {
                Text(
                    packText("composer.commandCount", "共 {count} 个命令，上下滑动查看")
                        .replace("{count}", commands.size.toString()),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun PendingBanner(count: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.secondary.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            packIcon("send", "send"),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = scheme.secondary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            packText("banner.pending", "已插队，等待当前步骤 · {count}")
                .replace("{count}", count.toString()),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.secondary,
        )
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
    onSteer: () -> Unit,
    onFollowUp: () -> Unit,
    onStop: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val components = packComponents()
    val buttonSize = components.composerButtonSize.dp
    val theme = packTheme()
    val composerRadius = if (layout.composerRadius >= 0) {
        layout.composerRadius
    } else {
        theme.composerRadius ?: (cornerRadius + 8)
    }
    val composerElevation = if (layout.composerElevation >= 0) {
        layout.composerElevation
    } else {
        theme.composerElevation
    }
    val inputStyle = MaterialTheme.typography.bodyMedium.let { base ->
        if (layout.composerFontSize > 0) base.copy(fontSize = layout.composerFontSize.sp) else base
    }.copy(color = packColor(layout.composerTextColor))
    val buttonIconSize = (layout.composerButtonIconSize.takeIf { it > 0 } ?: 21).dp
    val placeholderColor = when (layout.composerPlaceholderColor) {
        "primary" -> scheme.primary
        "secondary" -> scheme.secondary
        "tertiary" -> scheme.tertiary
        "text" -> scheme.onSurface
        "error" -> scheme.error
        else -> scheme.onSurfaceVariant
    }
    val canSend = connected && value.isNotBlank()
    val shape = RoundedCornerShape(
        topStart = composerRadius.dp,
        topEnd = composerRadius.dp,
    )

    Surface(
        color = scheme.surface.copy(alpha = layout.composerSurfaceAlpha),
        shape = shape,
        shadowElevation = composerElevation.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = layout.composerPaddingH.dp,
                    vertical = layout.composerPaddingV.dp,
                ),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (layout.composerMinHeight > 0) {
                                Modifier.heightIn(min = layout.composerMinHeight.dp)
                            } else {
                                Modifier
                            },
                        ),
                    textStyle = inputStyle,
                    placeholder = {
                        Text(
                            if (busy) {
                                packText("composer.steerPlaceholder", "插队指令…")
                            } else {
                                layout.composerPlaceholder
                            },
                            style = MaterialTheme.typography.bodyMedium.let { base ->
                                if (layout.composerFontSize > 0) {
                                    base.copy(fontSize = layout.composerFontSize.sp)
                                } else {
                                    base
                                }
                            },
                            color = placeholderColor.copy(alpha = 0.75f),
                        )
                    },
                    maxLines = layout.composerMaxLines,
                    shape = RoundedCornerShape(layout.composerInputRadius.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = scheme.primary.copy(
                            alpha = (layout.composerInputBorderAlpha + 0.2f).coerceAtMost(1f),
                        ),
                        unfocusedBorderColor = scheme.outline.copy(alpha = layout.composerInputBorderAlpha),
                        focusedContainerColor = scheme.surfaceVariant.copy(alpha = layout.composerInputAlpha),
                        unfocusedContainerColor = scheme.surfaceVariant.copy(alpha = layout.composerInputAlpha),
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
                if (busy) {
                    Box(
                        Modifier
                            .size(buttonSize)
                            .clip(CircleShape)
                            .background(scheme.error.copy(alpha = 0.16f))
                            .clickable { onStop() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            packIcon("stop", "stop"),
                            contentDescription = packText("a11y.stop", "停止"),
                            modifier = Modifier.size(buttonIconSize),
                            tint = scheme.error,
                        )
                    }
                    if (canSend) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .size(buttonSize)
                                .shadow(8.dp, CircleShape, ambientColor = scheme.primary, spotColor = scheme.primary)
                                .clip(CircleShape)
                                .background(brandBrush())
                                .pointerInput(onSteer, onFollowUp) {
                                    detectTapGestures(
                                        onTap = { onSteer() },
                                        onLongPress = { onFollowUp() },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                packIcon("send", "send"),
                                contentDescription = packText("a11y.steer", "插队"),
                                modifier = Modifier.size(buttonIconSize),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                } else {
                    Box(
                        Modifier
                            .size(buttonSize)
                            .then(
                                if (canSend) {
                                    Modifier.shadow(8.dp, CircleShape, ambientColor = scheme.primary, spotColor = scheme.primary)
                                } else {
                                    Modifier
                                },
                            )
                            .clip(CircleShape)
                            .background(if (canSend) brandBrush() else SolidColor(scheme.surfaceVariant))
                            .clickable(enabled = canSend) { onSubmit() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            packIcon("send", "send"),
                            contentDescription = packText("a11y.send", "发送"),
                            modifier = Modifier.size(buttonIconSize),
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary else scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (busy && canSend && components.showSteerHint) {
                Text(
                    packText("composer.steerHint", "点发送=插队 · 长按=排队"),
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (!connected) {
                Text(
                    packText("composer.offline", "未连接 · 点右上角重连，或从菜单启动 relay"),
                    modifier = Modifier.padding(start = 6.dp, top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TypingRow(label: String, avatarShadow: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        BrandAvatar(24.dp, shadow = avatarShadow)
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
                "select" -> TextButton(onClick = { vm.answer(cancelled = true) }) {
                    Text(packText("dialog.cancel", "取消"))
                }
                "confirm" -> TextButton(onClick = { vm.answer(confirmed = true) }) {
                    Text(packText("dialog.allow", "允许"))
                }
                else -> TextButton(onClick = { vm.answer(value = value) }) {
                    Text(packText("dialog.ok", "确定"))
                }
            }
        },
        dismissButton = {
            when (request.method) {
                "confirm" -> TextButton(onClick = { vm.answer(confirmed = false) }) {
                    Text(packText("dialog.deny", "拒绝"))
                }
                "select" -> Unit
                else -> TextButton(onClick = { vm.answer(cancelled = true) }) {
                    Text(packText("dialog.cancel", "取消"))
                }
            }
        },
    )
}

@Composable
private fun StatusBar(status: String, extensionStatuses: Map<String, String>) {
    val scheme = MaterialTheme.colorScheme
    val activity = when (status) {
        "thinking" -> packText("status.thinking", "思考中")
        "working" -> packText("status.working", "处理中")
        "compacting" -> packText("status.compacting", "压缩中")
        "retrying" -> packText("status.retrying", "重试中")
        "switching" -> packText("status.switching", "切换中")
        "restoring" -> packText("status.restoring", "恢复中")
        else -> status
    }.trim()
    // Extension lines (⏱ rate, 🔒 permission mode, ...) outlive the turn they
    // were written in. They are labels, not activity: no spinner once the agent
    // has stopped, otherwise the ring keeps turning forever.
    val extras = extensionStatuses.values.filter { it.isNotBlank() && it != activity }
    val label = (listOf(activity) + extras).filter { it.isNotBlank() }.joinToString(" · ")
    Row(
        Modifier
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(CircleShape)
            .background(scheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (activity.isNotBlank()) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 1.5.dp,
                color = scheme.secondary,
            )
            Spacer(Modifier.width(9.dp))
        }
        Text(
            label,
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
                packIcon("warning", "warning"),
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
                    packIcon("close", "close"),
                    contentDescription = packText("a11y.close", "关闭"),
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
