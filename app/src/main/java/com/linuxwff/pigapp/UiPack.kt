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
)

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
    /** Skin switcher: show, where and how (chips/list). */
    val showSkinSwitcher: Boolean = true,
    val skinSwitcherPlacement: String = "drawer",
    val skinSwitcherStyle: String = "chips",
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
        val skinSwitcher = json.optJSONObject("skinSwitcher")
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
            showSkinSwitcher = skinSwitcher?.optBoolean("show", d.showSkinSwitcher)
                ?: d.showSkinSwitcher,
            skinSwitcherPlacement = placement.takeIf { it in placements } ?: d.skinSwitcherPlacement,
            skinSwitcherStyle = skinStyle.takeIf { it in styles } ?: d.skinSwitcherStyle,
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
