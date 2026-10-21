package com.linuxwff.pigapp

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
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

    fun newSession() {
        currentAssistant = null
        client.send(JSONObject().put("type", "new_session"))
    }

    fun clearLocal() {
        ui = ui.copy(messages = emptyList())
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

    private fun append(message: ChatMsg) {
        ui = ui.copy(messages = ui.messages + message)
    }

    private fun update(id: String, block: (ChatMsg) -> ChatMsg) {
        ui = ui.copy(messages = ui.messages.map { if (it.id == id) block(it) else it })
    }

    private fun handle(event: JSONObject) {
        when (val type = event.optString("type")) {
            "relay_hello" -> {
                ui = ui.copy(error = null)
                client.send(JSONObject().put("id", "state-1").put("type", "get_state"))
                client.send(JSONObject().put("id", "stats-1").put("type", "get_session_stats"))
            }
            "relay_client_error" -> ui = ui.copy(error = event.optString("message"))
            "relay_exit" -> ui = ui.copy(
                busy = false,
                status = "",
                error = "pig 已退出（code ${event.optInt("code")}）",
            )
            "relay_ui_pack" -> ui = ui.copy(
                uiPackHash = event.optString("hash"),
                uiPackFiles = event.optJSONObject("files")?.length() ?: 0,
            )
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
                ui = ui.copy(
                    model = label,
                    sessionId = data.optString("sessionId", ""),
                    busy = data.optBoolean("isStreaming", ui.busy),
                )
            }
            "get_session_stats" -> {
                val usage = data.optJSONObject("contextUsage")
                if (usage != null) {
                    val percent = usage.optDouble("percent", 0.0)
                    ui = ui.copy(status = if (percent > 0) "context ${"%.1f".format(percent)}%" else ui.status)
                }
            }
        }
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
        super.onCleared()
    }

    private fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

    companion object {
        @Volatile
        var current: AppViewModel? = null
            private set
    }
}
