package com.linuxwff.pigapp

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("pig", Context.MODE_PRIVATE)

    val port: Int = prefs.getInt("port", 8791)
    val token: String = prefs.getString("token", null) ?: newToken().also {
        prefs.edit().putString("token", it).apply()
    }
    val cwd: String = prefs.getString("cwd", null) ?: "/data/data/com.termux/files/home"

    private val client = RelayClient("127.0.0.1", port, token)

    var ui by mutableStateOf(AppUiState())
        private set

    private val windowPositions = mutableStateMapOf<String, WindowPos>()
    private val dismissedWindows = mutableMapOf<String, String>() // pack path -> window id
    private val knownWindows = mutableMapOf<String, AppWindow>() // window id -> last parsed definition
    private var packAssets: Map<String, String> = emptyMap()
    private var packAssetHashes: Map<String, String> = emptyMap()
    private val imageCache = mutableStateMapOf<String, ImageBitmap>()
    private val imageLoading = mutableSetOf<String>()
    private val formValues = mutableStateMapOf<String, String>() // "windowId/fieldId" -> value

    var models by mutableStateOf<List<ModelInfo>>(emptyList())
        private set
    var modelsLoading by mutableStateOf(false)
        private set
    var showModelPicker by mutableStateOf(false)
        private set

    private var nextId = 1
    private var currentAssistant: String? = null
    private val toolMsgIds = mutableMapOf<String, String>()

    private var foreground = false
    private var autoReconnectJob: kotlinx.coroutines.Job? = null
    private var lastPongAt = 0L
    private var desiredSessionPath: String? = null
    private var restorePending = false
    private var restoreChecked = false
    private var liveText: String? = null
    private val pendingToolPartials = mutableMapOf<String, String>()

    init {
        current = this
        viewModelScope.launch {
            client.connected.collect { connected ->
                ui = ui.copy(connected = connected)
                if (connected) lastPongAt = System.currentTimeMillis()
            }
        }
        viewModelScope.launch {
            client.events.collect { event ->
                handle(event)
            }
        }
        // Loopback TCP has no keepalive: after the phone sleeps or the process
        // is frozen the socket can look alive while the relay is gone. Ping
        // periodically and drop the connection when the pongs stop.
        viewModelScope.launch {
            while (true) {
                delay(4_000)
                if (!client.connected.value) continue
                val now = System.currentTimeMillis()
                if (lastPongAt > 0 && now - lastPongAt > 12_000) {
                    lastPongAt = 0
                    client.close()
                    if (foreground) startAutoReconnect()
                    continue
                }
                client.send(JSONObject().put("type", "relay_ping").put("id", "ping-${nextId++}"))
            }
        }
    }

    fun connect() {
        val ctx = getApplication<Application>()
        if (!RelayLauncher.hasPermission(ctx)) {
            ui = ui.copy(
                error = "没有 Termux RUN_COMMAND 权限。请在系统设置里允许本应用调用 Termux，然后点重连。",
            )
            return
        }
        val launchError = RelayLauncher.launch(ctx, port, token, cwd)
        if (launchError != null) {
            ui = ui.copy(
                error = "无法启动 pig-relay：$launchError",
                setup = true,
                setupHint = launchError,
            )
            return
        }
        ui = ui.copy(error = null)
        client.close()
        client.connect()
        viewModelScope.launch {
            // A leftover relay from an older install may still hold the port
            // and reject our token; it exits on the bad auth. Retry the launch
            // so the fresh relay can bind.
            repeat(3) {
                delay(1500)
                if (!client.connected.value) {
                    RelayLauncher.launch(ctx, port, token, cwd)
                }
            }
        }
    }

    /** Called from Activity.onResume: resume the relay/session after a lock or process freeze. */
    fun onForeground() {
        foreground = true
        val ctx = getApplication<Application>()
        if (prefs.getBoolean("keepalive", true)) {
            KeepAliveService.start(ctx)
        } else {
            KeepAliveService.stop(ctx)
        }
        if (!RelayLauncher.hasPermission(ctx)) {
            ui = ui.copy(
                error = "没有 Termux RUN_COMMAND 权限。请在系统设置里允许本应用调用 Termux，然后点重连。",
            )
            return
        }
        if (client.connected.value) {
            lastPongAt = System.currentTimeMillis()
            client.send(JSONObject().put("type", "relay_ping").put("id", "ping-${nextId++}"))
            return
        }
        startAutoReconnect()
    }

    /** Called from Activity.onPause: stop retrying while the app is in the background. */
    fun onBackground() {
        foreground = false
        autoReconnectJob?.cancel()
        autoReconnectJob = null
    }

    private fun startAutoReconnect() {
        if (autoReconnectJob?.isActive == true) return
        autoReconnectJob = viewModelScope.launch {
            var nextLaunch = 0L
            while (foreground && !client.connected.value) {
                val ctx = getApplication<Application>()
                if (!RelayLauncher.hasPermission(ctx)) break
                if (!client.isConnecting) connect()
                val now = System.currentTimeMillis()
                if (now >= nextLaunch) {
                    // The relay may have been killed with the app process; keep
                    // asking Termux to run it until the socket comes back.
                    RelayLauncher.launch(ctx, port, token, cwd)
                    nextLaunch = now + 8_000
                }
                delay(1_000)
            }
        }
    }

    private fun savedSession(): String? =
        prefs.getString("lastSessionFile", null)?.takeIf { it.isNotBlank() }

    private fun rememberSession(path: String) {
        if (path.isBlank()) return
        if (savedSession() != path) prefs.edit().putString("lastSessionFile", path).apply()
        markVisited(path)
    }

    private fun markVisited(path: String) {
        if (path.isBlank()) return
        val current = prefs.getStringSet("visitedSessions", emptySet()) ?: emptySet()
        if (path in current) return
        prefs.edit().putStringSet("visitedSessions", current + path).apply()
    }

    fun reconnect() {
        client.close()
        connect()
    }

    fun bootstrap() {
        val error = RelayLauncher.bootstrap(getApplication(), cwd)
        ui = ui.copy(
            setup = error == null,
            setupHint = error,
            toast = error ?: "已在 Termux 里开始安装，完成后点「重试」",
        )
    }

    fun dismissSetup() {
        ui = ui.copy(setup = false)
    }

    fun openTermux() {
        if (!RelayLauncher.openTermux(getApplication())) {
            RelayLauncher.openTermuxInstallPage(getApplication())
        }
    }

    fun openTermuxInstallPage() {
        RelayLauncher.openTermuxInstallPage(getApplication())
    }

    fun openAppSettings() {
        RelayLauncher.openAppSettings(getApplication())
    }

    /** Long-press on a tool chip: apply that mode to every tool card. */
    fun setToolMode(mode: String) {
        if (mode !in setOf("collapsed", "summary", "expanded")) return
        val label = when (mode) {
            "collapsed" -> "收起"
            "expanded" -> "展开"
            else -> "摘要"
        }
        ui = ui.copy(toolMode = mode, toast = "已应用到全部工具：$label")
    }

    fun copyText(text: String) {
        if (text.isBlank()) return
        val manager = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("Pig", text))
        ui = ui.copy(toast = "已复制")
    }

    fun setKeepAlive(enabled: Boolean) {
        prefs.edit().putBoolean("keepalive", enabled).apply()
        if (enabled) {
            KeepAliveService.start(getApplication())
        } else {
            KeepAliveService.stop(getApplication())
        }
        ui = ui.copy(
            keepAlive = enabled,
            toast = if (enabled) "后台保活已开启" else "后台保活已关闭",
        )
    }

    fun setWakeLock(enabled: Boolean) {
        prefs.edit().putBoolean("wakelock", enabled).apply()
        KeepAliveService.setWakeLock(getApplication(), enabled)
        ui = ui.copy(
            wakeLock = enabled,
            toast = if (enabled) "唤醒锁已开启（锁屏后 CPU 不休眠）" else "唤醒锁已关闭",
        )
    }

    fun openBatterySettings() {
        val ctx = getApplication<Application>()
        if (RelayLauncher.isIgnoringBatteryOptimizations(ctx)) {
            ui = ui.copy(toast = "已在电池优化白名单里")
            return
        }
        ui = ui.copy(
            toast = if (RelayLauncher.requestIgnoreBatteryOptimizations(ctx)) {
                "请在系统弹窗里选择「允许」"
            } else {
                "无法直接请求，请在系统设置 → 电池里手动允许"
            },
        )
    }

    fun sendPrompt(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        append(ChatMsg(id = "u${nextId++}", role = Role.USER, text = text))
        currentAssistant = null
        markVisited(ui.sessionFile)
        ui = ui.copy(readOnly = false)
        val command = JSONObject()
            .put("id", "p${nextId++}")
            .put("type", "prompt")
            .put("message", text)
        if (ui.busy) command.put("streamingBehavior", "followUp")
        client.send(command)
    }

    fun abort() = client.send(JSONObject().put("type", "abort"))

    fun openModelPicker() {
        showModelPicker = true
        requestModels()
    }

    fun closeModelPicker() {
        showModelPicker = false
    }

    fun requestModels() {
        modelsLoading = true
        client.send(JSONObject().put("id", "models-${nextId++}").put("type", "get_available_models"))
        client.send(
            JSONObject().put("id", "levels-${nextId++}").put("type", "get_available_thinking_levels"),
        )
    }

    fun selectModel(model: ModelInfo) {
        showModelPicker = false
        client.send(
            JSONObject()
                .put("id", "setmodel-${nextId++}")
                .put("type", "set_model")
                .put("provider", model.provider)
                .put("modelId", model.id),
        )
    }

    fun selectModelById(provider: String, id: String) {
        val known = models.firstOrNull { it.provider == provider && it.id == id }
        selectModel(known ?: ModelInfo(provider, id, id, 0, 0, false, false))
    }

    fun setThinkingLevel(level: String) {
        if (level.isBlank()) return
        client.send(
            JSONObject()
                .put("id", "think-${nextId++}")
                .put("type", "set_thinking_level")
                .put("level", level),
        )
    }

    fun requestSessions() {
        ui = ui.copy(sessionsLoading = true)
        client.send(JSONObject().put("id", "sessions-${nextId++}").put("type", "relay_sessions"))
    }

    /** Ask pig for the current context usage; refreshed on connect, session switch and after each turn. */
    private fun requestStats() {
        client.send(JSONObject().put("id", "stats-${nextId++}").put("type", "get_session_stats"))
    }

    fun switchSession(path: String) {
        if (path == ui.sessionFile) {
            ui = ui.copy(toast = "已经是当前会话")
            return
        }
        doSwitch(path)
    }

    fun confirmSwitch() {
        val session = ui.pendingSwitch ?: return
        ui = ui.copy(pendingSwitch = null)
        doSwitch(session.path)
    }

    fun cancelSwitch() {
        ui = ui.copy(pendingSwitch = null)
    }

    private fun doSwitch(path: String) {
        if (path == ui.sessionFile) return
        markVisited(path)
        desiredSessionPath = path
        restorePending = false
        ui = ui.copy(status = "switching")
        // The relay parks the current pig and attaches/reads the target
        // session, so switching during a running turn is safe.
        client.send(
            JSONObject()
                .put("id", "switch-${nextId++}")
                .put("type", "switch_session")
                .put("sessionPath", path),
        )
    }

    fun newSession() {
        currentAssistant = null
        ui = ui.copy(readOnly = false)
        client.send(JSONObject().put("type", "new_session"))
    }

    /** Rename a session: the live pig renames the current one, the relay appends
     *  a session_info entry for any other file. */
    fun renameSession(session: SessionInfo, name: String) {
        val clean = name.trim().replace(Regex("[\\r\\n]+"), " ").take(80)
        val current = session.path == ui.sessionFile
        if (current && client.connected.value) {
            client.send(
                JSONObject()
                    .put("id", "rename-${nextId++}")
                    .put("type", "set_session_name")
                    .put("name", clean),
            )
        } else {
            client.send(
                JSONObject()
                    .put("id", "rename-${nextId++}")
                    .put("type", "relay_session_rename")
                    .put("path", session.path)
                    .put("name", clean),
            )
        }
        ui = ui.copy(sessions = ui.sessions.map { if (it.path == session.path) it.copy(name = clean) else it })
    }

    fun deleteSession(session: SessionInfo) {
        if (session.path == ui.sessionFile) {
            ui = ui.copy(toast = "当前会话不能删除，先切换或新建会话")
            return
        }
        client.send(
            JSONObject()
                .put("id", "delete-${nextId++}")
                .put("type", "relay_session_delete")
                .put("path", session.path),
        )
    }

    fun clearLocal() {
        ui = ui.copy(messages = emptyList())
    }

    fun injectWindow(text: String) {
        val result = AppUiParser.parseWindow(text)
        val window = result.window
        if (window == null) {
            ui = ui.copy(windowError = result.warnings.firstOrNull() ?: "窗口无效")
            return
        }
        val existing = ui.windows.any { it.id == window.id }
        val windows = if (existing) {
            ui.windows.map { if (it.id == window.id) window else it }
        } else {
            ui.windows + window
        }
        ui = ui.copy(windows = windows, windowError = result.warnings.firstOrNull())
    }

    fun closeWindow(id: String) {
        val window = ui.windows.firstOrNull { it.id == id }
        if (window != null && window.source != "debug") {
            dismissedWindows[window.source] = window.id
        }
        windowPositions.remove(id)
        ui = ui.copy(windows = ui.windows.filterNot { it.id == id })
    }

    fun closeTopWindow() {
        ui.windows.lastOrNull()?.let { closeWindow(it.id) }
    }

    fun focusWindow(id: String) {
        val window = ui.windows.firstOrNull { it.id == id } ?: return
        if (ui.windows.lastOrNull()?.id == id) return
        ui = ui.copy(windows = ui.windows.filterNot { it.id == id } + window)
    }

    fun positionOf(id: String): WindowPos {
        windowPositions[id]?.let { return it }
        val index = ui.windows.indexOfFirst { it.id == id }.coerceAtLeast(0)
        return WindowPos(16f + (index % 5) * 14f, 96f + (index % 5) * 18f)
    }

    fun moveWindow(id: String, dx: Float, dy: Float, maxX: Float, maxY: Float) {
        val current = positionOf(id)
        windowPositions[id] = WindowPos(
            (current.x + dx).coerceIn(0f, maxX),
            (current.y + dy).coerceIn(0f, maxY),
        )
    }

    fun handleWindowAction(windowId: String, action: String) {
        when {
            action == "builtin:close" -> closeWindow(windowId)
            action.startsWith("builtin:copy:") -> {
                val value = action.removePrefix("builtin:copy:")
                val clipboard = getApplication<Application>()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Pig", value))
                ui = ui.copy(toast = "已复制")
            }
            action.startsWith("ext:") -> {
                val command = action.removePrefix("ext:").trim()
                if (command.isEmpty()) {
                    ui = ui.copy(toast = "ext 动作缺少命令名")
                    return
                }
                val payload = formPayload(windowId)
                val message = if (payload == null) "/$command" else "/$command $payload"
                client.send(
                    JSONObject()
                        .put("id", "act-${nextId++}")
                        .put("type", "prompt")
                        .put("message", message),
                )
                ui = ui.copy(toast = "已发送 /$command")
            }
            action.isBlank() -> Unit
            else -> ui = ui.copy(toast = "未知动作: $action")
        }
    }

    /**
     * Opens a drawer entry's window. The last parsed definition is cached, so a
     * window the user closed can be reopened without waiting for a pack push;
     * the command is still sent to refresh its contents from the extension.
     */
    /** Opens a cached pack window by id, regardless of autoOpen/nav. */
    fun openWindowById(id: String) {
        if (id.isBlank()) return
        dismissedWindows.entries.removeAll { it.value == id }
        val existing = ui.windows.firstOrNull { it.id == id }
        if (existing != null) {
            focusWindow(id)
            return
        }
        val cached = knownWindows[id] ?: run {
            ui = ui.copy(toast = "还没有窗口 $id")
            return
        }
        ui = ui.copy(windows = ui.windows + cached)
    }

    fun openNavItem(item: NavItem) {
        dismissedWindows.entries.removeAll { it.value == item.window }
        if (ui.windows.none { it.id == item.window }) {
            knownWindows[item.window]?.let { cached ->
                windowPositions.remove(item.window)
                ui = ui.copy(windows = ui.windows + cached)
            }
        }
        if (item.command.isNotBlank()) {
            if (!ui.connected) {
                ui = ui.copy(toast = "未连接 relay，无法刷新 ${item.label}")
                return
            }
            client.send(
                JSONObject()
                    .put("id", "nav-${nextId++}")
                    .put("type", "prompt")
                    .put("message", "/${item.command}"),
            )
            ui = ui.copy(toast = "已打开 ${item.label}")
        } else if (knownWindows[item.window] == null) {
            ui = ui.copy(toast = "还没有 ${item.label} 窗口")
        }
    }

    fun dismissError() {
        ui = ui.copy(error = null)
    }

    fun consumeToast() {
        ui = ui.copy(toast = null)
    }

    fun answer(value: String? = null, confirmed: Boolean? = null, cancelled: Boolean = false) {
        val pending = ui.pending ?: return
        val response = JSONObject().put("type", "extension_ui_response").put("id", pending.id)
        when {
            cancelled -> response.put("cancelled", true)
            confirmed != null -> response.put("confirmed", confirmed)
            else -> response.put("value", value ?: "")
        }
        client.send(response)
        ui = ui.copy(pending = null)
    }

    fun handleDebugEvent(text: String) {
        val event = runCatching { JSONObject(text) }.getOrNull()
        if (event == null) {
            ui = ui.copy(toast = "debug event 不是 JSON")
            return
        }
        handle(event)
    }

    private fun modelInfo(json: JSONObject): ModelInfo {
        val input = json.optJSONArray("input")
        val images = input != null && (0 until input.length()).any { input.optString(it) == "image" }
        return ModelInfo(
            provider = json.optString("provider"),
            id = json.optString("id"),
            name = json.optString("name", json.optString("id")),
            contextWindow = json.optInt("contextWindow", 0),
            maxTokens = json.optInt("maxTokens", 0),
            reasoning = json.optBoolean("reasoning", false),
            images = images,
        )
    }

    private fun handleWidget(event: JSONObject) {
        val key = event.optString("widgetKey", "widget").ifBlank { "widget" }
        val lines = event.optJSONArray("widgetLines")
        val placement = if (event.optString("widgetPlacement", "aboveEditor") == "belowEditor") {
            "belowEditor"
        } else {
            "aboveEditor"
        }
        val next = ui.widgets.filterNot { it.key == key }.toMutableList()
        if (lines != null) {
            next.add(
                AppWidget(
                    key = key,
                    lines = (0 until lines.length()).map { lines.optString(it) },
                    placement = placement,
                ),
            )
        }
        ui = ui.copy(widgets = next)
    }

    fun assetHash(path: String): String = packAssetHashes[path] ?: ""

    fun cachedImage(path: String, hash: String): ImageBitmap? = imageCache["$path@$hash"]

    fun loadImage(path: String, hash: String) {
        if (path.isBlank()) return
        val key = "$path@$hash"
        if (imageCache.containsKey(key) || !imageLoading.add(key)) return
        val encoded = packAssets[path]
        if (encoded == null) {
            imageLoading.remove(key)
            return
        }
        viewModelScope.launch {
            val image = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
            imageLoading.remove(key)
            if (image != null) imageCache[key] = image
        }
    }

    fun formValue(windowId: String, fieldId: String, fallback: String): String =
        formValues["$windowId/$fieldId"] ?: fallback

    fun setFormValue(windowId: String, fieldId: String, value: String) {
        if (fieldId.isBlank()) return
        formValues["$windowId/$fieldId"] = value
    }

    fun formPayload(windowId: String): String? {
        val window = ui.windows.firstOrNull { it.id == windowId } ?: return null
        val fields = mutableListOf<UiNode>()
        collectFields(window.body, fields)
        if (fields.isEmpty()) return null
        val values = JSONObject()
        fields.forEach { node ->
            val stored = formValues["$windowId/${node.fieldId}"]
            val value = stored ?: when (node.type) {
                "toggle" -> node.checked.toString()
                else -> node.initialValue
            }
            values.put(node.fieldId, value)
        }
        return values.toString()
    }

    private fun collectFields(node: UiNode, out: MutableList<UiNode>) {
        if (node.type == "textfield" || node.type == "select" || node.type == "toggle") out.add(node)
        node.children.forEach { collectFields(it, out) }
    }

    private fun append(message: ChatMsg) {
        ui = ui.copy(messages = ui.messages + message)
    }

    private fun update(id: String, block: (ChatMsg) -> ChatMsg) {
        ui = ui.copy(messages = ui.messages.map { if (it.id == id) block(it) else it })
    }

    private fun remove(id: String) {
        ui = ui.copy(messages = ui.messages.filterNot { it.id == id })
    }

    private fun handle(event: JSONObject) {
        when (val type = event.optString("type")) {
            "relay_hello" -> {
                ui = ui.copy(error = null, setup = false, setupHint = null)
                lastPongAt = System.currentTimeMillis()
                restoreChecked = false
                restorePending = false
                desiredSessionPath = null
                liveText = null
                pendingToolPartials.clear()
                // The relay may have kept the previous pig alive (mid-turn
                // reconnect). Ask it what it is doing before switching sessions.
                client.send(JSONObject().put("id", "state-${nextId++}").put("type", "get_state"))
                client.send(JSONObject().put("id", "msgs-${nextId++}").put("type", "get_messages"))
                requestStats()
                requestSessions()
                client.send(JSONObject().put("type", "relay_ui"))
            }
            "relay_pong" -> lastPongAt = System.currentTimeMillis()
            "relay_resume" -> handleResumeText(event.optString("text", ""))
            "relay_tool" -> handleRelayTool(event)
            "relay_client_error" -> {
                val message = event.optString("message")
                ui = ui.copy(error = message, setup = true, setupHint = message)
            }
            "relay_error" -> {
                val message = event.optString("message", "relay error")
                ui = ui.copy(error = message, setup = true, setupHint = message)
            }
            "relay_exit" -> {
                ui = ui.copy(
                    busy = false,
                    status = "",
                    error = "pig 已退出（code ${event.optInt("code")}），正在重连…",
                )
                lastPongAt = 0
                liveText = null
                pendingToolPartials.clear()
                client.close()
                if (foreground) startAutoReconnect()
            }
            "relay_ui_pack" -> handleUiPack(event)
            "relay_sessions" -> handleSessions(event)
            "relay_session_renamed" -> {
                val path = event.optString("path")
                val name = event.optString("name")
                ui = ui.copy(
                    sessions = ui.sessions.map { if (it.path == path) it.copy(name = name) else it },
                    toast = "会话已重命名",
                )
                requestSessions()
            }
            "relay_session_deleted" -> {
                val path = event.optString("path")
                if (savedSession() == path) prefs.edit().remove("lastSessionFile").apply()
                ui = ui.copy(
                    sessions = ui.sessions.filterNot { it.path == path },
                    toast = "会话已删除",
                )
                requestSessions()
            }
            "relay_session_error" ->
                ui = ui.copy(toast = event.optString("message", "会话操作失败"))
            "response" -> handleResponse(event)
            "agent_start" -> ui = ui.copy(busy = true, status = "thinking")
            "turn_start" -> ui = ui.copy(status = "working")
            "agent_end", "agent_settled" -> {
                ui = ui.copy(busy = false, status = "")
                liveText = null
                pendingToolPartials.clear()
                requestStats()
            }
            "message_start" -> handleMessageStart(event.optJSONObject("message"))
            "message_update" -> handleMessageUpdate(event)
            "message_end" -> handleMessageEnd(event.optJSONObject("message"))
            "tool_execution_start" -> handleToolStart(event)
            "tool_execution_update" -> handleToolUpdate(event)
            "tool_execution_end" -> handleToolEnd(event)
            "extension_ui_request" -> handleUiRequest(event)
            "compaction_start" -> ui = ui.copy(status = "compacting")
            "compaction_end" -> {
                ui = ui.copy(status = "")
                requestStats()
            }
            "error" -> {
                val message = event.optString("message", event.optString("error", "unknown error"))
                append(ChatMsg(id = "n${nextId++}", role = Role.NOTICE, text = message, isError = true))
                ui = ui.copy(busy = false, status = "")
            }
            else -> if (type.startsWith("auto_retry")) ui = ui.copy(status = "retrying")
        }
    }

    private fun handleResponse(event: JSONObject) {
        if (!event.optBoolean("success", true)) {
            val command = event.optString("command")
            if (command == "switch_session" && restorePending) {
                // The remembered session was deleted; fall back to a fresh one.
                restorePending = false
                desiredSessionPath = null
                prefs.edit().remove("lastSessionFile").apply()
                ui = ui.copy(status = "")
                client.send(JSONObject().put("id", "state-${nextId++}").put("type", "get_state"))
                requestStats()
                append(
                    ChatMsg(
                        id = "n${nextId++}",
                        role = Role.NOTICE,
                        text = "上次会话已不存在，已开始新会话",
                    ),
                )
                return
            }
            if (command == "switch_session") {
                desiredSessionPath = null
                ui = ui.copy(status = "")
                // Never leave the transcript empty after a failed switch:
                // reload whatever the pig is still working on.
                client.send(JSONObject().put("id", "state-${nextId++}").put("type", "get_state"))
                client.send(JSONObject().put("id", "msgs-${nextId++}").put("type", "get_messages"))
                requestStats()
            }
            append(
                ChatMsg(
                    id = "n${nextId++}",
                    role = Role.NOTICE,
                    text = "${event.optString("command")} failed: ${event.optString("error")}",
                    isError = true,
                ),
            )
            return
        }
        val command = event.optString("command")
        // These commands carry no data but still change UI state.
        if (command == "set_thinking_level") {
            client.send(JSONObject().put("id", "state-${nextId++}").put("type", "get_state"))
            return
        }
        if (command == "set_session_name") {
            requestSessions()
            return
        }
        if (command == "switch_session" || command == "new_session") {
            if (command == "new_session") {
                // A new session supersedes the remembered one; the next
                // get_state response remembers its file instead.
                prefs.edit().remove("lastSessionFile").apply()
                desiredSessionPath = null
                restorePending = false
            }
            ui = ui.copy(messages = emptyList(), status = "")
            liveText = null
            pendingToolPartials.clear()
            client.send(JSONObject().put("id", "state-${nextId++}").put("type", "get_state"))
            client.send(JSONObject().put("id", "msgs-${nextId++}").put("type", "get_messages"))
            requestStats()
            requestSessions()
            return
        }
        val data = event.optJSONObject("data") ?: return
        when (command) {
            "get_state" -> {
                val model = data.optJSONObject("model")
                val label = listOfNotNull(
                    model?.optString("provider")?.takeIf { it.isNotBlank() },
                    model?.optString("id")?.takeIf { it.isNotBlank() },
                ).joinToString("/")
                val sessionId = data.optString("sessionId", "")
                val sessionFile = data.optString("sessionFile", ui.sessionFile)
                val streaming = data.optBoolean("isStreaming", false)
                val live = data.optBoolean("live", true)
                val switched = sessionId.isNotBlank() &&
                    ui.sessionId.isNotBlank() &&
                    sessionId != ui.sessionId
                if (switched) currentAssistant = null
                var status = ui.status
                var toast = ui.toast

                if (!restoreChecked) {
                    restoreChecked = true
                    val saved = savedSession()
                    if (saved != null && (sessionFile.isBlank() || sessionFile != saved)) {
                        if (streaming) {
                            // The pig is still producing on another session;
                            // attach to the live turn instead of switching.
                            toast = "已接上正在运行的会话"
                        } else {
                            restorePending = true
                            desiredSessionPath = saved
                            ui = ui.copy(status = "restoring")
                            client.send(
                                JSONObject()
                                    .put("id", "switch-${nextId++}")
                                    .put("type", "switch_session")
                                    .put("sessionPath", saved),
                            )
                            return
                        }
                    } else if (saved != null && sessionFile == saved) {
                        toast = "已恢复上次会话"
                    }
                }

                if (restorePending && sessionFile.isNotBlank() && sessionFile == desiredSessionPath) {
                    restorePending = false
                    desiredSessionPath = null
                    status = ""
                    toast = "已恢复上次会话"
                }
                if (!restorePending && sessionFile.isNotBlank()) {
                    rememberSession(sessionFile)
                    if (sessionFile == desiredSessionPath) desiredSessionPath = null
                }

                if (!live) {
                    // Shown by the static read-only banner; the status bar is
                    // reserved for real activity (and its spinner).
                    status = ""
                }
                ui = ui.copy(
                    model = label,
                    sessionId = sessionId,
                    sessionFile = sessionFile,
                    busy = streaming,
                    readOnly = !live,
                    messages = if (switched) emptyList() else ui.messages,
                    contextWindow = if (live) {
                        model?.optInt("contextWindow", ui.contextWindow) ?: ui.contextWindow
                    } else {
                        0
                    },
                    contextPercent = if (live) ui.contextPercent else null,
                    contextTokens = if (live) ui.contextTokens else 0,
                    thinkingLevel = data.optString("thinkingLevel", ui.thinkingLevel),
                    status = status,
                    toast = toast,
                )
                if (streaming) bindResumedAssistant()
            }
            "get_session_stats" -> {
                if (ui.readOnly) return
                val usage = data.optJSONObject("contextUsage")
                if (usage != null) {
                    ui = ui.copy(
                        contextPercent = usage.optDouble("percent", 0.0),
                        contextTokens = usage.optInt("tokens", ui.contextTokens),
                        contextWindow = usage.optInt("contextWindow", ui.contextWindow),
                    )
                }
            }
            "get_messages" -> data.optJSONArray("messages")?.let { renderHistory(it) }
            "get_available_models" -> {
                val array = data.optJSONArray("models")
                val list = buildList {
                    if (array != null) {
                        for (index in 0 until array.length()) {
                            array.optJSONObject(index)?.let { add(modelInfo(it)) }
                        }
                    }
                }
                models = list.sortedWith(compareBy({ it.provider }, { it.id }))
                modelsLoading = false
                ui = ui.copy(availableModelCount = models.size)
            }
            "get_available_thinking_levels" -> {
                val levels = data.optJSONArray("levels")
                ui = ui.copy(
                    thinkingLevels = if (levels == null) {
                        emptyList()
                    } else {
                        (0 until levels.length()).map { levels.optString(it) }
                    },
                )
            }
            "set_model" -> {
                val provider = data.optString("provider", ui.model.substringBefore('/'))
                val id = data.optString("id", ui.model.substringAfter('/'))
                ui = ui.copy(
                    model = "$provider/$id",
                    contextWindow = data.optInt("contextWindow", ui.contextWindow),
                )
                client.send(
                    JSONObject().put("id", "levels-${nextId++}")
                        .put("type", "get_available_thinking_levels"),
                )
                client.send(JSONObject().put("id", "state-${nextId++}").put("type", "get_state"))
                requestStats()
            }
        }
    }

    /**
     * Derives pack-backed windows from `windows/<id>.json` and reconciles them with
     * the current list: add, update in place (position/z preserved), remove when
     * the file disappears, keep the last good window when a file fails to parse,
     * and honor the dismissed set.
     */
    private fun reconcileWindows(files: Map<String, String>, onDemand: Set<String> = emptySet()) {
        val windowFiles = files.filterKeys { it.startsWith("windows/") && it.endsWith(".json") }
        val parsed = mutableListOf<AppWindow>()
        val failedPaths = mutableSetOf<String>()
        val warnings = mutableListOf<String>()
        var duplicate: String? = null

        windowFiles.toSortedMap().forEach { (path, text) ->
            val result = AppUiParser.parseWindow(text, source = path)
            val window = result.window
            if (window == null) {
                failedPaths.add(path)
                warnings.add("$path: ${result.warnings.firstOrNull() ?: "invalid"}")
            } else {
                parsed.add(window)
            }
        }

        val byId = LinkedHashMap<String, AppWindow>()
        parsed.forEach { window ->
            if (byId.containsKey(window.id)) duplicate = "重复窗口 id: ${window.id}"
            byId[window.id] = window
        }

        val next = mutableListOf<AppWindow>()
        val placed = mutableSetOf<String>()

        ui.windows.forEach { current ->
            if (current.source == "debug") {
                if (!byId.containsKey(current.id)) {
                    next.add(current)
                    placed.add(current.id)
                }
                return@forEach
            }
            if (current.source !in windowFiles) return@forEach
            val updated = byId[current.id]
            when {
                updated != null -> {
                    next.add(updated)
                    placed.add(updated.id)
                }
                current.source in failedPaths -> {
                    next.add(current)
                    placed.add(current.id)
                }
            }
        }

        parsed.forEach { window ->
            if (window.id in placed) return@forEach
            if (!window.autoOpen) return@forEach
            if (window.id in onDemand) return@forEach
            if (dismissedWindows[window.source] == window.id) return@forEach
            dismissedWindows.remove(window.source)
            next.add(window)
            placed.add(window.id)
        }

        dismissedWindows.keys.retainAll(windowFiles.keys)
        knownWindows.entries.removeAll { it.value.source !in windowFiles.keys }
        parsed.forEach { knownWindows[it.id] = it }
        val ids = next.map { it.id }.toSet()
        windowPositions.keys.toList().forEach { if (it !in ids) windowPositions.remove(it) }

        ui = ui.copy(windows = next, windowError = warnings.firstOrNull() ?: duplicate)
    }

    private fun handleSessions(event: JSONObject) {
        val visited = prefs.getStringSet("visitedSessions", emptySet()) ?: emptySet()
        val array = event.optJSONArray("sessions")
        val sessions = buildList {
            if (array != null) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val path = item.optString("path")
                    add(
                        SessionInfo(
                            path = path,
                            id = item.optString("id"),
                            cwd = item.optString("cwd"),
                            name = item.optString("name"),
                            preview = item.optString("preview"),
                            updated = item.optLong("updated"),
                            size = item.optLong("size"),
                            live = item.optBoolean("live", false),
                            busy = item.optBoolean("busy", false),
                            visited = visited.contains(path),
                        ),
                    )
                }
            }
        }
        ui = ui.copy(sessions = sessions, sessionsLoading = false)
    }

    private fun renderHistory(messages: org.json.JSONArray) {
        val out = mutableListOf<ChatMsg>()
        val toolIds = mutableMapOf<String, String>()
        val lastInputRole = if (messages.length() > 0) {
            messages.optJSONObject(messages.length() - 1)?.optString("role")
        } else {
            null
        }
        toolMsgIds.clear()
        currentAssistant = null
        var counter = 1
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            when (message.optString("role")) {
                "user" -> {
                    val text = contentText(message)
                    if (text.isNotBlank()) out.add(ChatMsg("h${counter++}", Role.USER, text = text))
                }
                "assistant" -> {
                    val text = contentText(message)
                    if (text.isNotBlank()) out.add(ChatMsg("h${counter++}", Role.ASSISTANT, text = text))
                    val blocks = message.optJSONArray("content") ?: continue
                    for (blockIndex in 0 until blocks.length()) {
                        val block = blocks.optJSONObject(blockIndex) ?: continue
                        if (block.optString("type") != "toolCall") continue
                        val callId = block.optString("id")
                        val id = "h${counter++}"
                        if (callId.isNotBlank()) {
                            toolIds[callId] = id
                            // Let live tool_execution_update/end events find the
                            // card after a reconnect.
                            toolMsgIds[callId] = id
                        }
                        val pending = if (callId.isBlank()) null else pendingToolPartials.remove(callId)
                        out.add(
                            ChatMsg(
                                id = id,
                                role = Role.TOOL,
                                toolName = block.optString("name", "tool"),
                                toolArgs = block.optJSONObject("arguments")?.toString(2) ?: "",
                                toolResult = pending ?: "",
                                running = pending != null,
                            ),
                        )
                    }
                }
                "toolResult" -> {
                    val text = contentText(message)
                    val id = toolIds[message.optString("toolCallId")]
                    val position = out.indexOfFirst { it.id == id }
                    val meta = JSONObject()
                    message.optJSONObject("details")?.let { meta.put("details", it) }
                    message.optJSONObject("structuredContent")?.let { meta.put("structured", it) }
                    if (position >= 0) {
                        out[position] = out[position].copy(
                            running = false,
                            toolResult = text,
                            toolDetails = meta.toString(),
                            isError = message.optBoolean("isError", false),
                        )
                    } else {
                        out.add(
                            ChatMsg(
                                id = "h${counter++}",
                                role = Role.TOOL,
                                toolName = message.optString("toolName", "tool"),
                                toolResult = text,
                                isError = message.optBoolean("isError", false),
                            ),
                        )
                    }
                }
            }
        }
        // If the relay replayed an in-flight assistant message, put it back
        // after the history rebuild. Only the last input message can be the
        // partial assistant, so never overwrite an older completed bubble.
        val live = liveText
        if (!live.isNullOrEmpty() && lastInputRole == "assistant") {
            val last = out.lastOrNull()
            if (last?.role == Role.ASSISTANT) {
                if (last.text.length > live.length) {
                    // History got further than the relay snapshot; continue
                    // appending from the longer text.
                    liveText = last.text
                }
                out[out.lastIndex] = last.copy(text = if (last.text.length < live.length) live else last.text)
                currentAssistant = out.last().id
            } else {
                val id = "h${counter++}"
                out.add(ChatMsg(id = id, role = Role.ASSISTANT, text = live))
                currentAssistant = id
            }
        }
        ui = ui.copy(messages = out)
        bindResumedAssistant()
    }

    private fun handleUiPack(event: JSONObject) {
        val filesObject = event.optJSONObject("files") ?: JSONObject()
        val files = buildMap {
            val keys = filesObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, filesObject.optString(key, ""))
            }
        }
        val assetsObject = event.optJSONObject("assets") ?: JSONObject()
        val assets = buildMap {
            val keys = assetsObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, assetsObject.optString(key, ""))
            }
        }
        val hashesObject = event.optJSONObject("assetHashes") ?: JSONObject()
        val hashes = buildMap {
            val keys = hashesObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, hashesObject.optString(key, ""))
            }
        }
        packAssets = assets
        packAssetHashes = hashes
        val validImages = hashes.map { (path, hash) -> "$path@$hash" }.toSet()
        imageCache.keys.toList().forEach { if (it !in validImages) imageCache.remove(it) }
        imageLoading.retainAll(validImages)

        reconcileWindows(files, UiPackParser.navWindowIds(files))

        val hash = event.optString("hash")
        val result = UiPackParser.parse(files)
        val pack = result.pack
        if (pack == null) {
            val message = result.warnings.firstOrNull() ?: "unknown error"
            ui = ui.copy(uiPackError = message, toast = "UI pack 未应用: $message")
            return
        }
        val changed = ui.uiPackHash.isNotEmpty() && hash != ui.uiPackHash
        ui = ui.copy(
            uiPack = pack,
            uiPackHash = hash,
            uiPackFiles = files.size,
            assetCount = assets.size,
            uiPackError = result.warnings.firstOrNull(),
            toast = when {
                changed -> "UI pack 已应用: ${pack.name} v${pack.version}"
                result.warnings.isNotEmpty() -> result.warnings.joinToString("; ")
                else -> ui.toast
            },
        )
    }

    private fun handleMessageStart(message: JSONObject?) {
        if (message == null || message.optString("role") != "assistant") return
        val text = contentText(message)
        liveText = text
        val id = currentAssistant ?: "a${nextId++}".also {
            currentAssistant = it
            append(ChatMsg(id = it, role = Role.ASSISTANT, text = text))
        }
        if (text.isNotEmpty()) update(id) { it.copy(text = text) }
    }

    private fun handleMessageUpdate(event: JSONObject?) {
        if (event == null) return
        val update = event.optJSONObject("assistantMessageEvent") ?: return
        if (update.optString("type") != "text_delta") return
        val delta = update.optString("delta", "")
        if (delta.isEmpty()) return
        // liveText holds the text including the prefix replayed by relay_resume,
        // so replacing the bubble is safe after a mid-stream reconnect.
        liveText = (liveText ?: "") + delta
        val id = currentAssistant ?: "a${nextId++}".also {
            currentAssistant = it
            append(ChatMsg(id = it, role = Role.ASSISTANT))
        }
        val text = liveText ?: delta
        update(id) { it.copy(text = text) }
    }

    /** Relay replayed the assistant text that was streamed before the reconnect. */
    private fun handleResumeText(text: String) {
        if (text.isEmpty()) return
        liveText = text
        val id = currentAssistant ?: "a${nextId++}".also {
            currentAssistant = it
            append(ChatMsg(id = it, role = Role.ASSISTANT))
        }
        update(id) { it.copy(text = text) }
    }

    /** Relay replayed a tool that is still running (arrives before get_messages). */
    private fun handleRelayTool(event: JSONObject) {
        val callId = event.optString("toolCallId")
        if (callId.isEmpty()) return
        val partial = event.optJSONObject("partialResult")?.let { contentText(it) } ?: ""
        val id = toolMsgIds[callId]
        if (id == null) {
            if (partial.isNotEmpty()) pendingToolPartials[callId] = partial
            return
        }
        update(id) {
            it.copy(
                running = true,
                toolResult = partial.ifBlank { it.toolResult },
                toolName = event.optString("toolName").ifBlank { it.toolName ?: "tool" },
            )
        }
    }

    /** Reattach a reconnecting client to the assistant message that is still streaming. */
    private fun bindResumedAssistant() {
        if (!ui.busy || currentAssistant != null) return
        val last = ui.messages.lastOrNull() ?: return
        if (last.role == Role.ASSISTANT) currentAssistant = last.id
    }

    private fun handleMessageEnd(message: JSONObject?) {
        if (message == null || message.optString("role") != "assistant") return
        val id = currentAssistant
        if (id == null) {
            liveText = null
            return
        }
        val text = contentText(message)
        update(id) { it.copy(text = text.ifBlank { it.text }) }
        // Tool-call-only assistant messages have no text; the tool card is the
        // visible representation, so drop the empty bubble.
        if (ui.messages.firstOrNull { it.id == id }?.text.isNullOrBlank()) remove(id)
        currentAssistant = null
        liveText = null
    }

    private fun handleToolStart(event: JSONObject) {
        val callId = event.optString("toolCallId")
        if (callId.isEmpty()) return
        val id = "t${nextId++}"
        toolMsgIds[callId] = id
        append(
            ChatMsg(
                id = id,
                role = Role.TOOL,
                toolName = event.optString("toolName", "tool"),
                toolArgs = event.optJSONObject("args")?.toString(2) ?: "",
                running = true,
            ),
        )
    }

    private fun handleToolUpdate(event: JSONObject) {
        val id = toolMsgIds[event.optString("toolCallId")] ?: return
        val partial = contentText(event.optJSONObject("partialResult"))
        if (partial.isNotEmpty()) update(id) { it.copy(toolResult = partial) }
    }

    private fun handleToolEnd(event: JSONObject) {
        val id = toolMsgIds.remove(event.optString("toolCallId")) ?: return
        val resultObject = event.optJSONObject("result")
        val result = contentText(resultObject)
        val meta = JSONObject()
        resultObject?.optJSONObject("details")?.let { meta.put("details", it) }
        resultObject?.optJSONObject("structuredContent")?.let { meta.put("structured", it) }
        update(id) {
            it.copy(
                running = false,
                toolResult = result.ifBlank { it.toolResult },
                toolDetails = meta.toString(),
                isError = event.optBoolean("isError", false),
            )
        }
    }

    private fun handleUiRequest(event: JSONObject) {
        val method = event.optString("method")
        when (method) {
            "confirm", "select", "input", "editor" -> {
                val options = event.optJSONArray("options")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }
                } ?: emptyList()
                ui = ui.copy(
                    pending = UiRequest(
                        id = event.optString("id"),
                        method = method,
                        title = event.optString("title", ""),
                        message = event.optString("message", "").takeIf { it.isNotBlank() },
                        options = options,
                        placeholder = event.optString("placeholder", "").takeIf { it.isNotBlank() },
                        prefill = event.optString("prefill", ""),
                    ),
                )
            }
            "notify" -> ui = ui.copy(toast = event.optString("message", ""))
            "setStatus" -> ui = ui.copy(status = event.optString("statusText", ""))
            "setTitle" -> ui = ui.copy(
                titleOverride = event.optString("title", "").takeIf { it.isNotBlank() },
            )
            "setWidget" -> handleWidget(event)
            else -> Unit // setWidget/setTitle/set_editor_text are not rendered in M0.
        }
    }

    private fun contentText(object0: JSONObject?): String {
        if (object0 == null) return ""
        val blocks = object0.optJSONArray("content") ?: return object0.optString("text", "")
        val builder = StringBuilder()
        for (index in 0 until blocks.length()) {
            val block = blocks.optJSONObject(index) ?: continue
            if (block.optString("type") == "text") builder.append(block.optString("text", ""))
        }
        return builder.toString()
    }

    override fun onCleared() {
        if (current === this) current = null
        windowPositions.clear()
        formValues.clear()
        // The Activity may be destroyed while the keep-alive service keeps the
        // process alive. Close the socket so a later ViewModel cannot end up
        // with a second relay connection; onResume reconnects and restores the
        // session file.
        client.close()
        KeepAliveService.stop(getApplication())
        super.onCleared()
    }

    private fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

    companion object {
        @Volatile
        var current: AppViewModel? = null
            private set
    }
}
