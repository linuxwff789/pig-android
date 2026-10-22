package com.linuxwff.pigapp

import org.json.JSONObject

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
    /** Optional `pack:<action>` run when the entry is tapped. */
    val action: String = "",
)

data class UiCommand(val name: String, val description: String = "")

/** One `dataSources` entry in `layout/chat.json`. */
data class UiDataSource(
    val rpc: String,
    val params: JSONObject? = null,
    val refresh: Set<String> = emptySet(),
)

/** One `bindings.<slot>` entry: a JSON path plus an optional `{key}` template. */
data class UiBinding(
    val path: String,
    val format: String = "",
    val fallback: String = "",
)

/** One named `actions.<name>` entry in `layout/chat.json`. */
data class UiAction(
    val rpc: String,
    val params: JSONObject? = null,
    val confirm: String = "",
)

data class UiBubbleAppearance(
    val paddingH: Int = 14,
    val paddingV: Int = 11,
    val borderWidth: Int = 1,
    /** Theme token for user bubble text. */
    val textColor: String = "onSurface",
    /** Render assistant Markdown inside a surface card. */
    val assistantBubble: Boolean = false,
    val assistantBackgroundAlpha: Float = 0.55f,
    val assistantBorderAlpha: Float = 0.25f,
    val assistantTextColor: String = "onSurface",
)

data class UiToolCardAppearance(
    /** surface | surfaceVariant | background | transparent */
    val background: String = "surface",
    val backgroundAlpha: Float = 1f,
    val radius: Int = -1,
    val borderWidth: Int = 1,
    /** -1 inherits theme.cardBorderAlpha. */
    val borderAlpha: Float = -1f,
    val padding: Int = 12,
    val collapsedPaddingH: Int = 11,
    val collapsedPaddingV: Int = 8,
    val iconBoxSize: Int = 26,
    val iconSize: Int = 15,
    val iconRadius: Int = 8,
    val iconAlpha: Float = 0.14f,
    val titleSize: Int = 0,
    val metricsColor: String = "onSurfaceVariant",
    val chipRadius: Int = 6,
    val chipAlpha: Float = 0.15f,
    val groupRadius: Int = -1,
    val groupPadding: Int = 10,
)

data class UiDrawerAppearance(
    /** theme | surface | gradient | transparent */
    val background: String = "theme",
    val gradientAlpha: Float = 0.18f,
    val cornerRadius: Int = -1,
    val paddingH: Int = 16,
    val paddingV: Int = 20,
    val spacing: Int = 10,
    val brandAvatarSize: Int = 44,
    val brandTagline: Boolean = true,
    val brandCardAlpha: Float = 0.34f,
    val brandPills: Boolean = true,
    val buttonHeight: Int = 0,
    val buttonRadius: Int = -1,
    val buttonIconSize: Int = 16,
    val sectionLabelSize: Int = 0,
    val sectionLabelUppercase: Boolean = false,
    val sessionRowRadius: Int = -1,
    val sessionSelectedAlpha: Float = 0.13f,
    val showSessionId: Boolean = true,
    val showSessionTime: Boolean = true,
    val searchRadius: Int = -1,
    /** Optional drawer modules; sessions/settings are always rendered. */
    val modules: List<String> = listOf(
        "brand", "newSession", "skinSwitcher", "packManager", "panels",
    ),
)

data class UiHeader(
    val show: Boolean = true,
    val heightDp: Int = 0,
    val paddingH: Int = 12,
    val paddingV: Int = 2,
    /** gradient | surface | transparent */
    val background: String = "gradient",
    val gradientAlpha: Float = 0.13f,
    val divider: Boolean = false,
    /** Pixel-level header tree; when present it replaces `rows`. */
    val body: UiNode? = null,
    val rows: List<UiHeaderRow> = emptyList(),
)

data class UiHeaderRow(
    val spacingDp: Int = 6,
    /** start | center | end */
    val align: String = "start",
    val heightDp: Int = 0,
    val elements: List<UiHeaderElement> = emptyList(),
)

data class UiHeaderElement(
    val type: String,
    val visible: Boolean = true,
    /** always | busy | idle | connected | offline | streaming | compacting */
    val condition: String = "always",
    val size: Int = 0,
    val weight: Float = 0f,
    val padding: Int = 0,
    val icon: String = "",
    val label: String = "",
    val text: String = "",
    val style: String = "label",
    /** primary | secondary | tertiary | onSurface | onSurfaceVariant | error | success | warning */
    val color: String = "onSurfaceVariant",
    val bind: String = "",
    val format: String = "",
    val action: String = "",
    val avatar: Boolean = true,
    val avatarSize: Int = 0,
    val online: Boolean = true,
)

/** A pack action waiting for user confirmation. */
data class PackActionRequest(
    val name: String,
    val message: String,
    val windowId: String? = null,
)

/** A pack imported into the app's private `ui-packs/` directory. */
data class LocalPackInfo(
    val id: String,
    val name: String,
    val version: Int,
    val files: Int,
    val importedAt: Long = 0L,
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
    /** Selected `skins.json` id; empty means the base theme. */
    val skinId: String = "",
    /** Imported packs in the app's private directory. */
    val localPacks: List<LocalPackInfo> = emptyList(),
    /** Active local pack id; empty means the pack pushed by the relay. */
    val activePackId: String = "",
    /** Name of the pack the relay is currently pushing (the built-in entry). */
    val relayPackName: String = "",
    /** Raw RPC response data for pack bindings, keyed by alias/source name. */
    val rpcData: Map<String, JSONObject> = emptyMap(),
    /** Set when a pack action declares a `confirm` message. */
    val pendingPackAction: PackActionRequest? = null,
    val contextPercent: Double? = null,
    val contextTokens: Int = 0,
    /** Right after /compact PiG reports null usage until the next model call. */
    val contextCompacted: Boolean = false,
    /** Cumulative prompt-cache hit rate (cacheRead / (input + cacheRead + cacheWrite)). */
    val cacheHitRate: Double? = null,
    val cacheReadTokens: Long = 0,
    val cacheInputTokens: Long = 0,
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
    /** User-specified context window overrides, keyed by `provider/id`. */
    val contextOverrides: Map<String, Int> = emptyMap(),
    val thinkingLevel: String = "off",
    val thinkingLevels: List<String> = emptyList(),
    val assetCount: Int = 0,
    val setup: Boolean = false,
    val setupHint: String? = null,
    val wakeLock: Boolean = true,
    val keepAlive: Boolean = true,
    /** Glow behind per-message avatars; off by default for cheaper drawing. */
    val avatarShadow: Boolean = false,
    /** Shadow under user message bubbles; defaults from the UI pack. */
    val bubbleShadow: Boolean = true,
    /** Empty = use the UI pack's chat.toolCards; otherwise collapsed/summary/expanded. */
    val toolMode: String = "",
    /** True for a history session served from disk (no pig process running). */
    val readOnly: Boolean = false,
    /** Message currently being streamed (rendered as plain text for speed). */
    val streamingId: String? = null,
    /** Steering/follow-up messages still queued in pig. */
    val pendingMessages: Int = 0,
    /** Slash commands (built-ins + registered extension commands). */
    val commands: List<UiCommand> = emptyList(),
    /** A model switch is in flight; header shows the actual model until get_state confirms. */
    val modelSwitchPending: Boolean = false,
    val pendingModel: String = "",
)
