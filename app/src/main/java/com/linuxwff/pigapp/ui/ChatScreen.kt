package com.linuxwff.pigapp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.widget.Toast
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
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
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.linuxwff.pigapp.AppLog
import com.linuxwff.pigapp.AppUiState
import com.linuxwff.pigapp.AppViewModel
import com.linuxwff.pigapp.BackupExporter
import com.linuxwff.pigapp.ChatMsg
import com.linuxwff.pigapp.LocalPackInfo
import com.linuxwff.pigapp.LogExporter
import com.linuxwff.pigapp.PendingImage
import com.linuxwff.pigapp.RelayLauncher
import com.linuxwff.pigapp.Role
import com.linuxwff.pigapp.SessionInfo
import com.linuxwff.pigapp.TypeScale
import com.linuxwff.pigapp.UpdateChecker
import com.linuxwff.pigapp.UpdateInfo
import com.linuxwff.pigapp.UiLayout
import com.linuxwff.pigapp.UiRequest
import com.linuxwff.pigapp.UiSkin
import com.linuxwff.pigapp.resolve
import com.linuxwff.pigapp.toolPresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PigApp(vm: AppViewModel) {
    val ui = vm.ui
    // One composer draft per session (kept by the view model, so it survives
    // session switches and app restarts). A conversation that is still being
    // created uses a placeholder key until its file exists.
    val draftKey = vm.draftKey()
    // Keyed on the conversation, not on the storage key: adopting the session
    // file of a conversation that had none must not reset what is typed.
    var draft by remember(ui.draftEpoch) { mutableStateOf(vm.draftText(draftKey)) }
    LaunchedEffect(draftKey, draft) { vm.setDraft(draftKey, draft) }
    var showSettings by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var showPackManager by remember { mutableStateOf(false) }
    // System photo picker: GetMultipleContents needs no storage permission.
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris -> if (uris.isNotEmpty()) vm.addAttachments(uris) }
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
        if (draft.isNotBlank() || ui.attachments.isNotEmpty()) {
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
                snackbarHost = {
                    SnackbarHost(snackbar) { data ->
                        val chrome = LocalUiPack.current.layout.chrome
                        val context = LocalContext.current
                        val copied = packText("toast.copied", "已复制")
                        Snackbar(
                            snackbarData = data,
                            // Toasts carry relay/pig failures; make them
                            // copyable instead of a sentence that disappears.
                            modifier = Modifier
                                .padding(horizontal = chrome.toastInsetH.dp)
                                .pointerInput(data.visuals.message) {
                                    detectTapGestures(
                                        onLongPress = {
                                            val clipboard = context.getSystemService(
                                                Context.CLIPBOARD_SERVICE,
                                            ) as ClipboardManager
                                            clipboard.setPrimaryClip(
                                                ClipData.newPlainText(
                                                    "Pig",
                                                    data.visuals.message,
                                                ),
                                            )
                                            Toast.makeText(
                                                context,
                                                copied,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                    )
                                },
                            containerColor = if (chrome.toastContainer.isBlank()) {
                                SnackbarDefaults.color
                            } else {
                                packColor(chrome.toastContainer)
                            },
                            contentColor = if (chrome.toastText.isBlank()) {
                                SnackbarDefaults.contentColor
                            } else {
                                packColor(chrome.toastText)
                            },
                            actionColor = if (chrome.toastAction.isBlank()) {
                                SnackbarDefaults.actionColor
                            } else {
                                packColor(chrome.toastAction)
                            },
                            shape = if (chrome.toastShape >= 0) {
                                RoundedCornerShape(chrome.toastShape.dp)
                            } else {
                                SnackbarDefaults.shape
                            },
                        )
                    }
                },
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
                        if (ui.pending != null && ui.pendingHidden) {
                            HiddenRequestBanner(ui.pending, vm::showPending)
                        }
                        Composer(
                            value = draft,
                            // Store every keystroke, not just the last value a
                            // LaunchedEffect flushed: the session file can be
                            // adopted between two frames, and then the draft has
                            // to be found under the new key already.
                            onValueChange = { draft = it; vm.setDraft(draftKey, it) },
                            busy = ui.busy,
                            connected = ui.connected,
                            attachments = ui.attachments,
                            layout = ui.uiPack.layout,
                            cornerRadius = ui.uiPack.theme.cornerRadius,
                            onSubmit = submit,
                            onSteer = { sendDraft(true) },
                            onFollowUp = { sendDraft(false) },
                            onStop = vm::abort,
                            onPickImages = { imagePicker.launch("image/*") },
                            onRemoveAttachment = vm::removeAttachment,
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
                        loadingHistory = ui.historyLoading,
                        loadingOlder = ui.historyLoadingOlder,
                        hasOlderHistory = ui.historyHasOlder,
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
                        onLoadOlder = vm::loadOlderHistory,
                        onRetryHistory = vm::reloadHistory,
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
        if (ui.providerManagerOpen) {
            ProviderManagerDialog(vm, ui) { vm.closeProviderManager() }
        }
        if (showStats) {
            StatsDialog(ui, onRefresh = vm::refreshStats) { showStats = false }
        }
        if (showPackManager) {
            PackManagerDialog(vm, ui) { showPackManager = false }
        }
    }

    ui.pendingSwitch?.let { session ->
        val title = if (session.unsaved) {
        session.name.ifBlank { packText("session.newUnsaved", "新会话（还没发送）") }
    } else {
        session.name.ifBlank {
            session.preview.ifBlank { packText("session.untitled", "未命名会话") }
        }
    }
    val components = packComponents()
        PackAlertDialog(
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
        PackAlertDialog(
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
    ui.pending?.takeIf { !ui.pendingHidden }?.let { PendingDialog(it, vm) }
    // One full-screen image viewer for transcript and Markdown pictures.
    ImageViewerHost { image ->
        vm.saveChatImage(image.bitmap, image.mimeType)
        ImageViewer.close()
    }
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
    val command = RelayLauncher.installCommand(context)
    val enableCommand = RelayLauncher.enableExternalAppsCommand()
    val termuxInstalled = RelayLauncher.isTermuxInstalled(context)
    val permitted = RelayLauncher.hasPermission(context)
    val scheme = MaterialTheme.colorScheme
    // Termux works in its own sandbox: the only progress the app can honestly
    // show is time-based staging plus the moment the relay answers.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.installRunning) {
        while (ui.installRunning) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = if (ui.installRunning && ui.installStartedAt > 0) {
        ((now - ui.installStartedAt) / 1000).coerceAtLeast(0)
    } else {
        0
    }
    // The same installer serves the first install and a relay update; keep the
    // title honest about which one is running.
    val updatingRelay = ui.installRunning && ui.relayVersion.isNotBlank()
    val stage = when {
        elapsed < 45 -> packText("setup.stageStart", "正在下载安装脚本（会先换国内源）")
        elapsed < 150 -> packText("setup.stagePig", "正在安装 pig")
        elapsed < 360 -> packText(
            "setup.stageGo",
            "正在安装 Go 并构建扩展（最慢的一步，约 100MB）",
        )
        elapsed < 720 -> packText("setup.stageLong", "还在构建扩展；Termux 窗口里能看到实时日志")
        else -> packText(
            "setup.stageStuck",
            "超过 12 分钟还没连上：去 Termux 看看是不是报错了，可以点「重试」。",
        )
    }
    PackAlertDialog(
        onDismissRequest = vm::dismissSetup,
        shape = RoundedCornerShape(24.dp),
        containerColor = scheme.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandAvatar(30.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (updatingRelay) {
                        packText("setup.titleUpdate", "正在更新 relay")
                    } else {
                        packText("setup.title", "Termux 后端还没准备好")
                    },
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.installRunning) {
                    if (ui.installTotal > 0) {
                        // The relay forwards the installer's own stage file, so
                        // this is real progress instead of a guessed timeline.
                        LinearProgressIndicator(
                            progress = {
                                (ui.installStep.toFloat() / ui.installTotal)
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = scheme.primary,
                            trackColor = scheme.surfaceVariant,
                        )
                        Text(
                            packText("setup.installStage", "步骤 {step}/{total} · {message}")
                                .replace("{step}", ui.installStep.toString())
                                .replace("{total}", ui.installTotal.toString())
                                .replace("{message}", ui.installMessage),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = scheme.primary,
                            trackColor = scheme.surfaceVariant,
                        )
                    }
                    Text(
                        packText("setup.installElapsed", "安装中 · 已 {seconds}s").replace(
                            "{seconds}",
                            elapsed.toString(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Before the relay exists (mirror/pig/Go) only the
                    // time-based hint is honest; later the real stage is shown.
                    if (ui.installTotal == 0) {
                        Text(
                            stage,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        packText(
                            "setup.installAutoConnect",
                            "装好后这里会自动连上，不用再点重试。",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Text(
                    packText("setup.intro", "Pig App 只是前端：pig 和 pig-relay 要装在 Termux 里。"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (ui.termuxBlocked) {
                    // Termux refuses everything until this switch is on; it is
                    // the only step the app cannot do for the user, so it gets
                    // the shortest possible command of its own.
                    Text(
                        packText(
                            "setup.enableTitle",
                            "1. 让 Termux 允许 App 执行命令（只需一次）",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        packText(
                            "setup.enableHint",
                            "全新 Termux 默认关闭 allow-external-apps，App 的后台命令会被直接拒绝。在 Termux 里跑这一行：",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Surface(
                        color = scheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.5f)),
                    ) {
                        SelectionContainer {
                            Text(
                                enableCommand,
                                fontFamily = packCodeFont(),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = vm::copyExternalAppsAndOpen) {
                            Text(packText("setup.enableAndOpen", "复制并打开 Termux"))
                        }
                        TextButton(onClick = vm::copyExternalAppsCommand) {
                            Text(packText("setup.copyOnly", "只复制"))
                        }
                    }
                    Text(
                        packText(
                            "setup.enableThen",
                            "2. 粘贴回车后回到这里，点「一键安装」；它会显示进度并自动连接。",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        packText(
                            "setup.installOrManual",
                            "也可以直接在 Termux 里跑下面这条，它会一并开启开关：",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                } else if (!termuxInstalled) {
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
                        packText("setup.stepCopy", "1. 点「复制命令」"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        packText("setup.stepPaste", "2. 点「打开 Termux」，粘贴并回车"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        packText("setup.stepWait", "3. 装完回到这里，会自动连接"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Surface(
                    color = scheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.5f)),
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (termuxInstalled && !ui.termuxBlocked) {
                        TextButton(onClick = vm::copyInstallCommandAndOpen) {
                            Text(packText("setup.copyAndOpen", "复制命令并打开 Termux"))
                        }
                        TextButton(onClick = vm::openTermux) {
                            Text(packText("setup.openTermux", "打开 Termux"))
                        }
                    }
                }
                Text(
                    packText(
                        "setup.scriptHint",
                        "脚本会开启 allow-external-apps、安装 pig 和 pig-relay。全新 Termux 必须先手动跑一次，之后才能一键安装。",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                ui.setupHint?.let {
                    SelectionContainer {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.error,
                        )
                    }
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
                ui.termuxBlocked -> TextButton(onClick = vm::copyExternalAppsAndOpen) {
                    Text(packText("setup.enableAndOpen", "复制并打开 Termux"))
                }
                else -> TextButton(onClick = { vm.bootstrap() }) {
                    Text(packText("setup.oneClick", "一键安装"))
                }
            }
        },
        dismissButton = {
            Row {
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
    PackAlertDialog(
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

@Composable
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
    /** Non-null opens the custom auto-compaction threshold editor. */
    var thresholdEditor by remember { mutableStateOf<Int?>(null) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf("") }
    var updateFailed by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    val updateScope = rememberCoroutineScope()
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    // packText is @Composable; onClick/coroutine bodies may not call it, so
    // resolve the update messages once here.
    val msgUpdating = packText("settings.updateChecking", "正在检查…")
    val msgAvailable = packText("settings.updateAvailable", "发现新版本 {tag}（当前 {current}）")
    val msgLatest = packText("settings.updateLatest", "已是最新版本（{version}）")
    val msgFailed = packText("settings.updateFailed", "检查失败：{error}")
    val msgCopied = packText(
        "settings.updateCommandCopied",
        "已复制 Termux 更新命令，粘贴到 Termux 执行",
    )
    var showLogs by remember { mutableStateOf(false) }
    var exportingLog by remember { mutableStateOf(false) }
    var logStatus by remember { mutableStateOf("") }
    var logFailed by remember { mutableStateOf(false) }
    var pendingLog by remember { mutableStateOf<String?>(null) }
    val logScope = rememberCoroutineScope()
    val msgLogExporting = packText("settings.logsExporting", "导出中…")
    val msgLogSaved = packText("settings.logsSaved", "已保存到 {path}")
    val msgLogFailed = packText("settings.logsFailed", "导出失败：{error}")
    val msgLogCancelled = packText("settings.logsCancelled", "已取消导出")
    // Android 10+ writes to Download/Pig without a permission; below that the
    // system file picker is the only way onto "sdcard" without asking for
    // WRITE_EXTERNAL_STORAGE.
    val logSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val text = pendingLog
        pendingLog = null
        exportingLog = false
        if (text == null || uri == null) {
            logStatus = msgLogCancelled
            return@rememberLauncherForActivityResult
        }
        val result = runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("无法写入所选位置")
        }
        logFailed = result.isFailure
        logStatus = result.fold(
            onSuccess = {
                msgLogSaved.replace("{path}", uri.lastPathSegment ?: uri.toString())
            },
            onFailure = { error ->
                msgLogFailed.replace("{error}", error.message ?: error.toString())
            },
        )
    }
    // ---- Backup / restore / uninstall -----------------------------------
    var exportDialog by remember { mutableStateOf(false) }
    var uninstallDialog by remember { mutableStateOf(false) }
    // Android < 10 cannot write Download/Pig without a permission: remember the
    // choices while the system save picker is open.
    var pendingExport by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    var pendingImport by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    val backupSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupExporter.MIME),
    ) { uri ->
        val spec = pendingExport
        pendingExport = null
        if (uri != null && spec != null) vm.exportBackupToUri(uri, spec.first, spec.second)
    }
    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) pendingImport = uri to displayName(context, uri)
    }
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
    // Geometry comes from the UI pack: `layout/chat.json` → `settings`. The app
    // only remembers which preset the user picked.
    val settingsLayout = ui.uiPack.layout.settings
    val size = settingsLayout.resolve(ui.settingsSize)
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
                    .fillMaxWidth(size.width)
                    .fillMaxHeight(size.height),
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
                            packText("settings.title", "设置"),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = packText("a11y.close", "关闭"),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (settingsLayout.showSizeSwitcher && settingsLayout.sizes.size > 1) {
                            Column {
                                Text(
                                    packText("settings.window", "窗口大小"),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    packText(
                                        "settings.windowHint",
                                        "由 UI pack 提供的尺寸；选项会记住，内容仍然可以滚动",
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    settingsLayout.sizes.forEach { preset ->
                                        FilterChip(
                                            selected = ui.settingsSize == preset.id,
                                            onClick = { vm.setSettingsSize(preset.id) },
                                            label = {
                                                Text(
                                                    preset.label.ifBlank {
                                                        packText(
                                                            "settings.size.${preset.id}",
                                                            preset.id,
                                                        )
                                                    },
                                                )
                                            },
                                            shape = packChipShape(),
                                            colors = packChipColors(),
                                        )
                                    }
                                }
                            }
                        }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            packText("settings.restartRelay", "重启 relay"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            packText(
                                "settings.restartRelayHint",
                                "更新 Termux 组件后使用；会结束当前 pig 进程并重连",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = !ui.busy && ui.status != "compacting",
                        onClick = vm::restartRelay,
                    ) {
                        Text(packText("settings.restartRelayAction", "重启"))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            packText("settings.relayVersion", "relay 版本"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            buildString {
                                append(
                                    ui.relayVersion.ifBlank {
                                        if (ui.connected) {
                                            packText("settings.relayOld", "旧版（无版本号）")
                                        } else {
                                            "—"
                                        }
                                    },
                                )
                                append(" · App ")
                                append(appVersion.ifBlank { "?" })
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                ui.relayOutdated -> scheme.error
                                ui.relayAhead -> scheme.primary
                                else -> scheme.onSurfaceVariant
                            },
                        )
                    }
                    if (ui.relayOutdated && ui.connected) {
                        TextButton(
                            enabled = !ui.installRunning && !ui.busy &&
                                ui.status != "compacting",
                            onClick = {
                                onDismiss()
                                vm.updateRelay()
                            },
                        ) {
                            Text(packText("settings.relayUpdate", "更新 relay"))
                        }
                    }
                }
                if (ui.relayOutdated) {
                    Text(
                        packText(
                            "settings.relayOutdatedHint",
                            "relay 与 App 版本不一致：备份、供应商、安装进度等功能可能不可用。" +
                                "更新会重跑安装脚本（先编译扩展），完成后自动重启 relay。",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.error,
                    )
                }
                if (ui.relayAhead) {
                    Text(
                        packText(
                            "settings.relayAheadHint",
                            "relay 比 App 新；建议更新 App（关于 → 检查更新）。不会把 relay 降级。",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.primary,
                    )
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
                                shape = packChipShape(),
                                colors = packChipColors(),
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
                    value = if (thresholdPct !in listOf(50, 55, 60, 65, 70, 75, 80) && thresholdPct > 0) {
                        "custom"
                    } else {
                        thresholdPct.toString()
                    },
                    onPick = if (ui.settingsRemote) {
                        { picked ->
                            if (picked == "custom") {
                                thresholdEditor = if (thresholdPct in 1..99) thresholdPct else 80
                            } else {
                                vm.setCompactionThreshold(picked.toIntOrNull() ?: 80)
                            }
                        }
                    } else {
                        {}
                    },
                    options = buildList {
                        listOf(50, 55, 60, 65, 70, 75, 80).forEach { pct ->
                            add(pct.toString() to "$pct%")
                        }
                        add(
                            "custom" to if (thresholdPct in 1..99 &&
                                thresholdPct !in listOf(50, 55, 60, 65, 70, 75, 80)
                            ) {
                                "$thresholdPct%（自定义）"
                            } else {
                                packText("settings.compactionThresholdCustom", "自定义…")
                            },
                        )
                    },
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

                HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                Text(
                    packText("settings.maintenance", "备份与维护"),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            packText("settings.backupExport", "导出备份"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            packText(
                                "settings.backupExportHint",
                                "打包 ~/.pig：密钥、模型、设置、扩展、会话与 UI pack；文件含明文密钥，请妥善保管",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = ui.backupRemote && !ui.maintenanceBusy,
                        onClick = { exportDialog = true },
                    ) {
                        Text(packText("settings.backupExportAction", "导出"))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            packText("settings.backupImport", "导入备份"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            packText(
                                "settings.backupImportHint",
                                "从 .tar.gz 恢复覆盖 ~/.pig；恢复前自动备份现有数据，之后重启 pig",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = ui.backupRemote && !ui.maintenanceBusy,
                        onClick = { runCatching { backupPicker.launch(arrayOf("*/*")) } },
                    ) {
                        Text(packText("settings.backupImportAction", "导入"))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            packText("settings.uninstall", "卸载 pig 与 pig-relay"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            packText(
                                "settings.uninstallHint",
                                "停止进程并删除 Termux 里的二进制；~/.pig 数据默认保留",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = !ui.maintenanceBusy,
                        onClick = { uninstallDialog = true },
                    ) {
                        Text(
                            packText("settings.uninstallAction", "卸载"),
                            color = scheme.error,
                        )
                    }
                }
                if (!ui.backupRemote && ui.connected) {
                    Text(
                        packText(
                            "settings.backupNeedsRelay",
                            "备份需要新版 relay：在 Termux 重跑安装命令，再点上面的「重启 relay」。",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.error,
                    )
                }
                if (ui.maintenanceBusy) {
                    val total = ui.maintenanceTotal
                    val sent = ui.maintenanceSent
                    Column {
                        Text(
                            buildString {
                                append(ui.maintenanceLabel)
                                if (sent > 0) {
                                    append(" · ")
                                    append(formatBytes(sent))
                                    if (total > 0) {
                                        append(" / ")
                                        append(formatBytes(total))
                                    }
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (total > 0) {
                                    (sent.toFloat() / total).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = scheme.primary,
                            trackColor = scheme.surfaceVariant,
                        )
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            packText("settings.appVersion", "Pig Android {version}")
                                .replace("{version}", appVersion.ifBlank { "?" }),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            packText("settings.appVersionHint", "App 版本 · relay {relay} · pig 在 Termux 里运行")
                                .replace(
                                    "{relay}",
                                    ui.relayVersion.ifBlank {
                                        if (ui.connected) {
                                            packText("settings.relayOld", "旧版（无版本号）")
                                        } else {
                                            packText("settings.offline", "离线")
                                        }
                                    },
                                ),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        enabled = !updateChecking,
                        onClick = {
                            updateChecking = true
                            updateFailed = false
                            updateStatus = msgUpdating
                            updateScope.launch {
                                UpdateChecker.check(appVersion)
                                    .onSuccess { info ->
                                        updateFailed = false
                                        if (info.newer) {
                                            updateStatus = msgAvailable
                                                .replace("{tag}", info.tag)
                                                .replace("{current}", appVersion)
                                            updateInfo = info
                                        } else {
                                            updateStatus = msgLatest
                                                .replace("{version}", appVersion)
                                        }
                                    }
                                    .onFailure { error ->
                                        updateFailed = true
                                        updateStatus = msgFailed
                                            .replace("{error}", error.message ?: error.toString())
                                    }
                                updateChecking = false
                            }
                        },
                    ) {
                        Text(
                            if (updateChecking) {
                                packText("settings.updateCheckingShort", "检查中…")
                            } else {
                                packText("settings.checkUpdate", "检查更新")
                            },
                        )
                    }
                }
                if (updateStatus.isNotBlank()) {
                    Text(
                        updateStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (updateFailed) scheme.error else scheme.primary,
                    )
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("Pig update", UpdateChecker.INSTALL_COMMAND),
                            )
                            updateStatus = msgCopied
                        },
                    ) {
                        Text(
                            packText("settings.copyInstall", "复制 Termux 更新命令"),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_URL))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    ) {
                        Text(
                            packText("settings.github", "GitHub 发布页"),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Text(
                    packText(
                        "settings.updateTermuxHint",
                        "App 检查的是 GitHub 最新 Release；pig / pig-relay 用上面的安装命令在 Termux 里更新，更新后到设置顶部点「重启 relay」。",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
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
                    HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                packText("settings.logs", "诊断日志"),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                packText(
                                    "settings.logsHint",
                                    "连接、帧、安装结果和错误；导出到 Download/Pig（token 与密钥已打码）",
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showLogs = true }) {
                            Text(packText("settings.logsView", "查看"))
                        }
                        TextButton(
                            enabled = !exportingLog,
                            onClick = {
                                if (exportingLog) return@TextButton
                                exportingLog = true
                                logFailed = false
                                logStatus = msgLogExporting
                                val app = context.applicationContext
                                val name = AppLog.suggestedName()
                                logScope.launch {
                                    val text = withContext(Dispatchers.IO) { AppLog.exportText(app) }
                                    val saved = withContext(Dispatchers.IO) {
                                        LogExporter.direct(app, text, name)
                                    }
                                    if (saved != null) {
                                        exportingLog = false
                                        logStatus = msgLogSaved.replace("{path}", saved.location)
                                    } else {
                                        pendingLog = text
                                        runCatching { logSaver.launch(name) }.onFailure { error ->
                                            exportingLog = false
                                            logFailed = true
                                            logStatus = msgLogFailed.replace(
                                                "{error}",
                                                error.message ?: error.toString(),
                                            )
                                        }
                                    }
                                }
                            },
                        ) {
                            Text(
                                if (exportingLog) {
                                    msgLogExporting
                                } else {
                                    packText("settings.logsExport", "导出")
                                },
                            )
                        }
                    }
                    if (logStatus.isNotBlank()) {
                        Text(
                            logStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (logFailed) scheme.error else scheme.primary,
                        )
                    }
                    HorizontalDivider(color = scheme.outline.copy(alpha = 0.2f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(packText("settings.done", "完成"))
                        }
                    }
                }
            }
        }
    }
    thresholdEditor?.let { current ->
        CompactionThresholdDialog(
            initial = current,
            onSave = { percent ->
                vm.setCompactionThreshold(percent)
                thresholdEditor = null
            },
            onDismiss = { thresholdEditor = null },
        )
    }
    if (showLogs) LogDialog(onDismiss = { showLogs = false })
    updateInfo?.let { info ->
        UpdateDialog(
            info = info,
            currentVersion = appVersion,
            onOpen = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(info.url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            onDismiss = { updateInfo = null },
        )
    }
    if (exportDialog) {
        BackupExportDialog(
            onStart = { sessions, uiPack ->
                exportDialog = false
                if (!vm.exportBackupToDownload(sessions, uiPack)) {
                    // Android < 10: ask for a target through the system picker.
                    pendingExport = sessions to uiPack
                    runCatching { backupSaver.launch(BackupExporter.suggestedName()) }
                        .onFailure { pendingExport = null }
                }
            },
            onDismiss = { exportDialog = false },
        )
    }
    pendingImport?.let { (uri, name) ->
        BackupImportDialog(
            fileName = name,
            onConfirm = {
                pendingImport = null
                vm.importBackup(uri, name)
            },
            onDismiss = { pendingImport = null },
        )
    }
    if (uninstallDialog) {
        UninstallDialog(
            onConfirm = { purge ->
                uninstallDialog = false
                vm.uninstallPig(purge)
            },
            onDismiss = { uninstallDialog = false },
        )
    }
}

@Composable
private fun BackupExportDialog(
    onStart: (sessions: Boolean, uiPack: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var sessions by remember { mutableStateOf(true) }
    var uiPack by remember { mutableStateOf(true) }
    PackAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(packText("backup.exportTitle", "导出备份")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    packText(
                        "backup.exportBody",
                        "备份包含 ~/.pig 下的配置、扩展、密钥、模型列表、UI pack 与会话。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    packText(
                        "backup.exportSecrets",
                        "⚠ 备份里有明文 API key 与令牌。只保存到你信任的位置，不要随手分享。",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.error,
                )
                SettingToggle(
                    title = packText("backup.includeSessions", "包含会话记录"),
                    subtitle = packText(
                        "backup.includeSessionsHint",
                        "占备份的大部分体积；关掉只备份配置与密钥",
                    ),
                    checked = sessions,
                    onCheckedChange = { sessions = it },
                )
                SettingToggle(
                    title = packText("backup.includeUi", "包含 UI pack"),
                    subtitle = packText("backup.includeUiHint", "主题、布局、皮肤与图标"),
                    checked = uiPack,
                    onCheckedChange = { uiPack = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(sessions, uiPack) }) {
                Text(packText("backup.exportAction", "导出"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(packText("dialog.cancel", "取消"))
            }
        },
    )
}

@Composable
private fun BackupImportDialog(
    fileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    PackAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(packText("backup.importTitle", "导入备份")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    packText(
                        "backup.importBody",
                        "备份中的文件会覆盖 ~/.pig 下的同名文件；备份里没有的现有会话会保留。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    packText(
                        "backup.importAuto",
                        "恢复前 relay 会先把当前 ~/.pig 打包到 ~/.pig/backups/；恢复后 pig 用新配置重新启动。",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(packText("backup.importAction", "恢复"), color = scheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(packText("dialog.cancel", "取消"))
            }
        },
    )
}

@Composable
private fun UninstallDialog(
    onConfirm: (purgeData: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var purge by remember { mutableStateOf(false) }
    PackAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(packText("backup.uninstallTitle", "卸载 pig 与 pig-relay")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    packText(
                        "backup.uninstallBody",
                        "会停止正在运行的 pig 和 relay，并删除 Termux 里的 pig、pig-relay 二进制。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                SettingToggle(
                    title = packText("backup.uninstallPurge", "同时删除 ~/.pig 数据"),
                    subtitle = packText(
                        "backup.uninstallPurgeHint",
                        "配置、密钥、模型、会话和 UI pack 全部删除，无法撤销",
                    ),
                    checked = purge,
                    onCheckedChange = { purge = it },
                )
                Text(
                    packText(
                        "backup.uninstallKeep",
                        "不勾选时数据保留，之后重新安装即可继续使用。",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (purge) scheme.error else scheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(purge) }) {
                Text(packText("backup.uninstallAction", "卸载"), color = scheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(packText("dialog.cancel", "取消"))
            }
        },
    )
}

/** Display name of a picked document, for the import confirmation. */
private fun displayName(context: Context, uri: Uri): String {
    val queried = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    return queried ?: uri.lastPathSegment ?: "pig-backup.tar.gz"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f".format(bytes / (1L shl 30).toFloat()).removeSuffix(".0") + " GB"
    bytes >= 1L shl 20 -> "%.1f".format(bytes / (1L shl 20).toFloat()).removeSuffix(".0") + " MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/**
 * Live tail of the relay/pig log. It refreshes every second so a failure that
 * happens *now* shows up without reconnecting - the app cannot read logcat, and
 * these lines only exist on the relay's side.
 */
@Composable
private fun LogDialog(onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val emptyText = packText("settings.logsEmpty", "还没有日志")
    var text by remember { mutableStateOf(AppLog.tail(400)) }
    LaunchedEffect(Unit) {
        while (true) {
            text = AppLog.tail(400)
            delay(1000)
        }
    }
    PackAlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = scheme.surface,
        title = { Text(packText("settings.logsTitle", "诊断日志 · 最近 400 行")) },
        text = {
            Box(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SelectionContainer {
                    Text(
                        text.ifBlank { emptyText },
                        fontFamily = packCodeFont(),
                        fontSize = 10.sp,
                        color = scheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(packText("settings.close", "关闭"))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    val clipboard = context
                        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Pig log", AppLog.snapshot()))
                },
            ) {
                Text(packText("settings.logsCopy", "复制全部"))
            }
        },
    )
}

@Composable
private fun UpdateDialog(
    info: UpdateInfo,
    currentVersion: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    PackAlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = scheme.surface,
        title = { Text(packText("settings.updateDialogTitle", "发现新版本 {tag}").replace("{tag}", info.tag)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    packText("settings.updateDialogCurrent", "当前版本 {current} → {latest}")
                        .replace("{current}", currentVersion.ifBlank { "?" })
                        .replace("{latest}", info.version),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                if (info.notes.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            info.notes,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpen(); onDismiss() }) {
                Text(packText("settings.updateOpen", "打开下载页"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(packText("settings.updateLater", "稍后")) }
        },
    )
}

@Composable
private fun CompactionThresholdDialog(
    initial: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(initial.toString()) }
    val percent = text.filter { it.isDigit() }.take(2).toIntOrNull()
    PackAlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = scheme.surface,
        title = { Text(packText("settings.compactionThresholdCustom", "自定义压缩阈值")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    packText(
                        "settings.compactionThresholdCustomHint",
                        "上下文用到这个比例时自动总结旧消息。预留 tokens 由「窗口 × (1 − 比例)」换算，范围 1–99%。",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input.filter { it.isDigit() }.take(2)
                    },
                    singleLine = true,
                    label = { Text("%") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = scheme.primary.copy(alpha = 0.7f),
                        unfocusedBorderColor = scheme.outline.copy(alpha = 0.5f),
                    ),
                )
                Text(
                    if (percent in 1..99) {
                        packText("settings.compactionThresholdCustomValue", "= {value}%")
                            .replace("{value}", percent.toString())
                    } else {
                        packText("settings.compactionThresholdCustomInvalid", "请输入 1–99 之间的整数")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (percent in 1..99) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = percent in 1..99,
                onClick = { percent?.let(onSave) },
            ) {
                Text(packText("dialog.save", "保存"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(packText("dialog.cancel", "取消")) }
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
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            chips.forEach { (mode, label) ->
                FilterChip(
                    selected = value == mode,
                    onClick = { onPick(mode) },
                    label = { Text(label) },
                    shape = packChipShape(),
                    colors = packChipColors(),
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
        PackAlertDialog(
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
            PackAlertDialog(
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
            PackAlertDialog(
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

    PackAlertDialog(
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
                            subtitle = if (pack.bundled) {
                                packText("pack.bundledHint", "随 App 内置 · v{version} · {files} files")
                                    .replace("{version}", pack.version.toString())
                                    .replace("{files}", pack.files.toString())
                            } else {
                                packText("pack.localHint", "v{version} · {files} files")
                                    .replace("{version}", pack.version.toString())
                                    .replace("{files}", pack.files.toString())
                            },
                            selected = pack.id == ui.activePackId,
                            cornerRadius = corner,
                            onPick = { vm.selectPack(pack.id) },
                            onDelete = if (pack.bundled) null else ({ deleteTarget = pack }),
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
        PackAlertDialog(
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
        session.unsaved -> packText("session.unsaved", "未发送") to scheme.primary
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
    val title = if (session.unsaved) {
        session.name.ifBlank { packText("session.newUnsaved", "新会话（还没发送）") }
    } else {
        session.name.ifBlank {
            session.preview.ifBlank { packText("session.untitled", "未命名会话") }
        }
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
                if (showId && session.id.isNotBlank()) add(session.id.take(8))
                if (showTime) add(DateUtils.getRelativeTimeSpanString(session.updated).toString())
                if (components.showSessionSize && !session.unsaved) add("${session.size / 1024}K")
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
            if (!session.unsaved) IconButton(
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
            packColor(appearance.borderColor).copy(
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
                        .background(packColor(appearance.iconBackground).copy(alpha = appearance.iconAlpha)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        packIcon("tool", "build"),
                        contentDescription = null,
                        modifier = Modifier.size(
                            (appearance.iconSize - 1).coerceAtLeast(8).dp,
                        ),
                        tint = packColor(appearance.iconTint),
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
    loadingHistory: Boolean,
    loadingOlder: Boolean,
    hasOlderHistory: Boolean,
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
    onLoadOlder: () -> Unit,
    onRetryHistory: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var autoScroll by remember { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }
    // Where the reader was before an older page was prepended.
    var olderAnchor by remember { mutableStateOf<OlderAnchor?>(null) }
    var pulled by remember { mutableStateOf(0f) }
    var pullArmed by remember { mutableStateOf(true) }
    val pullThreshold = with(LocalDensity.current) { 120.dp.toPx() }
    val last = messages.lastOrNull()
    val showTyping = busy && (
        last == null ||
            last.role == Role.USER ||
            (last.role == Role.ASSISTANT && last.text.isBlank() && last.thinking.isBlank() &&
                last.images.isEmpty())
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
        last?.images?.size,
        showTyping,
    ) {
        if (messages.isNotEmpty() && autoScroll && olderAnchor == null) {
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

    // Prepending an older page shifts every item down; put the reader back on
    // the row they were looking at instead of leaving them at the new top.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }.collect { total ->
            val pending = olderAnchor ?: return@collect
            if (total <= pending.total) return@collect
            olderAnchor = null
            listState.scrollToItem(pending.index + (total - pending.total), pending.offset)
        }
    }
    // A page that comes back without adding items must not leave an anchor
    // behind: a later message arriving would scroll the reader somewhere else.
    LaunchedEffect(olderAnchor) {
        val pending = olderAnchor ?: return@LaunchedEffect
        delay(8_000)
        if (olderAnchor === pending) olderAnchor = null
    }
    // Re-arm the pull once the page has landed, so a second pull is not dead.
    LaunchedEffect(loadingHistory) {
        if (!loadingHistory) {
            pulled = 0f
            pullArmed = true
        }
    }

    // Pulling past the top asks for the next older page: no button to hunt for.
    // The pull is consumed so the overscroll stretch does not swallow it first.
    val olderPageConnection = remember(hasOlderHistory, loadingHistory, pullThreshold) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y < 0f) {
                    pulled = 0f
                    pullArmed = true
                    return Offset.Zero
                }
                if (available.y == 0f || !hasOlderHistory) return Offset.Zero
                if (listState.canScrollBackward) return Offset.Zero
                pulled += available.y
                if (pullArmed && pulled >= pullThreshold) {
                    pulled = 0f
                    pullArmed = false
                    if (!loadingHistory) {
                        olderAnchor = OlderAnchor(
                            listState.firstVisibleItemIndex,
                            listState.firstVisibleItemScrollOffset,
                            listState.layoutInfo.totalItemsCount,
                        )
                        onLoadOlder()
                    }
                }
                return Offset(0f, available.y)
            }
        }
    }

    if (messages.isEmpty()) {
        if (loadingHistory) {
            HistoryLoadingState(onRetry = onRetryHistory)
        } else {
            EmptyState(onSuggestion, emptyAvatarSize)
        }
        return
    }

    val displayItems = remember(messages, toolGroups) {
        buildChatListItems(messages, toolGroups)
    }

    Box(modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(olderPageConnection),
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
        // Feedback while a page is in flight; kept out of the list so it cannot
        // shift item indices underneath the scroll anchor above.
        AnimatedVisibility(
            visible = loadingOlder,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(cornerRadius),
                shadowElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(11.dp),
                        strokeWidth = 1.5.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        packText("chat.loadingOlder", "正在载入更早的消息…"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

/** Scroll position captured before an older page was prepended. */
private data class OlderAnchor(val index: Int, val offset: Int, val total: Int)

/**
 * Shown instead of the empty state while the first history page is in flight:
 * a blank transcript reads as "this session has no messages".
 */
@Composable
private fun HistoryLoadingState(onRetry: () -> Unit) {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(6_000)
        slow = true
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            packText("chat.loadingHistory", "正在读取历史会话…"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (slow) {
            Spacer(Modifier.height(4.dp))
            Text(
                packText("chat.loadingHistorySlow", "比平时慢，relay 可能没有响应"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
            TextButton(onClick = onRetry) {
                Text(
                    packText("chat.retryHistory", "重试"),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
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
                color = LocalUiPack.current.layout.chrome.emptyTitleColor
                    .takeIf { it.isNotBlank() }
                    ?.let { packColor(it) }
                    ?: LocalContentColor.current,
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
            val scheme = MaterialTheme.colorScheme
            val bubbleAppearance = LocalUiPack.current.layout.bubble
            val baseRadius = theme.bubbleRadius?.dp ?: (cornerRadius + 4).dp
            val inheritedBottomEnd =
                if (theme.bubbleRadius == null && cornerRadius > 0) 5.dp else baseRadius
            fun corner(value: Int, fallback: Dp): Dp =
                if (value >= 0) value.dp else fallback
            val shape = if (
                bubbleAppearance.radiusTL >= 0 || bubbleAppearance.radiusTR >= 0 ||
                bubbleAppearance.radiusBR >= 0 || bubbleAppearance.radiusBL >= 0
            ) {
                RoundedCornerShape(
                    topStart = corner(bubbleAppearance.radiusTL, baseRadius),
                    topEnd = corner(bubbleAppearance.radiusTR, baseRadius),
                    bottomEnd = corner(bubbleAppearance.radiusBR, inheritedBottomEnd),
                    bottomStart = corner(bubbleAppearance.radiusBL, baseRadius),
                )
            } else {
                theme.bubbleRadius?.let { RoundedCornerShape(it.dp) } ?: RoundedCornerShape(
                    topStart = (cornerRadius + 4).dp,
                    topEnd = (cornerRadius + 4).dp,
                    bottomEnd = if (cornerRadius == 0) 0.dp else 5.dp,
                    bottomStart = (cornerRadius + 4).dp,
                )
            }
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
                            if (bubbleAppearance.userBackground == "brand") {
                                Brush.linearGradient(
                                    listOf(
                                        (theme.bubbleStart ?: scheme.primary)
                                            .copy(alpha = theme.bubbleStartAlpha),
                                        (theme.bubbleEnd ?: scheme.tertiary)
                                            .copy(alpha = theme.bubbleEndAlpha),
                                    ),
                                )
                            } else {
                                SolidColor(
                                    packColor(bubbleAppearance.userBackground)
                                        .copy(alpha = bubbleAppearance.userBackgroundAlpha),
                                )
                            },
                            shape,
                        )
                        .then(
                            if (bubbleAppearance.borderWidth > 0) {
                                Modifier.border(
                                    bubbleAppearance.borderWidth.dp,
                                    packColor(bubbleAppearance.borderColor)
                                        .copy(alpha = theme.bubbleBorderAlpha),
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
                    Column {
                        ChatImageStrip(
                            images = message.images,
                            omitted = message.imagesOmitted,
                            cornerRadius = theme.bubbleRadius ?: cornerRadius,
                            maxHeight = 240.dp,
                        )
                        if (message.text.isNotBlank()) {
                            if (message.images.isNotEmpty()) Spacer(Modifier.height(5.dp))
                            Text(
                                message.text,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    textAlign = when (bubbleAppearance.textAlign) {
                                        "center" -> TextAlign.Center
                                        "end" -> TextAlign.End
                                        else -> TextAlign.Start
                                    },
                                ),
                                color = packColor(bubbleAppearance.textColor),
                            )
                        }
                    }
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
                    Column(Modifier.fillMaxWidth()) {
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
                        if (message.images.isNotEmpty() || message.imagesOmitted > 0) {
                            ChatImageStrip(
                                images = message.images,
                                omitted = message.imagesOmitted,
                                modifier = Modifier.padding(top = 6.dp),
                                cornerRadius = cornerRadius,
                            )
                        }
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
                                            packColor(bubbleAppearance.assistantBorderColor)
                                                .copy(alpha = bubbleAppearance.assistantBorderAlpha),
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
            // Notices are the app's own error channel: long-press to select
            // and copy instead of retyping what is on screen.
            SelectionContainer {
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
            packColor(
                if (message.isError) appearance.errorBorderColor else appearance.borderColor,
            ).copy(
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
                        .background(packColor(appearance.iconBackground).copy(alpha = appearance.iconAlpha)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        packIcon("tool", "build"),
                        contentDescription = null,
                        modifier = Modifier.size(
                            (appearance.iconSize - 1).coerceAtLeast(8).dp,
                        ),
                        tint = packColor(appearance.iconTint),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    message.toolName ?: "tool",
                    style = titleStyle,
                    color = packColor(appearance.titleColor),
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
                        .background(packColor(appearance.iconBackground).copy(alpha = appearance.iconAlpha)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        packIcon("tool", "build"),
                        contentDescription = null,
                        modifier = Modifier.size(appearance.iconSize.dp),
                        tint = packColor(appearance.iconTint),
                    )
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        message.toolName ?: "tool",
                        style = titleStyle,
                        color = packColor(appearance.titleColor),
                    )
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
            // Output images (`read` on a picture, screenshots, image models)
            // are the point of the card, so show them in every mode.
            if (message.images.isNotEmpty() || message.imagesOmitted > 0) {
                Spacer(Modifier.height(6.dp))
                ChatImageStrip(
                    images = message.images,
                    omitted = message.imagesOmitted,
                    maxHeight = 200.dp,
                    cornerRadius = cornerRadius,
                )
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
    val appearance = LocalUiPack.current.layout.toolCard
    val addedColor = packColor(appearance.diffAddColor)
    val removedColor = packColor(appearance.diffRemoveColor)
    val contextColor = packColor(appearance.diffContextColor)
    val annotated = remember(diff, addedColor, removedColor, contextColor, maxLines) {
        val lines = diff.lineSequence().take(maxLines).toList()
        val truncated = diff.lineSequence().count() > maxLines
        buildAnnotatedString {
            lines.forEachIndexed { index, line ->
                val color = when {
                    line.startsWith("+") && !line.startsWith("+++") -> addedColor
                    line.startsWith("-") && !line.startsWith("---") -> removedColor
                    else -> contextColor
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
            fontFamily = if (appearance.codeFontFamily.isNotBlank()) {
                packFontFamily(LocalUiPack.current, appearance.codeFontFamily)
            } else {
                packCodeFont()
            },
            fontSize = (if (appearance.codeFontSize > 0) appearance.codeFontSize else 11).sp,
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
    attachments: List<PendingImage>,
    layout: UiLayout,
    cornerRadius: Int,
    onSubmit: () -> Unit,
    onSteer: () -> Unit,
    onFollowUp: () -> Unit,
    onStop: () -> Unit,
    onPickImages: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val components = packComponents()
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
    val composerAlign = when (layout.composerTextAlign) {
        "center" -> TextAlign.Center
        "end" -> TextAlign.End
        else -> TextAlign.Start
    }
    val composerFamily = if (layout.composerFontFamily.isNotBlank()) {
        packFontFamily(LocalUiPack.current, layout.composerFontFamily)
    } else {
        null
    }
    fun composerTextStyle(base: TextStyle, size: Int): TextStyle {
        var style = if (size > 0) base.copy(fontSize = size.sp) else base
        if (composerFamily != null) {
            style = style.copy(fontFamily = composerFamily)
        }
        if (layout.composerLineHeight > 0f) {
            style = style.copy(lineHeight = (style.fontSize.value * layout.composerLineHeight).sp)
        }
        if (layout.composerLetterSpacing != 0f) {
            style = style.copy(letterSpacing = layout.composerLetterSpacing.sp)
        }
        return style.copy(textAlign = composerAlign)
    }
    val inputStyle = composerTextStyle(MaterialTheme.typography.bodyMedium, layout.composerFontSize)
        .copy(color = packColor(layout.composerTextColor))
    val placeholderStyle = composerTextStyle(
        MaterialTheme.typography.bodyMedium,
        layout.composerPlaceholderFontSize.takeIf { it > 0 } ?: layout.composerFontSize,
    )
    fun insetIcon(size: Dp, padding: Int): Dp =
        if (padding <= 0) size else (size.value - padding * 2).coerceAtLeast(6f).dp
    fun disc(kind: String, size: Dp, radius: Int): Shape =
        if (kind == "circle") {
            CircleShape
        } else {
            RoundedCornerShape((if (radius >= 0) radius.toFloat() else size.value / 2.4f).dp)
        }
    val attachSize = (layout.composerAttachSize.takeIf { it > 0 } ?: components.composerButtonSize).dp
    val sendSize = (layout.composerSendSize.takeIf { it > 0 } ?: components.composerButtonSize).dp
    val stopSize = (layout.composerStopSize.takeIf { it > 0 } ?: components.composerButtonSize).dp
    val attachShape = disc(layout.composerAttachShape, attachSize, layout.composerAttachRadius)
    val sendShape = disc(layout.composerSendShape, sendSize, layout.composerSendRadius)
    val stopShape = disc(layout.composerStopShape, stopSize, layout.composerStopRadius)
    val sendDisabledBackground = packColor(layout.composerSendDisabledBackground)
    val sendDisabledTint = packColor(layout.composerSendDisabledTint)
    val selectionColors = TextSelectionColors(
        handleColor = packColor(layout.composerSelectionHandleColor),
        backgroundColor = packColor(layout.composerSelectionColor)
            .copy(alpha = layout.composerSelectionAlpha),
    )
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fieldShape = RoundedCornerShape(layout.composerInputRadius.dp)
    val fieldBorder = if (focused) {
        scheme.primary.copy(alpha = (layout.composerInputBorderAlpha + 0.2f).coerceAtMost(1f))
    } else {
        scheme.outline.copy(alpha = layout.composerInputBorderAlpha)
    }
    val innerH = (layout.composerInnerPaddingH.takeIf { it >= 0 } ?: 16).dp
    val innerV = (layout.composerInnerPaddingV.takeIf { it >= 0 } ?: 16).dp
    val buttonIconSize = (layout.composerButtonIconSize.takeIf { it > 0 } ?: 21).dp
    val attachIconSize = layout.composerAttachIconSize.takeIf { it > 0 }?.dp ?: buttonIconSize
    val attachTint = packColor(layout.composerAttachTint)
    val attachBackground = packColor(layout.composerAttachBackground)
        .copy(alpha = layout.composerAttachBackgroundAlpha)
    val inputSurface = packColor(layout.composerInputSurface)
    val cursorColor = packColor(layout.composerCursorColor)
    val sendTint = packColor(layout.composerSendTint)
    val sendBackground = packColor(layout.composerSendBackground)
    val stopTint = packColor(layout.composerStopTint)
    val stopBackground = packColor(layout.composerStopBackground)
        .copy(alpha = layout.composerStopBackgroundAlpha)
    val placeholderColor = when (layout.composerPlaceholderColor) {
        "primary" -> scheme.primary
        "secondary" -> scheme.secondary
        "tertiary" -> scheme.tertiary
        "text" -> scheme.onSurface
        "error" -> scheme.error
        else -> scheme.onSurfaceVariant
    }
    val canSend = connected && (value.isNotBlank() || attachments.isNotEmpty())
    val shape = RoundedCornerShape(
        topStart = composerRadius.dp,
        topEnd = composerRadius.dp,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = layout.composerMarginH.dp,
                vertical = layout.composerMarginV.dp,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
    Surface(
        color = scheme.surface.copy(alpha = layout.composerSurfaceAlpha),
        shape = shape,
        shadowElevation = composerElevation.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (layout.composerMaxWidth > 0) {
                    Modifier.widthIn(max = layout.composerMaxWidth.dp)
                } else {
                    Modifier
                },
            ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = layout.composerPaddingH.dp,
                    vertical = layout.composerPaddingV.dp,
                ),
        ) {
            if (attachments.isNotEmpty()) {
                AttachmentStrip(
                    images = attachments,
                    onRemove = onRemoveAttachment,
                    thumbSize = layout.composerThumbSize.dp,
                    thumbRadius = layout.composerThumbRadius.dp,
                    gap = layout.composerThumbGap.dp,
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box(
                    Modifier
                        .size(attachSize)
                        .clip(attachShape)
                        .background(attachBackground)
                        .clickable(enabled = connected) { onPickImages() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        packIcon("image", "image"),
                        contentDescription = packText("a11y.attachImage", "添加图片"),
                        modifier = Modifier.size(
                            insetIcon(attachIconSize, layout.composerAttachPadding),
                        ),
                        tint = attachTint,
                    )
                }
                Spacer(Modifier.width(layout.composerAttachGap.dp))
                CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (layout.composerMinHeight > 0) {
                                    Modifier.heightIn(min = layout.composerMinHeight.dp)
                                } else {
                                    Modifier
                                },
                            )
                            .then(
                                if (layout.composerMaxHeight > 0) {
                                    Modifier.heightIn(max = layout.composerMaxHeight.dp)
                                } else {
                                    Modifier
                                },
                            )
                            .clip(fieldShape)
                            .background(inputSurface.copy(alpha = layout.composerInputAlpha))
                            .border(1.dp, fieldBorder, fieldShape)
                            .padding(horizontal = innerH, vertical = innerV),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                if (busy) {
                                    packText("composer.steerPlaceholder", "插队指令…")
                                } else {
                                    layout.composerPlaceholder
                                },
                                style = placeholderStyle,
                                color = placeholderColor.copy(
                                    alpha = layout.composerPlaceholderAlpha,
                                ),
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = inputStyle,
                            cursorBrush = SolidColor(cursorColor),
                            maxLines = layout.composerMaxLines,
                            interactionSource = interaction,
                            keyboardOptions = KeyboardOptions(
                                imeAction = if (layout.sendOnEnter) ImeAction.Send else ImeAction.Default,
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = { if (layout.sendOnEnter) onSubmit() },
                            ),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (busy) {
                    Box(
                        Modifier
                            .size(stopSize)
                            .clip(stopShape)
                            .background(stopBackground)
                            .then(
                                if (layout.composerStopBorderWidth > 0) {
                                    Modifier.border(
                                        layout.composerStopBorderWidth.dp,
                                        packColor(layout.composerStopBorderColor),
                                        stopShape,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { onStop() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            packIcon("stop", "stop"),
                            contentDescription = packText("a11y.stop", "停止"),
                            modifier = Modifier.size(
                                insetIcon(buttonIconSize, layout.composerStopPadding),
                            ),
                            tint = stopTint,
                        )
                    }
                    if (canSend) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .size(sendSize)
                                .shadow(
                                    layout.composerSendGlow.dp,
                                    sendShape,
                                    ambientColor = scheme.primary,
                                    spotColor = scheme.primary,
                                )
                                .clip(sendShape)
                                .then(
                                    if (layout.composerSendStyle == "solid") {
                                        Modifier.background(sendBackground)
                                    } else {
                                        Modifier.background(brandBrush())
                                    },
                                )
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
                                modifier = Modifier.size(
                                    insetIcon(buttonIconSize, layout.composerSendPadding),
                                ),
                                tint = sendTint,
                            )
                        }
                    }
                } else {
                    Box(
                        Modifier
                            .size(sendSize)
                            .then(
                                if (canSend && layout.composerSendGlow > 0) {
                                    Modifier.shadow(
                                        layout.composerSendGlow.dp,
                                        sendShape,
                                        ambientColor = scheme.primary,
                                        spotColor = scheme.primary,
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .clip(sendShape)
                            .background(
                                when {
                                    !canSend -> SolidColor(sendDisabledBackground)
                                    layout.composerSendStyle == "solid" -> SolidColor(sendBackground)
                                    else -> brandBrush()
                                },
                            )
                            .clickable(enabled = canSend) { onSubmit() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            packIcon("send", "send"),
                            contentDescription = packText("a11y.send", "发送"),
                            modifier = Modifier.size(
                                insetIcon(buttonIconSize, layout.composerSendPadding),
                            ),
                            tint = if (canSend) sendTint else sendDisabledTint,
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

/**
 * Extension dialogs (`confirm` / `select` / `input` / `editor`) such as the
 * permission-system prompt. The body is selectable and copyable because it is
 * often command-line help. Tapping the chat (the dialog scrim) only hides the
 * dialog: the request stays pending and the banner above the composer brings
 * it back, so a stray tap cannot deny a tool call.
 */
@Composable
private fun PendingDialog(request: UiRequest, vm: AppViewModel) {
    var value by remember(request.id) { mutableStateOf(request.prefill) }
    val scheme = MaterialTheme.colorScheme
    val theme = packTheme()
    val toolCard = LocalUiPack.current.layout.toolCard
    val shape = RoundedCornerShape(theme.cornerRadius.dp)
    val scroll = rememberScrollState()
    val copyText = remember(request.title, request.message, request.options) {
        buildString {
            append(request.title.ifBlank { request.method })
            request.message?.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
            request.options.forEachIndexed { index, option ->
                append("\n").append(index + 1).append(". ").append(option)
            }
        }
    }

    Dialog(
        onDismissRequest = vm::hidePending,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            shape = shape,
            color = scheme.surface,
            border = BorderStroke(1.dp, scheme.outline.copy(alpha = 0.35f)),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .padding(18.dp)
                    .heightIn(max = 560.dp)
                    .verticalScroll(scroll),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(toolCard.iconBoxSize.dp)
                            .clip(RoundedCornerShape(toolCard.iconRadius.dp))
                            .background(scheme.secondary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            packIcon("warning", "warning"),
                            contentDescription = null,
                            modifier = Modifier.size(toolCard.iconSize.dp),
                            tint = scheme.secondary,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        request.title.ifBlank {
                            when (request.method) {
                                "select" -> packText("dialog.selectTitle", "请选择")
                                "input", "editor" -> packText("dialog.inputTitle", "请输入")
                                else -> packText("dialog.confirmTitle", "确认操作")
                            }
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = packColor("onSurface"),
                        modifier = Modifier.weight(1f),
                    )
                    CopyButton { vm.copyText(copyText) }
                }
                request.message?.takeIf { it.isNotBlank() }?.let { message ->
                    Spacer(Modifier.height(10.dp))
                    SelectionContainer {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = if (looksLikeCommandHelp(message)) {
                                    packCodeFont()
                                } else {
                                    FontFamily.Default
                                },
                            ),
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(scheme.surfaceVariant.copy(alpha = 0.55f))
                                .padding(10.dp),
                        )
                    }
                }
                when (request.method) {
                    "select" -> request.options.forEach { option ->
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { vm.answer(value = option) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(option)
                        }
                    }
                    "input" -> {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(request.placeholder ?: "") },
                        )
                    }
                    "editor" -> {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp),
                            maxLines = 12,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (request.method) {
                        "select" -> TextButton(onClick = { vm.answer(cancelled = true) }) {
                            Text(packText("dialog.cancel", "取消"))
                        }
                        "confirm" -> {
                            TextButton(onClick = { vm.answer(confirmed = false) }) {
                                Text(
                                    packText("dialog.deny", "拒绝"),
                                    color = scheme.error,
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = { vm.answer(confirmed = true) }) {
                                Text(packText("dialog.allow", "允许"))
                            }
                        }
                        else -> {
                            TextButton(onClick = { vm.answer(cancelled = true) }) {
                                Text(packText("dialog.cancel", "取消"))
                            }
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = { vm.answer(value = value) }) {
                                Text(packText("dialog.ok", "确定"))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Heuristic: show command lines / help in the code font. */
private fun looksLikeCommandHelp(text: String): Boolean {
    val lines = text.lineSequence().filter { it.isNotBlank() }.take(8).toList()
    if (lines.isEmpty()) return false
    val shellish = lines.count { line ->
        line.startsWith("$") || line.startsWith("#") || line.startsWith(">") ||
            line.contains(" --") || line.contains("Usage:") || line.contains("用法:") ||
            line.startsWith("/") || line.startsWith("- ")
    }
    return shellish * 2 >= lines.size
}

/** The pending request is still waiting after the user tapped the chat. */
@Composable
private fun HiddenRequestBanner(request: UiRequest, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.secondary.copy(alpha = 0.14f))
            .clickable { onOpen() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            packIcon("warning", "warning"),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = scheme.secondary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            packText("dialog.pendingHidden", "有待确认的请求")
                .replace("{title}", request.title.ifBlank { request.method }),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.secondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            packText("dialog.pendingOpen", "查看"),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.primary,
        )
    }
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
        "starting" -> packText("status.starting", "正在新建对话…")
        "restoring" -> packText("status.restoring", "恢复中")
        else -> status
    }.trim()
    // Extension lines (⏱ rate, 🔒 permission mode, ...) outlive the turn they
    // were written in. They are labels, not activity: no spinner once the agent
    // has stopped, otherwise the ring keeps turning forever.
    val extras = extensionStatuses.values.filter { it.isNotBlank() && it != activity }
    val label = (listOf(activity) + extras).filter { it.isNotBlank() }.joinToString(" · ")
    val chrome = LocalUiPack.current.layout.chrome
    val pillColor = packColor(chrome.statusBackground)
    Row(
        Modifier
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(CircleShape)
            .background(pillColor.copy(alpha = chrome.statusBackgroundAlpha))
            .padding(
                horizontal = chrome.statusPaddingH.dp,
                vertical = chrome.statusPaddingV.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (activity.isNotBlank()) {
            CircularProgressIndicator(
                modifier = Modifier.size(
                    (if (chrome.statusSpinnerSize > 0) chrome.statusSpinnerSize else 11).dp,
                ),
                strokeWidth = 1.5.dp,
                color = packColor(chrome.statusSpinnerColor),
            )
            Spacer(Modifier.width(9.dp))
        }
        Text(
            label,
            style = if (chrome.statusSize > 0) {
                MaterialTheme.typography.labelSmall.copy(fontSize = chrome.statusSize.sp)
            } else {
                MaterialTheme.typography.labelSmall
            },
            color = packColor(chrome.statusColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val copied = packText("toast.copied", "已复制")
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
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Pig error", message))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    packIcon("copy", "copy"),
                    contentDescription = packText("a11y.copy", "复制"),
                    modifier = Modifier.size(15.dp),
                    tint = scheme.onErrorContainer,
                )
            }
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
