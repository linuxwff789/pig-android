package com.linuxwff.pigapp

import org.json.JSONObject

data class WindowPos(val x: Float, val y: Float)

data class UiOption(val label: String, val value: String)

data class UiNode(
    val type: String,
    val visible: Boolean = true,
    val padding: Int = 0,
    val weight: Float = 0f,
    val text: String = "",
    val style: String = "body",
    val align: String = "start",
    val spacing: Int = 0,
    val scroll: Boolean = false,
    val children: List<UiNode> = emptyList(),
    val size: Int = 0,
    val value: Float = 0f,
    val indeterminate: Boolean = false,
    val label: String = "",
    val action: String = "",
    val buttonStyle: String = "primary",
    val enabled: Boolean = true,
    val language: String = "",
    val maxLines: Int = 20,
    val fieldId: String = "",
    val placeholder: String = "",
    val options: List<UiOption> = emptyList(),
    val initialValue: String = "",
    val checked: Boolean = false,
    val headers: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
)

data class AppWindow(
    val id: String,
    val title: String,
    val placement: String,
    val widthDp: Int,
    val heightDp: Int,
    val dismissible: Boolean,
    val body: UiNode,
    val source: String,
    val loadedAt: Long,
) {
    override fun toString(): String =
        "AppWindow(id=$id, title=$title, placement=$placement, ${widthDp}x$heightDp, dismissible=$dismissible)"
}

/** Parser for the `app-ui/1` window contract. Never throws. */
object AppUiParser {
    private val idPattern = Regex("^[a-z0-9._-]{1,40}$")
    private val knownTypes = setOf(
        "column", "row", "divider", "spacer", "text", "code", "progress", "button",
        "textfield", "select", "toggle", "list", "table",
    )

    data class Result(val window: AppWindow?, val warnings: List<String>)

    fun parseWindow(text: String, source: String = "debug"): Result {
        val warnings = mutableListOf<String>()
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return Result(null, listOf("JSON 解析失败: ${e.message}"))
        }
        val version = json.optInt("appUI", 1)
        if (version != 1) return Result(null, listOf("不支持的 appUI 版本: $version"))

        val id = json.optString("id")
        if (!idPattern.matches(id)) return Result(null, listOf("id 无效: '$id'"))

        val bodyJson = json.optJSONObject("body")
            ?: return Result(null, listOf("缺少 body"))
        val nodes = intArrayOf(0)
        val body = parseNode(bodyJson, 0, nodes, warnings)
            ?: return Result(null, warnings.ifEmpty { listOf("body 无效") })

        val rawPlacement = json.optString("placement", "overlay")
        val allowedPlacements = setOf("overlay", "sheet", "aboveComposer", "belowComposer")
        val placement = rawPlacement.takeIf { it in allowedPlacements } ?: run {
            warnings.add("placement=$rawPlacement 无效，按 overlay 显示")
            "overlay"
        }

        return Result(
            AppWindow(
                id = id,
                title = json.optString("title", id).ifBlank { id },
                placement = placement,
                widthDp = json.optInt("width", 320).coerceIn(200, 2000),
                heightDp = json.optInt("height", 0).coerceIn(0, 2000),
                dismissible = json.optBoolean("dismissible", true),
                body = body,
                source = source,
                loadedAt = System.currentTimeMillis(),
            ),
            warnings,
        )
    }

    private fun parseNode(
        json: JSONObject,
        depth: Int,
        count: IntArray,
        warnings: MutableList<String>,
    ): UiNode? {
        if (depth > 32) {
            warnings.add("节点深度超过 32")
            return null
        }
        count[0]++
        if (count[0] > 2000) {
            warnings.add("节点数超过 2000")
            return null
        }

        val type = json.optString("type")
        if (type !in knownTypes) {
            warnings.add("不支持的节点类型: $type")
            return UiNode(type = "text", text = "[unsupported: $type]", style = "muted")
        }

        val children = mutableListOf<UiNode>()
        json.optJSONArray("children")?.let { array ->
            for (index in 0 until array.length()) {
                val child = array.optJSONObject(index) ?: continue
                parseNode(child, depth + 1, count, warnings)?.let { children.add(it) }
            }
        }

        val formType = type in setOf("textfield", "select", "toggle")
        val fieldId = json.optString("id", "").take(64).ifBlank {
            if (formType) {
                warnings.add("$type 缺少 id，使用 field${count[0]}")
                "field${count[0]}"
            } else ""
        }
        val valueRaw = json.opt("value")
        val initialValue = if (type == "textfield" || type == "select") {
            if (valueRaw is String) valueRaw.take(2048) else json.optString("text", "").take(2048)
        } else ""
        val checked = valueRaw is Boolean && valueRaw
        val options = mutableListOf<UiOption>()
        json.optJSONArray("options")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                if (item != null) {
                    val value = item.optString("value", item.optString("label"))
                    options.add(UiOption(item.optString("label", value), value))
                } else {
                    val text = array.optString(index)
                    if (text.isNotBlank()) options.add(UiOption(text, text))
                }
            }
        }
        val headers = mutableListOf<String>()
        json.optJSONArray("headers")?.let { array ->
            for (index in 0 until array.length()) headers.add(array.optString(index))
        }
        val rows = mutableListOf<List<String>>()
        json.optJSONArray("rows")?.let { array ->
            for (index in 0 until array.length()) {
                val row = array.optJSONArray(index) ?: continue
                rows.add((0 until row.length()).map { row.optString(it) })
            }
        }

        return UiNode(
            type = type,
            visible = json.optBoolean("visible", true),
            padding = json.optInt("padding", 0).coerceIn(0, 48),
            weight = json.optDouble("weight", 0.0).toFloat().coerceIn(0f, 10f),
            text = json.optString("text", "").take(16384),
            style = json.optString("style", "body"),
            align = json.optString("align", "start"),
            spacing = json.optInt("spacing", 0).coerceIn(0, 48),
            scroll = json.optBoolean("scroll", false),
            children = children,
            size = json.optInt("size", 0).coerceIn(0, 400),
            value = json.optDouble("value", 0.0).toFloat().coerceIn(0f, 1f),
            indeterminate = json.optBoolean("indeterminate", false),
            label = json.optString("label", "").take(256),
            action = json.optString("action", "").take(512),
            buttonStyle = json.optString("buttonStyle", "primary"),
            enabled = json.optBoolean("enabled", true),
            language = json.optString("language", "").take(32),
            maxLines = if (type == "textfield") {
                json.optInt("maxLines", 1).coerceIn(1, 12)
            } else {
                json.optInt("maxLines", 20).coerceIn(1, 200)
            },
            fieldId = fieldId,
            placeholder = json.optString("placeholder", "").take(128),
            options = options,
            initialValue = initialValue,
            checked = checked,
            headers = headers,
            rows = rows,
        )
    }
}
