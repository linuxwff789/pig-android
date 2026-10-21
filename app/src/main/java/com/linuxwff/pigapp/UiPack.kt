package com.linuxwff.pigapp

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

data class UiTheme(
    val background: Color = Color(0xFF0E1014),
    val surface: Color = Color(0xFF161A22),
    val surfaceVariant: Color = Color(0xFF1E2430),
    val primary: Color = Color(0xFF8B7CFF),
    val secondary: Color = Color(0xFF4CD7F6),
    val text: Color = Color(0xFFE6E8EF),
    val textMuted: Color = Color(0xFFA8B0C0),
    val danger: Color = Color(0xFFFF6B6B),
    val cornerRadius: Int = 16,
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

        var theme = UiTheme()
        files[themePath]?.let { theme = parseTheme(it, warnings) }
            ?: warnings.add("$themePath 缺失，使用默认主题")

        var layout = UiLayout()
        files[layoutPath]?.let { layout = parseLayout(it, warnings) }
            ?: warnings.add("$layoutPath 缺失，使用默认布局")

        return Result(
            UiPack(
                name = name,
                version = manifest.optInt("version", 1),
                theme = theme,
                layout = layout,
                files = files.size,
                loadedAt = System.currentTimeMillis(),
            ),
            warnings,
        )
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
