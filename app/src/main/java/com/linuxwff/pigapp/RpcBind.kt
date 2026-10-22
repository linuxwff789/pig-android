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
