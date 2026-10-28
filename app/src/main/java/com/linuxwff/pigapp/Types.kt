package com.linuxwff.pigapp

import androidx.compose.ui.graphics.ImageBitmap
import org.json.JSONObject

enum class Role { USER, ASSISTANT, TOOL, NOTICE }

/**
 * One inline image decoded from a transcript block (user attachment, tool
 * output such as `read`/screenshot, or an assistant image block).
 *
 * Images are decoded while the transcript is built (off the main thread) and
 * downsampled, so a long session does not keep base64 blobs in memory.
 */
data class ChatImage(
    val bitmap: ImageBitmap,
    /** Wire media type, e.g. `image/png`. */
    val mimeType: String,
)

/** An image picked in the composer: base64 for the prompt, bitmap for the thumb. */
data class PendingImage(
    val id: String,
    val mimeType: String,
    val base64: String,
    val bitmap: ImageBitmap,
)

/**
 * One message waiting in pig's queue.
 *
 * `kind` is `steer` (injected into the running turn, 插队) or `followUp`
 * (sent after it settles, 排队). The same text can legitimately be queued in
 * both lists, so identity is the whole value, not an id (pig's queue is a
 * plain string list).
 */
data class QueuedMessage(
    val kind: String,
    val text: String,
) {
    val isSteer: Boolean get() = kind == "steer"
}

data class ChatMsg(
    val id: String,
    val role: Role,
    val text: String = "",
    /** Visible reasoning ("thinking") blocks of an assistant message. */
    val thinking: String = "",
    val toolName: String? = null,
    val toolArgs: String = "",
    val toolResult: String = "",
    val toolDetails: String = "",
    val running: Boolean = false,
    val isError: Boolean = false,
    val expanded: Boolean = true,
    /** Inline images carried by this message (see [ChatImage]). */
    val images: List<ChatImage> = emptyList(),
    /** Image blocks dropped by the size/count caps. */
    val imagesOmitted: Int = 0,
)

/** Cumulative counters from `get_session_stats` (whole session file). */
data class SessionUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val total: Long = 0,
    val cost: Double = 0.0,
    val userMessages: Int = 0,
    val assistantMessages: Int = 0,
    val toolCalls: Int = 0,
    val toolResults: Int = 0,
    val totalMessages: Int = 0,
) {
    /** Cache hit share of the prompt, same formula as the header. */
    val cacheRate: Double?
        get() {
            val denominator = input + cacheRead + cacheWrite
            return if (denominator > 0) cacheRead * 100.0 / denominator else null
        }
}

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
    /** Live session without a file yet: created, nothing sent. */
    val unsaved: Boolean = false,
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
    /** Colour token for the user bubble outline. */
    val borderColor: String = "primary",
    val assistantBorderColor: String = "outline",
    /** `brand` keeps the gradient, any other value is a solid token. */
    val userBackground: String = "brand",
    val userBackgroundAlpha: Float = 1f,
    /** start | center | end */
    val textAlign: String = "start",
    /** Per-corner overrides; -1 inherits the theme radius. */
    val radiusTL: Int = -1,
    val radiusTR: Int = -1,
    val radiusBR: Int = -1,
    val radiusBL: Int = -1,
)

/** `chat.thinking`: the reasoning block above an assistant answer. */
/** `chrome`: status bar, toast, dialogs, filter chips and the empty state. */
data class UiChromeAppearance(
    val statusColor: String = "onSurfaceVariant",
    val statusSize: Int = 0,
    val statusSpinnerColor: String = "secondary",
    val statusSpinnerSize: Int = 0,
    val statusBackground: String = "surfaceVariant",
    val statusBackgroundAlpha: Float = 0.55f,
    val statusPaddingH: Int = 12,
    val statusPaddingV: Int = 6,
    /** Empty string keeps the Material default. */
    val toastContainer: String = "",
    val toastText: String = "",
    val toastAction: String = "",
    val toastShape: Int = -1,
    val toastInsetH: Int = 12,
    val dialogSurface: String = "",
    val dialogRadius: Int = -1,
    val dialogMaxWidth: Int = 0,
    val dialogTitleColor: String = "onSurface",
    val dialogTextColor: String = "onSurfaceVariant",
    val chipBackground: String = "primary",
    val chipBackgroundAlpha: Float = 0.18f,
    val chipLabelColor: String = "primary",
    val chipShape: String = "circle",
    val chipRadius: Int = -1,
    /** Empty string inherits the ambient content colour. */
    val emptyTitleColor: String = "",
    /** Jump-to-bottom pill: fill token + opacity, icon/label token + opacity. */
    val fabBackground: String = "primary",
    val fabBackgroundAlpha: Float = 1f,
    val fabTint: String = "onPrimary",
    val fabTintAlpha: Float = 1f,
)

/** `markdown`: colours for the Markdown body (links, inline code, tables, rules). */
data class UiMarkdownAppearance(
    val linkColor: String = "secondary",
    val inlineCodeBackground: String = "surfaceVariant",
    val inlineCodeColor: String = "onSurface",
    val codeBlockBackground: String = "background",
    val codeBlockAlpha: Float = 0.6f,
    val codeBlockBorderColor: String = "outline",
    val quoteTextColor: String = "onSurfaceVariant",
    val tableHeaderBackground: String = "surfaceVariant",
    val tableBorderColor: String = "outline",
    val tableBorderAlpha: Float = 0.4f,
    val tableZebraAlpha: Float = 0f,
    val hrColor: String = "outlineVariant",
)

data class UiThinkingAppearance(
    val show: Boolean = true,
    val collapsedByDefault: Boolean = true,
    /** Open automatically while the block is still streaming. */
    val expandWhileStreaming: Boolean = true,
    val showCount: Boolean = true,
    /** Overrides the `chat.thinking` pack string when set. */
    val title: String = "",
    /** Zero keeps the app's body-small size. */
    val fontSize: Int = 0,
    val italic: Boolean = false,
    /** surface | surfaceVariant | background | transparent */
    val background: String = "surfaceVariant",
    val backgroundAlpha: Float = 0.42f,
    val titleColor: String = "secondary",
    val textColor: String = "onSurfaceVariant",
    /** -1 uses 10dp. */
    val radius: Int = -1,
    val paddingH: Int = 10,
    val paddingV: Int = 7,
    val maxHeight: Int = 240,
    /** Glyphs for the collapsed / expanded header. */
    val collapsedGlyph: String = "▸",
    val expandedGlyph: String = "▾",
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
    val borderColor: String = "outline",
    val errorBorderColor: String = "error",
    val titleColor: String = "onSurface",
    val iconTint: String = "secondary",
    val iconBackground: String = "secondary",
    val diffAddColor: String = "success",
    val diffRemoveColor: String = "error",
    val diffContextColor: String = "onSurfaceVariant",
    val codeFontFamily: String = "",
    val codeFontSize: Int = 0,
    val chipAlpha: Float = 0.15f,
    val groupRadius: Int = -1,
    val groupPadding: Int = 10,
    /** Card width cap in dp; 0 fills the transcript. Rows stay left aligned. */
    val maxWidth: Int = 0,
    /**
     * Per-tool colour overrides, keyed by lowercase tool name (`bash`, `edit`,
     * `read`, ...). A key ending in `*` is a prefix match (`codegraph*`); an
     * exact key wins. Blank fields inherit the card defaults above.
     */
    val byTool: Map<String, UiToolStyle> = emptyMap(),
) {
    /** Overrides for [tool], or an empty style when the pack declares none. */
    fun styleFor(tool: String?): UiToolStyle {
        if (byTool.isEmpty()) return UiToolStyle()
        val name = tool?.trim()?.lowercase().orEmpty()
        if (name.isEmpty()) return UiToolStyle()
        byTool[name]?.let { return it }
        return byTool.entries
            .firstOrNull { (key, _) -> key.endsWith("*") && name.startsWith(key.dropLast(1)) }
            ?.value
            ?: UiToolStyle()
    }
}

/** Colours one tool's cards may override; blank means "inherit the card". */
data class UiToolStyle(
    val iconTint: String = "",
    val iconBackground: String = "",
    val titleColor: String = "",
    val borderColor: String = "",
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
    /** Max lines for `text` elements; a long session title must not wrap. */
    val lines: Int = 2,
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
    /** Shipped inside the APK (`assets/ui-packs/`); cannot be deleted. */
    val bundled: Boolean = false,
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

/** One model a custom provider exposes; `contextWindow`/`maxTokens` may be 0. */
data class ProviderModel(
    val id: String,
    val name: String,
    val contextWindow: Int = 0,
    val maxTokens: Int = 0,
)

/** One entry of `~/.pig/agent/custom-endpoints.json`. The API key never leaves Termux. */
data class ProviderInfo(
    val id: String,
    val name: String,
    val baseUrl: String,
    val api: String,
    val hasKey: Boolean,
    val auto: Boolean,
    val modelsUrl: String = "",
    /** Effective list: the cache for auto providers, the configured array otherwise. */
    val models: List<ProviderModel> = emptyList(),
    /** Every model ever fetched, even when only some are enabled. */
    val cachedModels: List<ProviderModel> = emptyList(),
    val fetchedAt: Long = 0,
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

    /** Status lines pinned by extensions, keyed by `statusKey`. */
    val extensionStatuses: Map<String, String> = emptyMap(),

    /** A pin landed while a turn was running; restart pig when it settles. */
    val pendingAgentRestart: Boolean = false,

    /** Widget keys the user collapsed (plan cards). Persisted across launches. */
    val collapsedWidgets: Set<String> = emptySet(),

    /** Widget key -> content the user dismissed; the card returns when it changes. */
    val dismissedWidgets: Map<String, String> = emptyMap(),
    val model: String = "",
    val sessionId: String = "",
    val pending: UiRequest? = null,
    /** The user tapped the chat to dismiss the dialog; the request still waits. */
    val pendingHidden: Boolean = false,
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
    /** Full-session counters, refreshed by `get_session_stats`. */
    val usage: SessionUsage = SessionUsage(),
    val cacheReadTokens: Long = 0,
    val cacheInputTokens: Long = 0,
    val sessions: List<SessionInfo> = emptyList(),
    val sessionsLoading: Boolean = false,
    val sessionFile: String = "",
    /** Bumped when the open conversation changes (a new session or a switch), so
     *  the composer reloads its draft then — and not when a session that had no
     *  file yet merely gets one. */
    val draftEpoch: Int = 0,
    val pendingSwitch: SessionInfo? = null,
    val windows: List<AppWindow> = emptyList(),
    val windowError: String? = null,
    val widgets: List<AppWidget> = emptyList(),
    val titleOverride: String? = null,
    val availableModelCount: Int = 0,
    val contextWindow: Int = 0,
    /** Window PiG itself reports, before the user-pinned override. */
    val contextWindowActual: Int = 0,
    /** User-specified context window overrides, keyed by `provider/id`. */
    val contextOverrides: Map<String, Int> = emptyMap(),
    val thinkingLevel: String = "off",
    val thinkingLevels: List<String> = emptyList(),
    /** PiG persists this one in settings.json (`compaction.enabled`). */
    val autoCompaction: Boolean = true,
    /** Session-scoped; PiG does not report it back, so the app tracks it. */
    val autoRetry: Boolean = true,
    /** `one-at-a-time` or `all`, applied to queued steering messages. */
    val steeringMode: String = "one-at-a-time",
    /** `one-at-a-time` or `all`, applied to queued follow-up messages. */
    val followUpMode: String = "one-at-a-time",
    /** True once the relay answered `relay_settings`; older relays do not. */
    val settingsRemote: Boolean = false,

    /** Connected relay announces `backup` (older releases would answer `unknown relay frame`). */
    val backupRemote: Boolean = false,
    /** Connected relay announces `providers` (native provider manager). */
    val providerRemote: Boolean = false,
    /** At least one `get_available_models` answer arrived for this connection. */
    val modelsLoaded: Boolean = false,
    /** The "no models yet" banner was dismissed for this process. */
    val providerSetupDismissed: Boolean = false,
    /** Connected relay gates its agent while the Termux installer runs. */
    val installStatusRemote: Boolean = false,
    /** `version` from relay_hello (empty for relays older than 1.1.27). */
    val relayVersion: String = "",
    /** Relay is older than this app (missing features); offer an in-place update. */
    val relayOutdated: Boolean = false,
    /** Relay is newer than this app; suggest updating the app, never downgrade. */
    val relayAhead: Boolean = false,
    /** Installer stage from `relay_install`; 0/0 means unknown (use elapsed time). */
    val installStep: Int = 0,
    val installTotal: Int = 0,
    val installMessage: String = "",
    /** The native provider manager is open (opened by a UI pack nav entry). */
    val providerManagerOpen: Boolean = false,
    val providers: List<ProviderInfo> = emptyList(),
    /** Provider ids owned by models.json; read-only in this manager. */
    val providersModelsFile: List<String> = emptyList(),
    val providersPath: String = "",
    val providersLoading: Boolean = false,
    val providersOffline: Boolean = false,
    /** A provider save/delete/refresh round trip is running. */
    val providerBusy: Boolean = false,
    /** Last provider result, shown inside the manager (may be an error). */
    val providerMessage: String = "",
    val providerMessageError: Boolean = false,
    /** Model list returned by the relay, for the native model picker. */
    val providerModels: List<ProviderModel> = emptyList(),
    val providerModelsFor: String = "",
    val providerModelsAt: Long = 0,
    /** Bumped when a provider save/delete succeeds, so the editor can close. */
    val providerEpoch: Int = 0,
    /** A backup export, restore or uninstall is running. */
    val maintenanceBusy: Boolean = false,
    /** What the running maintenance job is doing, shown in the settings dialog. */
    val maintenanceLabel: String = "",
    /** Bytes transferred so far (export) or read so far (restore). */
    val maintenanceSent: Long = 0,
    /** Expected total: uncompressed archive size for export, file size for restore. */
    val maintenanceTotal: Long = 0,

    /** WebDAV dialog: a test/upload/restore is running. */
    val webdavBusy: Boolean = false,
    /** Result line shown under the WebDAV fields. */
    val webdavMessage: String = "",
    val webdavMessageError: Boolean = false,
    /** Archives found on the server, newest first. */
    val webdavBackups: List<WebDavBackup> = emptyList(),
    /** A server is configured, so "sync now" can be offered outside the dialog. */
    val webdavReady: Boolean = false,

    /** `hideThinkingBlock` from settings.json: hide reasoning in the transcript. */
    val hideThinkingBlock: Boolean = false,

    /** Last turn was at level `off` yet the upstream still returned reasoning. */
    val upstreamIgnoresOff: Boolean = false,

    /** Background notifications per session state. */
    val notifyDone: Boolean = true,
    val notifyAttention: Boolean = true,
    val notifyError: Boolean = true,
    val notifyCompacted: Boolean = false,
    /** `system`, `silent`, `alarm` or `ringtone`. */
    val notifySound: String = "system",
    /** `provider/model` applied whenever a new session is created (empty = none). */
    val defaultModel: String = "",
    /** Settings dialog size: compact / standard / large / full. */
    val settingsSize: String = "standard",
    /** `compaction.reserveTokens` from settings.json (0 = unknown). */
    val compactionReserveTokens: Int = 0,
    /** `compaction.keepRecentTokens` from settings.json (0 = unknown). */
    val compactionKeepRecentTokens: Int = 0,
    /** `cacheWarming`: off / streaming / idle. */
    val cacheWarming: String = "",
    /** `retry.maxRetries` from settings.json. */
    val retryMaxRetries: Int = 0,
    val assetCount: Int = 0,
    val setup: Boolean = false,
    val setupHint: String? = null,
    /** The Termux-side installer is (meant to be) running: show staged progress. */
    val installRunning: Boolean = false,
    /** Wall clock of the last install start; drives the elapsed/progress text. */
    val installStartedAt: Long = 0L,
    /** Termux refused a RUN_COMMAND because allow-external-apps is off. */
    val termuxBlocked: Boolean = false,
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
    /** Older messages exist before the loaded window (pull at the top to get them). */
    val historyHasOlder: Boolean = false,
    /** A history page is in flight; an empty transcript shows a spinner instead of "no messages". */
    val historyLoading: Boolean = false,
    /** An *older* page is being pulled in (top pill), as opposed to a refresh. */
    val historyLoadingOlder: Boolean = false,
    /** Steering/follow-up messages still queued in pig. */
    val pendingMessages: Int = 0,
    /**
     * Contents of those queues, as pig reported them in `queue_update`.
     * Empty after a reconnect until the next queue change: pig's `get_state`
     * only carries a count, so the manager falls back to that count.
     */
    val queuedMessages: List<QueuedMessage> = emptyList(),
    /** Images picked in the composer and not sent yet. */
    val attachments: List<PendingImage> = emptyList(),
    /** Slash commands (built-ins + registered extension commands). */
    val commands: List<UiCommand> = emptyList(),
    /** A model switch is in flight; header shows the actual model until get_state confirms. */
    val modelSwitchPending: Boolean = false,
    val pendingModel: String = "",
)
