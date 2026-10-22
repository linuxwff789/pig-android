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
    val toolCards: String = "collapsible",
    val showUsage: Boolean = true,
    val composerPlaceholder: String = "给 Pig 发消息…",
    val composerMaxLines: Int = 6,
    val sendOnEnter: Boolean = true,
)

data class UiPack(
    val name: String = "Pig default",
    val version: Int = 1,
    val theme: UiTheme = UiTheme(),
    val layout: UiLayout = UiLayout(),
    val types: TypeScale = TypeScale(),
    val nav: List<NavItem> = emptyList(),
    val files: Int = 0,
    val loadedAt: Long = 0L,
)

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

        var layout = UiLayout()
        files[layoutPath]?.let { layout = parseLayout(it, warnings) }
            ?: warnings.add("$layoutPath 缺失，使用默认布局")

        var types = TypeScale()
        files[typographyPath]?.let { types = parseTypes(it, warnings) }

        val nav = files["nav.json"]?.let { parseNav(it, warnings) } ?: emptyList()

        return Result(
            UiPack(
                name = name,
                version = manifest.optInt("version", 1),
                theme = theme,
                layout = layout,
                types = types,
                nav = nav,
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
            out.add(
                NavItem(
                    id = id,
                    label = label,
                    window = window,
                    command = command,
                    icon = item.optString("icon").take(20),
                ),
            )
        }
        return out
    }

    private fun parseTheme(text: String, warnings: MutableList<String>): UiTheme {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            warnings.add("theme JSON 解析失败: ${e.message}")
            return UiTheme()
        }
        val d = UiTheme()
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
            accent = json.color("accent", d.secondary, warnings),
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
        val modes = setOf("collapsible", "expanded", "collapsed")
        val mode = chat?.optString("toolCards", d.toolCards) ?: d.toolCards
        if (mode !in modes) warnings.add("chat.toolCards=$mode 无效")

        return UiLayout(
            sidebarWidthDp = (sidebar?.optInt("widthDp", d.sidebarWidthDp)
                ?: d.sidebarWidthDp).coerceIn(240, 560),
            assistantLabel = chat?.optString("assistantLabel", d.assistantLabel)
                ?.takeIf { it.isNotBlank() } ?: d.assistantLabel,
            toolCards = mode.takeIf { it in modes } ?: d.toolCards,
            showUsage = chat?.optBoolean("showUsage", d.showUsage) ?: d.showUsage,
            composerPlaceholder = composer?.optString("placeholder", d.composerPlaceholder)
                ?.takeIf { it.isNotBlank() } ?: d.composerPlaceholder,
            composerMaxLines = (composer?.optInt("maxLines", d.composerMaxLines)
                ?: d.composerMaxLines).coerceIn(1, 12),
            sendOnEnter = composer?.optBoolean("sendOnEnter", d.sendOnEnter) ?: d.sendOnEnter,
        )
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
