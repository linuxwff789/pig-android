package com.linuxwff.pigapp

import org.json.JSONObject

data class ToolPresentation(
    val headline: String,
    val metrics: String,
    val summary: String,
    val expanded: String,
    val diff: String = "",
    val running: Boolean = false,
    val error: Boolean = false,
)

/**
 * Turns a tool's raw args/result into the three views the card needs:
 * a headline (path/command), metrics (lines/bytes/diff counts), a short
 * summary and the full detail. Falls back to raw text for unknown tools.
 */
fun toolPresentation(
    name: String?,
    argsJson: String,
    result: String,
    detailsJson: String,
    isError: Boolean,
    running: Boolean,
): ToolPresentation {
    val tool = name ?: "tool"
    val args = runCatching { JSONObject(argsJson) }.getOrNull()
    val meta = runCatching { JSONObject(detailsJson) }.getOrNull()
    val details = meta?.optJSONObject("details")
    val structured = meta?.optJSONObject("structured")

    if (running) {
        return ToolPresentation(
            headline = headlineFor(tool, args),
            metrics = "运行中…",
            summary = "",
            expanded = "",
            running = true,
        )
    }

    return when (tool) {
        "write" -> {
            val path = args?.optString("path").orEmpty()
            val content = args?.optString("content").orEmpty()
            ToolPresentation(
                headline = path.ifBlank { tool },
                metrics = "${lineCount(content)} 行 · ${formatBytes(content.toByteArray(Charsets.UTF_8).size)}",
                summary = content.lineSequence().take(4).joinToString("\n"),
                expanded = content,
                error = isError,
            )
        }

        "edit" -> {
            val path = args?.optString("path").orEmpty()
            val diff = details?.optString("diff").orEmpty()
            val (added, removed) = diffStats(diff)
            val fallback = editStats(args)
            ToolPresentation(
                headline = path.ifBlank { tool },
                metrics = if (diff.isNotBlank()) "+$added −$removed" else fallback.first,
                summary = diff.lineSequence().take(8).joinToString("\n").ifBlank { fallback.second },
                expanded = diff.ifBlank { fallback.second },
                diff = diff,
                error = isError,
            )
        }

        "read" -> {
            val path = args?.optString("path").orEmpty()
            ToolPresentation(
                headline = path.ifBlank { tool },
                metrics = "${lineCount(result)} 行",
                summary = result.lineSequence().take(4).joinToString("\n"),
                expanded = result,
                error = isError,
            )
        }

        "bash" -> {
            val command = args?.optString("command").orEmpty()
            val exit = structured?.optInt("exit_code", 0) ?: 0
            ToolPresentation(
                headline = command.lineSequence().firstOrNull().orEmpty().ifBlank { "bash" },
                metrics = "exit $exit · ${lineCount(result)} 行",
                summary = result.lineSequence()
                    .filter { it.isNotBlank() }
                    .take(4)
                    .joinToString("\n"),
                expanded = "$ $command\n$result",
                error = isError,
            )
        }

        "grep", "glob", "ls", "find" -> {
            val pattern = args?.optString("pattern").orEmpty()
            val path = args?.optString("path").orEmpty()
            ToolPresentation(
                headline = listOf(pattern, path)
                    .filter { it.isNotBlank() }
                    .joinToString("  ")
                    .ifBlank { tool },
                metrics = "${lineCount(result)} 行",
                summary = result.lineSequence().take(6).joinToString("\n"),
                expanded = result,
                error = isError,
            )
        }

        else -> ToolPresentation(
            headline = headlineFor(tool, args).ifBlank { tool },
            metrics = "${lineCount(result)} 行",
            summary = result.lineSequence().take(4).joinToString("\n"),
            expanded = result.ifBlank { argsJson },
            error = isError,
        )
    }
}

private fun headlineFor(tool: String, args: JSONObject?): String = when (tool) {
    "write", "edit", "read" -> args?.optString("path").orEmpty()
    "bash" -> args?.optString("command").orEmpty().lineSequence().firstOrNull().orEmpty()
    else -> args?.optString("path").orEmpty()
}

private fun editStats(args: JSONObject?): Pair<String, String> {
    val edits = args?.optJSONArray("edits") ?: return "" to ""
    var added = 0
    var removed = 0
    val preview = StringBuilder()
    for (index in 0 until edits.length()) {
        val edit = edits.optJSONObject(index) ?: continue
        val oldText = edit.optString("oldText")
        val newText = edit.optString("newText")
        removed += lineCount(oldText)
        added += lineCount(newText)
        if (preview.isNotEmpty()) preview.append('\n')
        preview.append("- ").append(oldText.lineSequence().firstOrNull().orEmpty())
        preview.append('\n').append("+ ").append(newText.lineSequence().firstOrNull().orEmpty())
    }
    return "+$added −$removed" to preview.toString()
}

private fun diffStats(diff: String): Pair<Int, Int> {
    var added = 0
    var removed = 0
    diff.lineSequence().forEach { line ->
        when {
            line.startsWith("+") && !line.startsWith("+++") -> added++
            line.startsWith("-") && !line.startsWith("---") -> removed++
        }
    }
    return added to removed
}

fun lineCount(text: String): Int {
    if (text.isEmpty()) return 0
    val newlines = text.count { it == '\n' }
    return if (text.endsWith('\n')) newlines else newlines + 1
}

private fun formatBytes(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "%.1f MB".format(bytes / (1024f * 1024f))
}
