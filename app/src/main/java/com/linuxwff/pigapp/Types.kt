package com.linuxwff.pigapp

enum class Role { USER, ASSISTANT, TOOL, NOTICE }

data class ChatMsg(
    val id: String,
    val role: Role,
    val text: String = "",
    val toolName: String? = null,
    val toolArgs: String = "",
    val toolResult: String = "",
    val running: Boolean = false,
    val isError: Boolean = false,
    val expanded: Boolean = true,
)

data class SessionInfo(
    val path: String,
    val id: String,
    val cwd: String,
    val name: String,
    val preview: String,
    val updated: Long,
    val size: Long,
)

data class AppWidget(val key: String, val lines: List<String>, val placement: String)

data class ModelInfo(
    val provider: String,
    val id: String,
    val name: String,
    val contextWindow: Int,
    val maxTokens: Int,
    val reasoning: Boolean,
    val images: Boolean,
)

data class UiRequest(
    val id: String,
    val method: String,
    val title: String,
    val message: String? = null,
    val options: List<String> = emptyList(),
    val placeholder: String? = null,
    val prefill: String = "",
)

data class AppUiState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val messages: List<ChatMsg> = emptyList(),
    val status: String = "",
    val model: String = "",
    val sessionId: String = "",
    val pending: UiRequest? = null,
    val error: String? = null,
    val toast: String? = null,
    val uiPackHash: String = "",
    val uiPackFiles: Int = 0,
    val uiPack: UiPack = UiPack(),
    val uiPackError: String? = null,
    val contextPercent: Double? = null,
    val sessions: List<SessionInfo> = emptyList(),
    val sessionsLoading: Boolean = false,
    val sessionFile: String = "",
    val pendingSwitch: SessionInfo? = null,
    val windows: List<AppWindow> = emptyList(),
    val windowError: String? = null,
    val widgets: List<AppWidget> = emptyList(),
    val titleOverride: String? = null,
    val availableModelCount: Int = 0,
    val contextWindow: Int = 0,
    val thinkingLevel: String = "off",
    val thinkingLevels: List<String> = emptyList(),
)
