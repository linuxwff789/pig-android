package com.linuxwff.pigapp

enum class Role { USER, ASSISTANT, TOOL, NOTICE }

data class ChatMsg(
    val id: String,
    val role: Role,
    val text: String = "",
    val toolName: String? = null,
    val toolArgs: String = "",
    val toolResult: String = "",
    val toolDetails: String = "",
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
    /** A pig process is attached to this session (idle or streaming). */
    val live: Boolean = false,
    /** That pig is currently producing a turn. */
    val busy: Boolean = false,
    /** The app has opened this session at least once. */
    val visited: Boolean = false,
)

data class AppWidget(val key: String, val lines: List<String>, val placement: String)

/** A drawer entry from `nav.json`; tapping it opens `window` and runs `command`. */
data class NavItem(
    val id: String,
    val label: String,
    val window: String,
    val command: String = "",
    val icon: String = "",
    /** "drawer" (default) or "settings". */
    val placement: String = "drawer",
)

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
    val contextTokens: Int = 0,
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
    val assetCount: Int = 0,
    val setup: Boolean = false,
    val setupHint: String? = null,
    val wakeLock: Boolean = true,
    val keepAlive: Boolean = true,
    /** Empty = use the UI pack's chat.toolCards; otherwise collapsed/summary/expanded. */
    val toolMode: String = "",
    /** True for a history session served from disk (no pig process running). */
    val readOnly: Boolean = false,
    /** Message currently being streamed (rendered as plain text for speed). */
    val streamingId: String? = null,
)
