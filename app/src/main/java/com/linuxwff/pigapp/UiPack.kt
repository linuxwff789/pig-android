package com.linuxwff.pigapp

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

data class UiTheme(
    val background: Color = Color(0xFF0A0C12),
    val surface: Color = Color(0xFF12151E),
    val surfaceVariant: Color = Color(0xFF1A1F2B),
    val primary: Color = Color(0xFFA78BFA),
    val secondary: Color = Color(0xFF7DD3FC),
    val text: Color = Color(0xFFECEEF5),
    val textMuted: Color = Color(0xFF98A2B8),
    val danger: Color = Color(0xFFFB7185),
    val cornerRadius: Int = 18,
    /** End of the brand gradient (avatar, send button, accents). */
    val accent: Color = Color(0xFF22D3EE),
    /** Default for per-message avatar glow; a user setting overrides it. */
    val avatarShadow: Boolean = false,
    /** Default for the user message bubble shadow; a user setting overrides it. */
    val bubbleShadow: Boolean = true,
    val onPrimary: Color? = null,
    val onSecondary: Color? = null,
    val onAccent: Color? = null,
    val outline: Color? = null,
    val outlineVariant: Color? = null,
    val success: Color = Color(0xFF4ADE80),
    val warning: Color = Color(0xFFFBBF24),
    val scrim: Color = Color(0x9E000000),
    val bubbleStart: Color? = null,
    val bubbleEnd: Color? = null,
    val cardRadius: Int? = null,
    val bubbleRadius: Int? = null,
    val chipRadius: Int? = null,
    val composerRadius: Int? = null,
    val composerElevation: Int = 12,
    val bubbleElevation: Int = 8,
    val fabElevation: Int = 6,
    val bubbleStartAlpha: Float = 0.30f,
    val bubbleEndAlpha: Float = 0.16f,
    val bubbleBorderAlpha: Float = 0.32f,
    val cardBorderAlpha: Float = 0.6f,
    val chipAlpha: Float = 0.15f,
)

/** A named theme override in `skins.json`; absent fields inherit the base theme. */
data class UiSkin(
    val id: String,
    val name: String,
    val theme: UiTheme,
)

/** Flat key/value strings, e.g. "header.online" -> "在线". */
data class UiStrings(val values: Map<String, String> = emptyMap()) {
    fun get(key: String, fallback: String): String = values[key] ?: fallback
}

/** Visibility/size/format switches read by the built-in components. */
data class UiComponents(
    val showHeaderAvatar: Boolean = true,
    val headerAvatarSize: Int = 36,
    val showHeaderOnline: Boolean = true,
    val showDrawerBrand: Boolean = true,
    val showPanels: Boolean = true,
    val showSessions: Boolean = true,
    val showSessionBadges: Boolean = true,
    val showSessionSize: Boolean = true,
    val showCopy: Boolean = true,
    val showSelection: Boolean = true,
    val showSuggestions: Boolean = true,
    val showReadOnlyBanner: Boolean = true,
    val showPendingBanner: Boolean = true,
    val showSteerHint: Boolean = true,
    val showStatusBar: Boolean = true,
    val assistantAvatar: Boolean = true,
    val messageAvatarSize: Int = 24,
    val bubbleMaxWidthDp: Int = 520,
    val toolSummaryLines: Int = 4,
    val toolExpandedLines: Int = 200,
    val codeFenceMaxLines: Int = 400,
    val tableMaxLines: Int = 6,
    val composerButtonSize: Int = 46,
)

data class TypeScale(
    val body: Int = 15,
    val label: Int = 11,
    val h1: Int = 21,
    val h2: Int = 19,
    val h3: Int = 17,
    val h4: Int = 15,
    val h5: Int = 14,
    val h6: Int = 13,
    val code: Int = 12,
    val lineHeight: Float = 1.5f,
    /** Built-in name (`sans`/`serif`/`mono`/`cursive`) or `assets/fonts/x.ttf`. */
    val fontFamily: String = "sans",
    val headingFontFamily: String = "",
    val labelFontFamily: String = "mono",
    val codeFontFamily: String = "mono",
    val bodyWeight: Int = 400,
    val headingWeight: Int = 600,
    val labelWeight: Int = 500,
    val letterSpacing: Float = 0f,
)

/** One selectable settings-dialog size, in fractions of the usable screen. */
data class UiSettingsSize(
    val id: String,
    val label: String,
    val width: Float,
    val height: Float,
)

/** The four presets the app shipped before packs could declare their own. */
val defaultSettingsSizes = listOf(
    UiSettingsSize("compact", "紧凑", 0.78f, 0.60f),
    UiSettingsSize("standard", "标准", 0.92f, 0.82f),
    UiSettingsSize("large", "大", 0.97f, 0.94f),
    UiSettingsSize("full", "全屏", 1f, 1f),
)

/**
 * Settings-dialog geometry from `layout/chat.json`. A pack can either list
 * `sizes` (the app renders chips and remembers the user's pick) or give a
 * single `width`/`height`, in which case the dialog is fixed to that size.
 */
data class UiSettingsLayout(
    val defaultSize: String = "standard",
    val sizes: List<UiSettingsSize> = defaultSettingsSizes,
    val showSizeSwitcher: Boolean = true,
) {
    /** The entry to use for [id], falling back to the pack default, then first. */
    fun resolve(id: String): UiSettingsSize =
        sizes.firstOrNull { it.id == id }
            ?: sizes.firstOrNull { it.id == defaultSize }
            ?: sizes.firstOrNull()
            ?: defaultSettingsSizes[1]
}

data class UiLayout(
    val sidebarWidthDp: Int = 300,
    val assistantLabel: String = "PIG",
    val toolCards: String = "collapsed",
    /** Group consecutive same-name tool cards behind one collapsible header. */
    val toolGroups: Boolean = true,
    val showUsage: Boolean = true,
    val composerPlaceholder: String = "给 Pig 发消息…",
    val composerMaxLines: Int = 6,
    val sendOnEnter: Boolean = true,
    /** Composer surface/input appearance. */
    val composerFontSize: Int = 0,
    val composerTextColor: String = "onSurface",
    val composerMinHeight: Int = 0,
    val composerPaddingH: Int = 12,
    val composerPaddingV: Int = 10,
    val composerRadius: Int = -1,
    val composerInputRadius: Int = 22,
    val composerInputAlpha: Float = 0.35f,
    val composerInputBorderAlpha: Float = 0.5f,
    val composerElevation: Int = -1,
    val composerSurfaceAlpha: Float = 1f,
    val composerButtonIconSize: Int = 0,
    /** Chat transcript appearance. */
    val chatPaddingH: Int = 14,
    val chatPaddingV: Int = 10,
    val chatBottomPadding: Int = 16,
    val messageSpacing: Int = 14,
    val emptyAvatarSize: Int = 64,
    val codeBlockRadius: Int = -1,
    val codeBlockBorderAlpha: Float = 0.35f,
    val composerPlaceholderColor: String = "onSurfaceVariant",
    /** Drawer appearance (no behaviour switches). */
    val drawer: UiDrawerAppearance = UiDrawerAppearance(),
    val bubble: UiBubbleAppearance = UiBubbleAppearance(),
    val toolCard: UiToolCardAppearance = UiToolCardAppearance(),
    val thinking: UiThinkingAppearance = UiThinkingAppearance(),
    /** Skin switcher: show, where and how (chips/list). */
    val showSkinSwitcher: Boolean = true,
    val skinSwitcherPlacement: String = "drawer",
    val skinSwitcherStyle: String = "chips",
    /** UI pack manager (built-in relay pack + imported local packs). */
    val showPackManager: Boolean = true,
    val packManagerPlacement: String = "both",
    /** Optional fully custom header; null keeps the built-in header. */
    val header: UiHeader? = null,
    /** Settings-dialog size presets (or one fixed size). */
    val settings: UiSettingsLayout = UiSettingsLayout(),
    /** RPC data sources, slot bindings and named safe actions. */
    val dataSources: Map<String, UiDataSource> = emptyMap(),
    val bindings: Map<String, UiBinding> = emptyMap(),
    val actions: Map<String, UiAction> = emptyMap(),
)

data class UiPack(
    val name: String = "Pig default",
    val version: Int = 1,
    val theme: UiTheme = UiTheme(),
    val layout: UiLayout = UiLayout(),
    val types: TypeScale = TypeScale(),
    val strings: UiStrings = UiStrings(),
    val components: UiComponents = UiComponents(),
    val icons: Map<String, String> = emptyMap(),
    val nav: List<NavItem> = emptyList(),
    val skins: List<UiSkin> = emptyList(),
    val defaultSkin: String = "",
    /** Materialized pack fonts: asset path -> cache file path. */
    val fontFiles: Map<String, String> = emptyMap(),
    val files: Int = 0,
    val loadedAt: Long = 0L,
)

/** Resolve a skin id to a pack whose theme is the selected skin (base if unknown). */
fun UiPack.withSkin(id: String?): UiPack {
    val skin = skins.firstOrNull { it.id == id } ?: return this
    return copy(theme = skin.theme)
}

/** Parses the pack pushed by pig-relay: manifest + theme/colors + layout/chat. */
object UiPackParser {
    data class Result(val pack: UiPack?, val warnings: List<String>)

    fun parse(files: Map<String, String>): Result {
        val warnings = mutableListOf<String>()
        val manifestText = files["manifest.json"]
            ?: return Result(null, listOf("manifest.json 缺失"))
        val manifest = try {
            JSONObject(manifestText)
        } catch (e: Exception) {
            return Result(null, listOf("manifest.json 解析失败: ${e.message}"))
        }
        val name = manifest.optString("name", "UI pack").ifBlank { "UI pack" }
        val themePath = manifest.optString("theme", "theme/colors.json")
        val layoutPath = manifest.optString("layout", "layout/chat.json")
        val typographyPath = manifest.optString("typography", "theme/typography.json")

        var theme = UiTheme()
        files[themePath]?.let { theme = parseTheme(it, warnings) }
            ?: warnings.add("$themePath 缺失，使用默认主题")

        var skins = emptyList<UiSkin>()
        var defaultSkin = ""
        files["skins.json"]?.let { text ->
            val parsed = parseSkins(text, theme, warnings)
            skins = parsed.second
            defaultSkin = parsed.first
        }

        var layout = UiLayout()
        files[layoutPath]?.let { layout = parseLayout(it, warnings) }
            ?: warnings.add("$layoutPath 缺失，使用默认布局")

        var types = TypeScale()
        files[typographyPath]?.let { types = parseTypes(it, warnings) }

        val strings = files["strings.json"]?.let { parseStrings(it, warnings) } ?: UiStrings()
        val components = files["components.json"]?.let { parseComponents(it, warnings) } ?: UiComponents()
        val icons = files["icons.json"]?.let { parseIcons(it, warnings) } ?: emptyMap()
        val nav = files["nav.json"]?.let { parseNav(it, warnings) } ?: emptyList()

        return Result(
            UiPack(
                name = name,
                version = manifest.optInt("version", 1),
                theme = theme,
                layout = layout,
                types = types,
                strings = strings,
                components = components,
                icons = icons,
                nav = nav,
                skins = skins,
                defaultSkin = defaultSkin,
                files = files.size,
                loadedAt = System.currentTimeMillis(),
            ),
            warnings,
        )
    }

    /**
     * `nav.json` adds buttons to the drawer: `{"items":[{"id","label","window","command","icon"}]}`.
     * Tapping an item opens the cached window and runs the command to refresh it.
     */
    /** Window ids referenced by `nav.json`; these are opened on demand, not on pack load. */
    fun navWindowIds(files: Map<String, String>): Set<String> {
        val text = files["nav.json"] ?: return emptySet()
        return parseNav(text, mutableListOf())
            .mapNotNull { it.window.takeIf { window -> window.isNotBlank() } }
            .toSet()
    }

    private fun parseNav(text: String, warnings: MutableList<String>): List<NavItem> {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            warnings.add("nav.json 解析失败: ${e.message}")
            return emptyList()
        }
        val items = json.optJSONArray("items") ?: return emptyList()
        val out = mutableListOf<NavItem>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val window = item.optString("window").take(40)
            if (!Regex("^[a-z0-9._-]{1,40}$").matches(window)) {
                warnings.add("nav.json 第 ${index + 1} 项 window 无效")
                continue
            }
            val id = item.optString("id", window).ifBlank { window }.take(40)
            val label = item.optString("label", id).ifBlank { id }.take(40)
            val command = item.optString("command").trim().removePrefix("/").take(80)
            val placement = if (item.optString("placement", "drawer").trim().lowercase() == "settings") {
                "settings"
            } else {
                "drawer"
            }
            out.add(
                NavItem(
                    id = id,
                    label = label,
                    window = window,
                    command = command,
                    icon = item.optString("icon").take(20),
                    placement = placement,
                    action = item.optString("action").trim().take(120),
                ),
            )
        }
        return out
    }

    private fun parseTheme(
        text: String,
        warnings: MutableList<String>,
        base: UiTheme = UiTheme(),
    ): UiTheme {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            warnings.add("theme JSON 解析失败: ${e.message}")
            return base
        }
        val d = base
        return UiTheme(
            background = json.color("background", d.background, warnings),
            surface = json.color("surface", d.surface, warnings),
            surfaceVariant = json.color("surfaceVariant", d.surfaceVariant, warnings),
            primary = json.color("primary", d.primary, warnings),
            secondary = json.color("secondary", d.secondary, warnings),
            text = json.color("text", d.text, warnings),
            textMuted = json.color("textMuted", d.textMuted, warnings),
            danger = json.color("danger", d.danger, warnings),
            cornerRadius = json.optInt("cornerRadius", d.cornerRadius).coerceIn(0, 40),
            accent = json.color("accent", d.accent, warnings),
            avatarShadow = json.optBoolean("avatarShadow", d.avatarShadow),
            bubbleShadow = json.optBoolean("bubbleShadow", d.bubbleShadow),
            onPrimary = json.colorOrNull("onPrimary", warnings) ?: d.onPrimary,
            onSecondary = json.colorOrNull("onSecondary", warnings) ?: d.onSecondary,
            onAccent = json.colorOrNull("onAccent", warnings) ?: d.onAccent,
            outline = json.colorOrNull("outline", warnings) ?: d.outline,
            outlineVariant = json.colorOrNull("outlineVariant", warnings) ?: d.outlineVariant,
            success = json.color("success", d.success, warnings),
            warning = json.color("warning", d.warning, warnings),
            scrim = json.color("scrim", d.scrim, warnings),
            bubbleStart = json.colorOrNull("bubbleStart", warnings) ?: d.bubbleStart,
            bubbleEnd = json.colorOrNull("bubbleEnd", warnings) ?: d.bubbleEnd,
            cardRadius = json.optInt("cardRadius", d.cardRadius ?: -1).takeIf { it >= 0 }?.coerceIn(0, 40),
            bubbleRadius = json.optInt("bubbleRadius", d.bubbleRadius ?: -1).takeIf { it >= 0 }?.coerceIn(0, 40),
            chipRadius = json.optInt("chipRadius", d.chipRadius ?: -1).takeIf { it >= 0 }?.coerceIn(0, 40),
            composerRadius = json.optInt("composerRadius", d.composerRadius ?: -1).takeIf { it >= 0 }?.coerceIn(0, 40),
            composerElevation = json.optInt("composerElevation", d.composerElevation).coerceIn(0, 40),
            bubbleElevation = json.optInt("bubbleElevation", d.bubbleElevation).coerceIn(0, 40),
            fabElevation = json.optInt("fabElevation", d.fabElevation).coerceIn(0, 40),
            bubbleStartAlpha = json.optDouble("bubbleStartAlpha", d.bubbleStartAlpha.toDouble()).toFloat().coerceIn(0f, 1f),
            bubbleEndAlpha = json.optDouble("bubbleEndAlpha", d.bubbleEndAlpha.toDouble()).toFloat().coerceIn(0f, 1f),
            bubbleBorderAlpha = json.optDouble("bubbleBorderAlpha", d.bubbleBorderAlpha.toDouble()).toFloat().coerceIn(0f, 1f),
            cardBorderAlpha = json.optDouble("cardBorderAlpha", d.cardBorderAlpha.toDouble()).toFloat().coerceIn(0f, 1f),
            chipAlpha = json.optDouble("chipAlpha", d.chipAlpha.toDouble()).toFloat().coerceIn(0f, 1f),
        )
    }

    private fun parseTypes(text: String, warnings: MutableList<String>): TypeScale {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            warnings.add("typography JSON 解析失败: ${e.message}")
            return TypeScale()
        }
        val d = TypeScale()
        fun size(key: String, fallback: Int): Int {
            val value = json.optInt(key, fallback)
            if (value !in 8..40) {
                warnings.add("typography.$key=$value 超出 8..40")
                return fallback
            }
            return value
        }
        fun weight(key: String, fallback: Int): Int {
            return when (val raw = json.opt(key)) {
                is Number -> raw.toInt()
                is String -> when (raw.trim().lowercase()) {
                    "thin" -> 100
                    "light" -> 300
                    "normal", "regular" -> 400
                    "medium" -> 500
                    "semibold", "semi-bold" -> 600
                    "bold" -> 700
                    "black" -> 900
                    else -> raw.toIntOrNull() ?: fallback
                }
                else -> fallback
            }.coerceIn(100, 900)
        }
        return TypeScale(
            body = size("body", d.body),
            label = size("label", d.label),
            h1 = size("h1", d.h1),
            h2 = size("h2", d.h2),
            h3 = size("h3", d.h3),
            h4 = size("h4", d.h4),
            h5 = size("h5", d.h5),
            h6 = size("h6", d.h6),
            code = size("code", d.code),
            lineHeight = json.optDouble("lineHeight", d.lineHeight.toDouble())
                .toFloat().coerceIn(1.0f, 2.5f),
            fontFamily = json.optString("fontFamily", d.fontFamily)
                .trim().ifBlank { d.fontFamily },
            headingFontFamily = json.optString("headingFontFamily", "")
                .trim(),
            labelFontFamily = json.optString("labelFontFamily", d.labelFontFamily)
                .trim().ifBlank { d.labelFontFamily },
            codeFontFamily = json.optString("codeFontFamily", d.codeFontFamily)
                .trim().ifBlank { d.codeFontFamily },
            bodyWeight = weight("bodyWeight", d.bodyWeight),
            headingWeight = weight("headingWeight", d.headingWeight),
            labelWeight = weight("labelWeight", d.labelWeight),
            letterSpacing = json.optDouble("letterSpacing", d.letterSpacing.toDouble())
                .toFloat().coerceIn(-2f, 4f),
        )
    }

    private fun parseLayout(text: String, warnings: MutableList<String>): UiLayout {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            warnings.add("layout JSON 解析失败: ${e.message}")
            return UiLayout()
        }
        val d = UiLayout()
        val sidebar = json.optJSONObject("sidebar")
        val chat = json.optJSONObject("chat")
        val composer = json.optJSONObject("composer")
        val drawerJson = json.optJSONObject("drawer")
        val bubbleJson = chat?.optJSONObject("bubble")
        val toolCardJson = chat?.optJSONObject("toolCard")
        val skinSwitcher = json.optJSONObject("skinSwitcher")
        val packManager = json.optJSONObject("packManager")
        val header = json.optJSONObject("header")?.let { parseHeader(it, warnings) }
        val dataSources = parseDataSources(json.optJSONObject("dataSources"), warnings)
        val bindings = parseBindings(json.optJSONObject("bindings"), warnings)
        val actions = parseActions(json.optJSONObject("actions"), warnings)
        val modes = setOf("collapsible", "expanded", "collapsed")
        val mode = chat?.optString("toolCards", d.toolCards) ?: d.toolCards
        if (mode !in modes) warnings.add("chat.toolCards=$mode 无效")
        val placements = setOf("drawer", "settings", "both")
        val placement = skinSwitcher?.optString("placement", d.skinSwitcherPlacement)
            ?: d.skinSwitcherPlacement
        if (placement !in placements) warnings.add("skinSwitcher.placement=$placement 无效")
        val styles = setOf("chips", "list")
        val skinStyle = skinSwitcher?.optString("style", d.skinSwitcherStyle)
            ?: d.skinSwitcherStyle
        if (skinStyle !in styles) warnings.add("skinSwitcher.style=$skinStyle 无效")
        val packPlacement = packManager?.optString("placement", d.packManagerPlacement)
            ?: d.packManagerPlacement
        if (packPlacement !in placements) warnings.add("packManager.placement=$packPlacement 无效")
        val settingsLayout = parseSettingsLayout(json.optJSONObject("settings"), warnings)

        return UiLayout(
            sidebarWidthDp = (sidebar?.optInt("widthDp", d.sidebarWidthDp)
                ?: d.sidebarWidthDp).coerceIn(240, 560),
            assistantLabel = chat?.optString("assistantLabel", d.assistantLabel)
                ?.takeIf { it.isNotBlank() } ?: d.assistantLabel,
            toolCards = mode.takeIf { it in modes } ?: d.toolCards,
            toolGroups = chat?.optBoolean("toolGroups", d.toolGroups) ?: d.toolGroups,
            showUsage = chat?.optBoolean("showUsage", d.showUsage) ?: d.showUsage,
            composerPlaceholder = composer?.optString("placeholder", d.composerPlaceholder)
                ?.takeIf { it.isNotBlank() } ?: d.composerPlaceholder,
            composerMaxLines = (composer?.optInt("maxLines", d.composerMaxLines)
                ?: d.composerMaxLines).coerceIn(1, 12),
            sendOnEnter = composer?.optBoolean("sendOnEnter", d.sendOnEnter) ?: d.sendOnEnter,
            composerFontSize = (composer?.optInt("fontSize", d.composerFontSize)
                ?: d.composerFontSize).coerceIn(0, 32),
            composerTextColor = composer?.optString("textColor", d.composerTextColor)
                ?.takeIf { it.isNotBlank() } ?: d.composerTextColor,
            composerMinHeight = (composer?.optInt("minHeight", d.composerMinHeight)
                ?: d.composerMinHeight).coerceIn(0, 200),
            composerPaddingH = (composer?.optInt("paddingH", d.composerPaddingH)
                ?: d.composerPaddingH).coerceIn(0, 48),
            composerPaddingV = (composer?.optInt("paddingV", d.composerPaddingV)
                ?: d.composerPaddingV).coerceIn(0, 48),
            composerRadius = (composer?.optInt("radius", d.composerRadius)
                ?: d.composerRadius).coerceIn(-1, 40),
            composerInputRadius = (composer?.optInt("inputRadius", d.composerInputRadius)
                ?: d.composerInputRadius).coerceIn(0, 40),
            composerInputAlpha = (composer?.optDouble(
                "inputAlpha", d.composerInputAlpha.toDouble(),
            ) ?: d.composerInputAlpha.toDouble()).toFloat().coerceIn(0f, 1f),
            composerInputBorderAlpha = (composer?.optDouble(
                "borderAlpha", d.composerInputBorderAlpha.toDouble(),
            ) ?: d.composerInputBorderAlpha.toDouble()).toFloat().coerceIn(0f, 1f),
            composerElevation = (composer?.optInt("elevation", d.composerElevation)
                ?: d.composerElevation).coerceIn(-1, 40),
            composerSurfaceAlpha = (composer?.optDouble(
                "surfaceAlpha", d.composerSurfaceAlpha.toDouble(),
            ) ?: d.composerSurfaceAlpha.toDouble()).toFloat().coerceIn(0f, 1f),
            composerButtonIconSize = (composer?.optInt("buttonIconSize", d.composerButtonIconSize)
                ?: d.composerButtonIconSize).coerceIn(0, 32),
            chatPaddingH = (chat?.optInt("paddingH", d.chatPaddingH) ?: d.chatPaddingH)
                .coerceIn(0, 64),
            chatPaddingV = (chat?.optInt("paddingV", d.chatPaddingV) ?: d.chatPaddingV)
                .coerceIn(0, 64),
            chatBottomPadding = (chat?.optInt("bottomPadding", d.chatBottomPadding)
                ?: d.chatBottomPadding).coerceIn(0, 128),
            messageSpacing = (chat?.optInt("messageSpacing", d.messageSpacing)
                ?: d.messageSpacing).coerceIn(0, 64),
            emptyAvatarSize = (chat?.optInt("emptyAvatarSize", d.emptyAvatarSize)
                ?: d.emptyAvatarSize).coerceIn(16, 128),
            codeBlockRadius = (chat?.optInt("codeBlockRadius", d.codeBlockRadius)
                ?: d.codeBlockRadius).coerceIn(-1, 40),
            codeBlockBorderAlpha = chat?.optDouble(
                "codeBlockBorderAlpha",
                d.codeBlockBorderAlpha.toDouble(),
            )?.toFloat()?.coerceIn(0f, 1f) ?: d.codeBlockBorderAlpha,
            composerPlaceholderColor = composer?.optString(
                "placeholderColor",
                d.composerPlaceholderColor,
            )?.takeIf { it.isNotBlank() } ?: d.composerPlaceholderColor,
            drawer = parseDrawer(drawerJson, warnings),
            bubble = parseBubble(bubbleJson, warnings),
            toolCard = parseToolCard(toolCardJson, warnings),
            thinking = parseThinking(chat?.optJSONObject("thinking"), warnings),
            showSkinSwitcher = skinSwitcher?.optBoolean("show", d.showSkinSwitcher)
                ?: d.showSkinSwitcher,
            skinSwitcherPlacement = placement.takeIf { it in placements } ?: d.skinSwitcherPlacement,
            skinSwitcherStyle = skinStyle.takeIf { it in styles } ?: d.skinSwitcherStyle,
            showPackManager = packManager?.optBoolean("show", d.showPackManager)
                ?: d.showPackManager,
            packManagerPlacement = packPlacement.takeIf { it in placements }
                ?: d.packManagerPlacement,
            header = header,
            settings = settingsLayout,
            dataSources = dataSources,
            bindings = bindings,
            actions = actions,
        )
    }

    /**
     * `skins.json`: `{"default":"id","skins":[{"id","name","theme":{...}}]}`.
     * Each skin's theme inherits every field it does not override from the base
     * theme; an unknown id or a duplicate is skipped.
     */
    private fun parseSkins(
        text: String,
        base: UiTheme,
        warnings: MutableList<String>,
    ): Pair<String, List<UiSkin>> {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            warnings.add("skins.json 解析失败: ${e.message}")
            return "" to emptyList()
        }
        val out = mutableListOf<UiSkin>()
        val seen = mutableSetOf<String>()
        val array = json.optJSONArray("skins") ?: return "" to emptyList()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            if (id.isBlank()) {
                warnings.add("skins[$index] 缺少 id")
                continue
            }
            if (!seen.add(id)) {
                warnings.add("skins[$index] 重复 id: $id")
                continue
            }
            val theme = item.optJSONObject("theme")?.let {
                parseTheme(it.toString(), warnings, base)
            } ?: base
            out.add(UiSkin(id = id, name = item.optString("name").ifBlank { id }, theme = theme))
        }
        val requested = json.optString("default").trim()
        val default = out.firstOrNull { it.id == requested }?.id
            ?: out.firstOrNull()?.id
            ?: ""
        if (requested.isNotBlank() && requested != default) {
            warnings.add("skins.default=$requested 不存在，使用 $default")
        }
        return default to out
    }

    private fun parseBubble(json: JSONObject?, warnings: MutableList<String>): UiBubbleAppearance {
        if (json == null) return UiBubbleAppearance()
        val d = UiBubbleAppearance()
        return UiBubbleAppearance(
            paddingH = json.optInt("paddingH", d.paddingH).coerceIn(0, 40),
            paddingV = json.optInt("paddingV", d.paddingV).coerceIn(0, 40),
            borderWidth = json.optInt("borderWidth", d.borderWidth).coerceIn(0, 4),
            textColor = json.optString("textColor", d.textColor),
            assistantBubble = json.optBoolean("assistantBubble", d.assistantBubble),
            assistantBackgroundAlpha = json.optDouble(
                "assistantBackgroundAlpha",
                d.assistantBackgroundAlpha.toDouble(),
            ).toFloat().coerceIn(0f, 1f),
            assistantBorderAlpha = json.optDouble(
                "assistantBorderAlpha",
                d.assistantBorderAlpha.toDouble(),
            ).toFloat().coerceIn(0f, 1f),
            assistantTextColor = json.optString("assistantTextColor", d.assistantTextColor),
        )
    }

    private fun parseThinking(
        json: JSONObject?,
        warnings: MutableList<String>,
    ): UiThinkingAppearance {
        if (json == null) return UiThinkingAppearance()
        val d = UiThinkingAppearance()
        val backgrounds = setOf("surface", "surfaceVariant", "background", "transparent")
        val background = json.optString("background", d.background)
        if (background !in backgrounds) warnings.add("chat.thinking.background=$background 无效")
        return UiThinkingAppearance(
            show = json.optBoolean("show", d.show),
            collapsedByDefault = json.optBoolean("collapsedByDefault", d.collapsedByDefault),
            expandWhileStreaming = json.optBoolean("expandWhileStreaming", d.expandWhileStreaming),
            showCount = json.optBoolean("showCount", d.showCount),
            title = json.optString("title", d.title).trim(),
            fontSize = json.optInt("fontSize", d.fontSize).coerceIn(0, 24),
            italic = json.optBoolean("italic", d.italic),
            background = background.takeIf { it in backgrounds } ?: d.background,
            backgroundAlpha = json.optDouble("backgroundAlpha", d.backgroundAlpha.toDouble())
                .toFloat().coerceIn(0f, 1f),
            titleColor = json.optString("titleColor", d.titleColor)
                .takeIf { it.isNotBlank() } ?: d.titleColor,
            textColor = json.optString("textColor", d.textColor)
                .takeIf { it.isNotBlank() } ?: d.textColor,
            radius = json.optInt("radius", d.radius)
                .let { if (it < 0) -1 else it.coerceIn(0, 40) },
            paddingH = json.optInt("paddingH", d.paddingH).coerceIn(0, 40),
            paddingV = json.optInt("paddingV", d.paddingV).coerceIn(0, 40),
            maxHeight = json.optInt("maxHeight", d.maxHeight).coerceIn(60, 800),
            collapsedGlyph = json.optString("collapsedGlyph", d.collapsedGlyph),
            expandedGlyph = json.optString("expandedGlyph", d.expandedGlyph),
        )
    }

    private fun parseToolCard(
        json: JSONObject?,
        warnings: MutableList<String>,
    ): UiToolCardAppearance {
        if (json == null) return UiToolCardAppearance()
        val d = UiToolCardAppearance()
        val backgrounds = setOf("surface", "surfaceVariant", "background", "transparent")
        val background = json.optString("background", d.background)
        if (background !in backgrounds) warnings.add("chat.toolCard.background=$background 无效")
        return UiToolCardAppearance(
            background = background.takeIf { it in backgrounds } ?: d.background,
            backgroundAlpha = json.optDouble("backgroundAlpha", d.backgroundAlpha.toDouble())
                .toFloat().coerceIn(0f, 1f),
            radius = json.optInt("radius", d.radius)
                .let { if (it < 0) -1 else it.coerceIn(0, 40) },
            borderWidth = json.optInt("borderWidth", d.borderWidth).coerceIn(0, 4),
            borderAlpha = json.optDouble("borderAlpha", d.borderAlpha.toDouble())
                .toFloat().coerceIn(-1f, 1f),
            padding = json.optInt("padding", d.padding).coerceIn(0, 40),
            collapsedPaddingH = json.optInt("collapsedPaddingH", d.collapsedPaddingH)
                .coerceIn(0, 40),
            collapsedPaddingV = json.optInt("collapsedPaddingV", d.collapsedPaddingV)
                .coerceIn(0, 40),
            iconBoxSize = json.optInt("iconBoxSize", d.iconBoxSize).coerceIn(12, 64),
            iconSize = json.optInt("iconSize", d.iconSize).coerceIn(8, 40),
            iconRadius = json.optInt("iconRadius", d.iconRadius).coerceIn(0, 24),
            iconAlpha = json.optDouble("iconAlpha", d.iconAlpha.toDouble())
                .toFloat().coerceIn(0f, 1f),
            titleSize = json.optInt("titleSize", d.titleSize).coerceIn(0, 32),
            metricsColor = json.optString("metricsColor", d.metricsColor),
            chipRadius = json.optInt("chipRadius", d.chipRadius).coerceIn(0, 24),
            chipAlpha = json.optDouble("chipAlpha", d.chipAlpha.toDouble())
                .toFloat().coerceIn(0f, 1f),
            groupRadius = json.optInt("groupRadius", d.groupRadius)
                .let { if (it < 0) -1 else it.coerceIn(0, 40) },
            groupPadding = json.optInt("groupPadding", d.groupPadding).coerceIn(0, 40),
        )
    }

    /**
     * `settings` in layout/chat.json:
     *
     * ```json
     * "settings": {
     *   "defaultSize": "standard",
     *   "sizes": [
     *     {"id": "compact", "label": "紧凑", "width": 0.78, "height": 0.6},
     *     {"id": "wide", "width": 0.98, "height": 0.9}
     *   ]
     * }
     * ```
     *
     * `width`/`height` alone pin one size (no chips). Fractions are clamped to
     * 0.3–1.0 of the usable screen.
     */
    private fun parseSettingsLayout(
        json: JSONObject?,
        warnings: MutableList<String>,
    ): UiSettingsLayout {
        val d = UiSettingsLayout()
        if (json == null) return d
        fun fraction(key: String, fallback: Float): Float =
            json.optDouble(key, fallback.toDouble()).toFloat().coerceIn(0.3f, 1f)

        val array = json.optJSONArray("sizes")
        val sizes = mutableListOf<UiSettingsSize>()
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                if (id.isBlank()) {
                    warnings.add("settings.sizes[$index] 缺少 id")
                    continue
                }
                if (sizes.any { it.id == id }) {
                    warnings.add("settings.sizes 重复 id: $id")
                    continue
                }
                sizes.add(
                    UiSettingsSize(
                        id = id,
                        label = item.optString("label").trim(),
                        width = item.optDouble("width", 0.92).toFloat().coerceIn(0.3f, 1f),
                        height = item.optDouble("height", 0.82).toFloat().coerceIn(0.3f, 1f),
                    ),
                )
            }
        }
        if (sizes.isEmpty() && (json.has("width") || json.has("height"))) {
            // A single fixed size: no chips, the dialog is always this big.
            return UiSettingsLayout(
                defaultSize = "pack",
                sizes = listOf(
                    UiSettingsSize(
                        "pack",
                        json.optString("label").trim(),
                        fraction("width", 0.92f),
                        fraction("height", 0.82f),
                    ),
                ),
                showSizeSwitcher = false,
            )
        }
        if (sizes.isEmpty()) return d
        val defaultSize = json.optString("defaultSize", d.defaultSize).trim()
        if (defaultSize.isNotBlank() && sizes.none { it.id == defaultSize }) {
            warnings.add("settings.defaultSize=$defaultSize 不在 sizes 中")
        }
        return UiSettingsLayout(
            defaultSize = defaultSize.takeIf { id -> sizes.any { it.id == id } } ?: sizes[0].id,
            sizes = sizes,
            showSizeSwitcher = json.optBoolean("showSizeSwitcher", sizes.size > 1),
        )
    }

    private fun parseDrawer(json: JSONObject?, warnings: MutableList<String>): UiDrawerAppearance {
        if (json == null) return UiDrawerAppearance()
        val d = UiDrawerAppearance()
        val backgrounds = setOf("theme", "surface", "gradient", "transparent")
        val background = json.optString("background", d.background)
        if (background !in backgrounds) warnings.add("drawer.background=$background 无效")
        fun colorRadius(key: String): Int {
            val value = json.optInt(key, d.cornerRadius)
            return if (value < 0) -1 else value.coerceIn(0, 40)
        }
        val budget = json.optInt("buttonHeight", d.buttonHeight).coerceIn(0, 96)
        return UiDrawerAppearance(
            background = background.takeIf { it in backgrounds } ?: d.background,
            gradientAlpha = json.optDouble("gradientAlpha", d.gradientAlpha.toDouble())
                .toFloat().coerceIn(0f, 1f),
            cornerRadius = colorRadius("cornerRadius"),
            paddingH = json.optInt("paddingH", d.paddingH).coerceIn(0, 48),
            paddingV = json.optInt("paddingV", d.paddingV).coerceIn(0, 48),
            spacing = json.optInt("spacing", d.spacing).coerceIn(0, 40),
            brandAvatarSize = json.optInt("brandAvatarSize", d.brandAvatarSize).coerceIn(16, 96),
            brandTagline = json.optBoolean("brandTagline", d.brandTagline),
            brandCardAlpha = json.optDouble("brandCardAlpha", d.brandCardAlpha.toDouble())
                .toFloat().coerceIn(0f, 1f),
            brandPills = json.optBoolean("brandPills", d.brandPills),
            buttonHeight = budget,
            buttonRadius = json.optInt("buttonRadius", d.buttonRadius)
                .let { if (it < 0) -1 else it.coerceIn(0, 40) },
            buttonIconSize = json.optInt("buttonIconSize", d.buttonIconSize).coerceIn(8, 32),
            sectionLabelSize = json.optInt("sectionLabelSize", d.sectionLabelSize).coerceIn(0, 24),
            sectionLabelUppercase = json.optBoolean(
                "sectionLabelUppercase",
                d.sectionLabelUppercase,
            ),
            sessionRowRadius = json.optInt("sessionRowRadius", d.sessionRowRadius)
                .let { if (it < 0) -1 else it.coerceIn(0, 40) },
            sessionSelectedAlpha = json.optDouble(
                "sessionSelectedAlpha",
                d.sessionSelectedAlpha.toDouble(),
            ).toFloat().coerceIn(0f, 1f),
            showSessionId = json.optBoolean("showSessionId", d.showSessionId),
            showSessionTime = json.optBoolean("showSessionTime", d.showSessionTime),
            searchRadius = json.optInt("searchRadius", d.searchRadius)
                .let { if (it < 0) -1 else it.coerceIn(0, 40) },
            modules = parseDrawerModules(json.opt("modules"), warnings) ?: d.modules,
        )
    }

    private fun parseDrawerModules(
        raw: Any?,
        warnings: MutableList<String>,
    ): List<String>? {
        val allowed = setOf("brand", "newSession", "skinSwitcher", "packManager", "panels")
        val names = when (raw) {
            is String -> listOf(raw)
            is org.json.JSONArray -> (0 until raw.length()).map { raw.optString(it) }
            else -> return null
        }
        val out = mutableListOf<String>()
        names.forEach { name ->
            val clean = name.trim()
            if (clean in allowed) {
                if (clean !in out) out.add(clean)
            } else {
                warnings.add("drawer.modules 忽略: $clean（sessions/settings 固定保留）")
            }
        }
        return out
    }

    private fun parseHeader(json: JSONObject, warnings: MutableList<String>): UiHeader {
        val d = UiHeader()
        val backgrounds = setOf("gradient", "surface", "transparent")
        val background = json.optString("background", d.background)
        if (background !in backgrounds) warnings.add("header.background=$background 无效")
        val rows = mutableListOf<UiHeaderRow>()
        json.optJSONArray("rows")?.let { array ->
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val align = row.optString("align", "start")
                if (align !in setOf("start", "center", "end")) {
                    warnings.add("header.rows[$index].align=$align 无效")
                }
                val elements = mutableListOf<UiHeaderElement>()
                row.optJSONArray("elements")?.let { items ->
                    for (e in 0 until items.length()) {
                        val item = items.optJSONObject(e) ?: continue
                        val type = item.optString("type").trim()
                        if (type.isBlank()) {
                            warnings.add("header.rows[$index].elements[$e] 缺少 type")
                            continue
                        }
                        val condition = item.optString("when", "always")
                        if (condition !in setOf("always", "busy", "idle", "connected", "offline", "streaming", "compacting")) {
                            warnings.add("header element $type when=$condition 无效")
                        }
                        val color = item.optString("color", "onSurfaceVariant")
                        if (color !in setOf("primary", "secondary", "tertiary", "onSurface", "onSurfaceVariant", "error", "success", "warning")) {
                            warnings.add("header element $type color=$color 无效")
                        }
                        elements.add(
                            UiHeaderElement(
                                type = type,
                                visible = item.optBoolean("visible", true),
                                condition = condition,
                                size = item.optInt("size", 0).coerceIn(0, 96),
                                weight = item.optDouble("weight", 0.0).toFloat().coerceIn(0f, 10f),
                                padding = item.optInt("padding", 0).coerceIn(0, 32),
                                icon = item.optString("icon").take(32),
                                label = item.optString("label").take(128),
                                text = item.optString("text").take(256),
                                style = item.optString("style", "label"),
                                color = color,
                                bind = item.optString("bind").trim().take(120),
                                format = item.optString("format").take(256),
                                action = item.optString("action").trim().take(120),
                                avatar = item.optBoolean("avatar", true),
                                avatarSize = item.optInt("avatarSize", 0).coerceIn(0, 96),
                                online = item.optBoolean("online", true),
                                lines = item.optInt("lines", 2).coerceIn(1, 4),
                            ),
                        )
                    }
                }
                rows.add(
                    UiHeaderRow(
                        spacingDp = row.optInt("spacing", 6).coerceIn(0, 32),
                        align = align.takeIf { it in setOf("start", "center", "end") } ?: "start",
                        heightDp = row.optInt("height", 0).coerceIn(0, 120),
                        elements = elements,
                    ),
                )
            }
        }
        val body = json.optJSONObject("body")?.let {
            AppUiParser.parseNode(it, warnings)
        }
        return UiHeader(
            show = json.optBoolean("show", d.show),
            heightDp = json.optInt("height", d.heightDp).coerceIn(0, 200),
            paddingH = json.optInt("paddingH", d.paddingH).coerceIn(0, 48),
            paddingV = json.optInt("paddingV", d.paddingV).coerceIn(0, 32),
            background = background.takeIf { it in backgrounds } ?: d.background,
            gradientAlpha = json.optDouble("gradientAlpha", d.gradientAlpha.toDouble())
                .toFloat().coerceIn(0f, 1f),
            divider = json.optBoolean("divider", d.divider),
            body = body,
            rows = rows,
        )
    }

    private fun parseDataSources(
        json: JSONObject?,
        warnings: MutableList<String>,
    ): Map<String, UiDataSource> {
        if (json == null) return emptyMap()
        val triggers = setOf("connect", "agent_end", "compaction_end", "session_switch", "pack_load", "window_open")
        val out = mutableMapOf<String, UiDataSource>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val item = json.optJSONObject(name) ?: continue
            val rpc = item.optString("rpc").trim()
            if (rpc.isBlank()) {
                warnings.add("dataSources.$name 缺少 rpc")
                continue
            }
            val refresh = mutableSetOf<String>()
            when (val raw = item.opt("refresh")) {
                is String -> if (raw.isNotBlank()) refresh.add(raw.trim())
                is org.json.JSONArray -> for (i in 0 until raw.length()) {
                    raw.optString(i).trim().takeIf { it.isNotBlank() }?.let(refresh::add)
                }
            }
            val invalid = refresh - triggers
            if (invalid.isNotEmpty()) warnings.add("dataSources.$name.refresh 无效: $invalid")
            out[name] = UiDataSource(
                rpc = rpc,
                params = item.optJSONObject("params"),
                refresh = refresh - invalid,
            )
        }
        return out
    }

    private fun parseBindings(
        json: JSONObject?,
        warnings: MutableList<String>,
    ): Map<String, UiBinding> {
        if (json == null) return emptyMap()
        val out = mutableMapOf<String, UiBinding>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val slot = keys.next()
            when (val raw = json.opt(slot)) {
                is String -> if (raw.isNotBlank()) {
                    out[slot] = UiBinding(path = raw.trim())
                }
                is JSONObject -> {
                    val path = raw.optString("path").trim()
                    if (path.isBlank()) {
                        warnings.add("bindings.$slot 缺少 path")
                    } else {
                        out[slot] = UiBinding(
                            path = path,
                            format = raw.optString("format"),
                            fallback = raw.optString("fallback"),
                        )
                    }
                }
            }
        }
        return out
    }

    private fun parseActions(
        json: JSONObject?,
        warnings: MutableList<String>,
    ): Map<String, UiAction> {
        if (json == null) return emptyMap()
        val out = mutableMapOf<String, UiAction>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            when (val raw = json.opt(name)) {
                is String -> if (raw.isNotBlank()) {
                    out[name] = UiAction(rpc = raw.trim())
                }
                is JSONObject -> {
                    val rpc = raw.optString("rpc").trim()
                    if (rpc.isBlank()) {
                        warnings.add("actions.$name 缺少 rpc")
                    } else {
                        out[name] = UiAction(
                            rpc = rpc,
                            params = raw.optJSONObject("params"),
                            confirm = raw.optString("confirm"),
                        )
                    }
                }
            }
        }
        return out
    }

    private fun parseStrings(text: String, warnings: MutableList<String>): UiStrings {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            warnings.add("strings.json 解析失败: ${e.message}")
            return UiStrings()
        }
        val out = mutableMapOf<String, String>()
        fun walk(prefix: String, obj: JSONObject) {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.opt(key)
                if (value is JSONObject) {
                    walk(if (prefix.isEmpty()) key else "$prefix.$key", value)
                } else if (value is String) {
                    out[if (prefix.isEmpty()) key else "$prefix.$key"] = value
                }
            }
        }
        walk("", json)
        return UiStrings(out)
    }

    private fun parseComponents(text: String, warnings: MutableList<String>): UiComponents {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            warnings.add("components.json 解析失败: ${e.message}")
            return UiComponents()
        }
        val d = UiComponents()
        fun flag(key: String, fallback: Boolean): Boolean = json.optBoolean(key, fallback)
        fun size(key: String, fallback: Int, range: IntRange): Int {
            val value = json.optInt(key, fallback)
            if (value !in range) {
                warnings.add("components.$key=$value 超出 ${range.first}..${range.last}")
                return fallback
            }
            return value
        }
        return UiComponents(
            showHeaderAvatar = flag("showHeaderAvatar", d.showHeaderAvatar),
            headerAvatarSize = size("headerAvatarSize", d.headerAvatarSize, 16..96),
            showHeaderOnline = flag("showHeaderOnline", d.showHeaderOnline),
            showDrawerBrand = flag("showDrawerBrand", d.showDrawerBrand),
            showPanels = flag("showPanels", d.showPanels),
            showSessions = flag("showSessions", d.showSessions),
            showSessionBadges = flag("showSessionBadges", d.showSessionBadges),
            showSessionSize = flag("showSessionSize", d.showSessionSize),
            showCopy = flag("showCopy", d.showCopy),
            showSelection = flag("showSelection", d.showSelection),
            showSuggestions = flag("showSuggestions", d.showSuggestions),
            showReadOnlyBanner = flag("showReadOnlyBanner", d.showReadOnlyBanner),
            showPendingBanner = flag("showPendingBanner", d.showPendingBanner),
            showSteerHint = flag("showSteerHint", d.showSteerHint),
            showStatusBar = flag("showStatusBar", d.showStatusBar),
            assistantAvatar = flag("assistantAvatar", d.assistantAvatar),
            messageAvatarSize = size("messageAvatarSize", d.messageAvatarSize, 12..64),
            bubbleMaxWidthDp = size("bubbleMaxWidthDp", d.bubbleMaxWidthDp, 200..1200),
            toolSummaryLines = size("toolSummaryLines", d.toolSummaryLines, 1..20),
            toolExpandedLines = size("toolExpandedLines", d.toolExpandedLines, 20..2000),
            codeFenceMaxLines = size("codeFenceMaxLines", d.codeFenceMaxLines, 20..2000),
            tableMaxLines = size("tableMaxLines", d.tableMaxLines, 1..20),
            composerButtonSize = size("composerButtonSize", d.composerButtonSize, 32..72),
        )
    }

    private fun parseIcons(text: String, warnings: MutableList<String>): Map<String, String> {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            warnings.add("icons.json 解析失败: ${e.message}")
            return emptyMap()
        }
        val out = mutableMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.optString(key, "")
            if (value.isNotBlank()) out[key] = value.trim()
        }
        return out
    }

    private fun JSONObject.colorOrNull(name: String, warnings: MutableList<String>): Color? {
        if (!has(name)) return null
        val raw = optString(name, "")
        if (raw.isBlank()) return null
        return try {
            Color(AndroidColor.parseColor(raw))
        } catch (e: Exception) {
            warnings.add("$name=$raw 不是有效颜色")
            null
        }
    }

    private fun JSONObject.color(name: String, fallback: Color, warnings: MutableList<String>): Color {
        val raw = optString(name, "")
        if (raw.isBlank()) return fallback
        return try {
            Color(AndroidColor.parseColor(raw))
        } catch (e: Exception) {
            warnings.add("$name=$raw 不是有效颜色")
            fallback
        }
    }
}
