package com.linuxwff.pigapp

import org.json.JSONArray
import org.json.JSONObject

private val placeholderRegex = Regex("\\{([a-zA-Z0-9_.]+)\\}")

/**
 * Resolve `stats.tokens.cacheRead` against the pack's RPC data. The first
 * segment is a `dataSources` name (or a built-in alias such as `state`).
 * Numeric segments index JSON arrays.
 */
fun resolveRpcPath(data: Map<String, JSONObject>, path: String): Any? {
    if (path.isBlank()) return null
    val parts = path.split('.')
    var current: Any? = data[parts.first()]
    for (part in parts.drop(1)) {
        current = when (val value = current) {
            is JSONObject -> {
                if (value.isNull(part)) null else value.opt(part)
            }
            is JSONArray -> part.toIntOrNull()?.let { index ->
                if (index in 0 until value.length()) value.opt(index) else null
            }
            else -> null
        }
        if (current == null || current == JSONObject.NULL) return null
    }
    return current.takeIf { it != JSONObject.NULL }
}

/** Format a bound value; `format` may use `{value}` or `{key}`/`{a.b}`. */
fun formatRpcValue(value: Any?, format: String, fallback: String = ""): String? {
    if (value == null) return fallback.ifBlank { null }
    if (format.isBlank()) return displayRpcValue(value)
    var out = format
    if (value is JSONObject || value is JSONArray) {
        out = placeholderRegex.replace(out) { match ->
            val path = match.groupValues[1]
            val resolved = resolveAny(value, path)
            if (resolved == null) match.value else displayRpcValue(resolved)
        }
    } else {
        out = out.replace("{value}", displayRpcValue(value))
    }
    if (placeholderRegex.containsMatchIn(out)) return fallback.ifBlank { null }
    return out
}

/** Resolve a binding, returning null when it has no value and no fallback. */
fun UiBinding.resolve(data: Map<String, JSONObject>): String? {
    val value = resolveRpcPath(data, path)
    return formatRpcValue(value, format, fallback)
}

private fun resolveAny(root: Any?, path: String): Any? {
    var current: Any? = root
    for (part in path.split('.')) {
        current = when (val value = current) {
            is JSONObject -> if (value.isNull(part)) null else value.opt(part)
            is JSONArray -> part.toIntOrNull()?.let { index ->
                if (index in 0 until value.length()) value.opt(index) else null
            }
            else -> null
        }
        if (current == null || current == JSONObject.NULL) return null
    }
    return current.takeIf { it != JSONObject.NULL }
}

private fun displayRpcValue(value: Any?): String = when (value) {
    null, JSONObject.NULL -> ""
    is Double -> if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        "%.1f".format(value)
    }
    is Float -> displayRpcValue(value.toDouble())
    else -> value.toString()
}

/** `1000000` → `1M`, `128000` → `128K`, `16384` → `16K`. */
fun formatTokenLabel(tokens: Int): String = when {
    tokens >= 1_000_000 ->
        "%.2f".format(tokens / 1_000_000f).trimEnd('0').trimEnd('.') + "M"
    tokens >= 1000 -> "${tokens / 1000}K"
    else -> tokens.toString()
}

/**
 * Resolve a pack binding path against live app state first.
 *
 * `context` (and its `context.*` children) is computed by the app rather than
 * taken from an RPC reply, so packs can show the *effective* window/usage —
 * including the user's pinned context length and the last known usage while a
 * relay is attached without a live pig.
 */
fun resolveUiPath(ui: AppUiState, path: String): Any? {
    val root = path.substringBefore('.')
    if (root == "context" && !ui.rpcData.containsKey(root)) {
        val percent = ui.contextPercent
        val ctx = JSONObject()
            .put("percent", percent ?: JSONObject.NULL)
            .put(
                "percentLabel",
                percent?.let { "%.1f".format(it).trimEnd('0').trimEnd('.') + "%" } ?: "—",
            )
            .put("tokens", ui.contextTokens)
            .put("tokensLabel", formatTokenLabel(ui.contextTokens))
            .put("window", ui.contextWindow)
            .put("windowLabel", if (ui.contextWindow > 0) formatTokenLabel(ui.contextWindow) else "—")
        val key = path.removePrefix("context").removePrefix(".")
        if (key.isEmpty()) return ctx
        return if (ctx.has(key) && !ctx.isNull(key)) ctx.opt(key) else null
    }
    return resolveRpcPath(ui.rpcData, path)
}
