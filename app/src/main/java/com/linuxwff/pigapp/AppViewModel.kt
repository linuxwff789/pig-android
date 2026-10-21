package com.linuxwff.pigapp

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    init {
        current = this
        viewModelScope.launch {
            client.connected.collect { connected -> ui = ui.copy(connected = connected) }
        }
        viewModelScope.launch {
            client.events.collect { event -> handle(event) }
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
            ui = ui.copy(error = "无法启动 pig-relay：$launchError")
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

    fun reconnect() {
        client.close()
        connect()
    }

    fun sendPrompt(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        append(ChatMsg(id = "u${nextId++}", role = Role.USER, text = text))
        currentAssistant = null
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

    fun switchSession(path: String) {
        val session = ui.sessions.firstOrNull { it.path == path }
        val recent = session != null && System.currentTimeMillis() - session.updated < 60_000
        if (recent && path != ui.sessionFile) {
            ui = ui.copy(pendingSwitch = session)
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
        ui = ui.copy(status = "switching", messages = emptyList())
        client.send(
            JSONObject()
                .put("id", "switch-${nextId++}")
                .put("type", "switch_session")
                .put("sessionPath", path),
        )
    }

    fun newSession() {
        currentAssistant = null
        client.send(JSONObject().put("type", "new_session"))
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
                ui = ui.copy(error = null)
                client.send(JSONObject().put("id", "state-1").put("type", "get_state"))
                client.send(JSONObject().put("id", "stats-1").put("type", "get_session_stats"))
                requestSessions()
            }
            "relay_client_error" -> ui = ui.copy(error = event.optString("message"))
            "relay_exit" -> ui = ui.copy(
                busy = false,
                status = "",
                error = "pig 已退出（code ${event.optInt("code")}）",
            )
            "relay_ui_pack" -> handleUiPack(event)
            "relay_sessions" -> handleSessions(event)
            "response" -> handleResponse(event)
            "agent_start" -> ui = ui.copy(busy = true, status = "thinking")
            "turn_start" -> ui = ui.copy(status = "working")
            "agent_end", "agent_settled" -> ui = ui.copy(busy = false, status = "")
            "message_start" -> handleMessageStart(event.optJSONObject("message"))
            "message_update" -> handleMessageUpdate(event.optJSONObject("assistantMessageEvent"))
            "message_end" -> handleMessageEnd(event.optJSONObject("message"))
            "tool_execution_start" -> handleToolStart(event)
            "tool_execution_update" -> handleToolUpdate(event)
            "tool_execution_end" -> handleToolEnd(event)
            "extension_ui_request" -> handleUiRequest(event)
            "compaction_start" -> ui = ui.copy(status = "compacting")
            "compaction_end" -> ui = ui.copy(status = "")
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
        val data = event.optJSONObject("data") ?: return
        when (event.optString("command")) {
            "get_state" -> {
                val model = data.optJSONObject("model")
                val label = listOfNotNull(
                    model?.optString("provider")?.takeIf { it.isNotBlank() },
                    model?.optString("id")?.takeIf { it.isNotBlank() },
                ).joinToString("/")
                val sessionId = data.optString("sessionId", "")
                val switched = sessionId.isNotBlank() &&
                    ui.sessionId.isNotBlank() &&
                    sessionId != ui.sessionId
                if (switched) currentAssistant = null
                ui = ui.copy(
                    model = label,
                    sessionId = sessionId,
                    sessionFile = data.optString("sessionFile", ui.sessionFile),
                    busy = data.optBoolean("isStreaming", ui.busy),
                    messages = if (switched) emptyList() else ui.messages,
                    contextWindow = model?.optInt("contextWindow", ui.contextWindow)
                        ?: ui.contextWindow,
                    thinkingLevel = data.optString("thinkingLevel", ui.thinkingLevel),
                )
            }
            "get_session_stats" -> {
                val usage = data.optJSONObject("contextUsage")
                if (usage != null) {
                    ui = ui.copy(contextPercent = usage.optDouble("percent", 0.0))
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
            }
            "set_thinking_level" -> {
                val level = data.optString("level", "").ifBlank { ui.thinkingLevel }
                ui = ui.copy(thinkingLevel = level)
                client.send(JSONObject().put("id", "state-${nextId++}").put("type", "get_state"))
            }
            "switch_session", "new_session" -> {
                ui = ui.copy(messages = emptyList(), status = "")
                client.send(JSONObject().put("id", "state-${nextId++}").put("type", "get_state"))
                client.send(JSONObject().put("id", "msgs-${nextId++}").put("type", "get_messages"))
                requestSessions()
            }
        }
    }

    /**
     * Derives pack-backed windows from `windows/<id>.json` and reconciles them with
     * the current list: add, update in place (position/z preserved), remove when
     * the file disappears, keep the last good window when a file fails to parse,
     * and honor the dismissed set.
     */
    private fun reconcileWindows(files: Map<String, String>) {
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
            if (dismissedWindows[window.source] == window.id) return@forEach
            dismissedWindows.remove(window.source)
            next.add(window)
            placed.add(window.id)
        }

        dismissedWindows.keys.retainAll(windowFiles.keys)
        val ids = next.map { it.id }.toSet()
        windowPositions.keys.toList().forEach { if (it !in ids) windowPositions.remove(it) }

        ui = ui.copy(windows = next, windowError = warnings.firstOrNull() ?: duplicate)
    }

    private fun handleSessions(event: JSONObject) {
        val array = event.optJSONArray("sessions")
        val sessions = buildList {
            if (array != null) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        SessionInfo(
                            path = item.optString("path"),
                            id = item.optString("id"),
                            cwd = item.optString("cwd"),
                            name = item.optString("name"),
                            preview = item.optString("preview"),
                            updated = item.optLong("updated"),
                            size = item.optLong("size"),
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
                        if (callId.isNotBlank()) toolIds[callId] = id
                        out.add(
                            ChatMsg(
                                id = id,
                                role = Role.TOOL,
                                toolName = block.optString("name", "tool"),
                                toolArgs = block.optJSONObject("arguments")?.toString(2) ?: "",
                            ),
                        )
                    }
                }
                "toolResult" -> {
                    val text = contentText(message)
                    val id = toolIds[message.optString("toolCallId")]
                    val position = out.indexOfFirst { it.id == id }
                    if (position >= 0) {
                        out[position] = out[position].copy(
                            toolResult = text,
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
        ui = ui.copy(messages = out)
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
        reconcileWindows(files)

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
        val id = currentAssistant ?: "a${nextId++}".also {
            currentAssistant = it
            append(ChatMsg(id = it, role = Role.ASSISTANT, text = text))
        }
        if (text.isNotEmpty()) update(id) { it.copy(text = text) }
    }

    private fun handleMessageUpdate(event: JSONObject?) {
        if (event == null || event.optString("type") != "text_delta") return
        val delta = event.optString("delta", "")
        if (delta.isEmpty()) return
        val id = currentAssistant ?: return
        update(id) { it.copy(text = it.text + delta) }
    }

    private fun handleMessageEnd(message: JSONObject?) {
        if (message == null || message.optString("role") != "assistant") return
        val id = currentAssistant ?: return
        val text = contentText(message)
        update(id) { it.copy(text = text.ifBlank { it.text }) }
        // Tool-call-only assistant messages have no text; the tool card is the
        // visible representation, so drop the empty bubble.
        if (ui.messages.firstOrNull { it.id == id }?.text.isNullOrBlank()) remove(id)
        currentAssistant = null
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
        val result = contentText(event.optJSONObject("result"))
        update(id) {
            it.copy(
                running = false,
                toolResult = result.ifBlank { it.toolResult },
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
        super.onCleared()
    }

    private fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

    companion object {
        @Volatile
        var current: AppViewModel? = null
            private set
    }
}
