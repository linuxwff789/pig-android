package com.linuxwff.pigapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * A plan an extension wants rendered as a checklist.
 *
 * The app is deliberately extension-agnostic: any extension that emits a plan
 * widget following `docs/plan-widget.md` gets this card, and everything else
 * keeps the plain monospace pane. That is what lets a user pick a third-party
 * plan extension without the app knowing about it.
 */
data class PlanStep(val text: String, val done: Boolean)

data class PlanSpec(val title: String, val steps: List<PlanStep>) {
    val done: Int get() = steps.count { it.done }
    val total: Int get() = steps.size
}

/**
 * Widget keys the contract reserves for plans. An extension may choose any of
 * them; `plan` is the canonical one. Keys are matched case-insensitively so
 * `Plan` / `plan` behave the same.
 */
private val PLAN_KEYS = setOf("plan", "plan-todos", "plan_todos", "todos", "todo")

private val CHECKBOX = Regex("""^\s*(?:[-*+]\s*)?\[([ xX✓✔])\]\s+(.+?)\s*$""")
private val SYMBOL = Regex("""^\s*([☐☑✅✔✓])\s*(.+?)\s*$""")
private val NUMBERED = Regex("""^\s*(\d+)[.)]\s+(.+?)\s*$""")
private val PROGRESS = Regex("""^\s*(?:📋\s*)?(?:plan\s*)?(\d+)\s*/\s*(\d+)\s*(?:[·|:—-].*)?$""", RegexOption.IGNORE_CASE)
private val TITLE = Regex("""^\s*(?:#{1,6}\s*|📋\s*)?plan\s*[:：]\s*(.*?)\s*$""", RegexOption.IGNORE_CASE)
private val TITLE_BARE = Regex("""^\s*(?:#{1,6}\s*|📋\s*)plan\s*$""", RegexOption.IGNORE_CASE)

/** True when [key] is one the plan contract reserves. */
fun isPlanWidgetKey(key: String): Boolean = key.trim().lowercase() in PLAN_KEYS

/**
 * Parse a widget into a plan, or `null` when it is not one.
 *
 * Two forms are accepted, in this order:
 *  1. one JSON object line: `{"plan":{"title":"…","steps":[{"text":"…","done":true}]}}`
 *  2. a text checklist (`☐/☑`, `[ ]/[x]`, numbered items) with an optional
 *     `Plan:` title and an optional `2/5` progress line.
 *
 * Returning null is the fallback contract: the widget still renders exactly as
 * before, so an extension that does not follow the convention is never broken.
 */
fun parsePlanWidget(key: String, lines: List<String>): PlanSpec? {
    if (!isPlanWidgetKey(key)) return null
    return parsePlanJson(lines) ?: parsePlanText(lines)
}

private fun parsePlanJson(lines: List<String>): PlanSpec? {
    val first = lines.firstOrNull { it.isNotBlank() }?.trim() ?: return null
    if (!first.startsWith("{")) return null
    val root = runCatching { JSONObject(first) }.getOrNull() ?: return null
    val node = root.optJSONObject("plan") ?: root
    val array = node.optJSONArray("steps") ?: return null
    val steps = ArrayList<PlanStep>(array.length())
    for (index in 0 until array.length()) {
        when (val item = array.opt(index)) {
            is JSONObject -> {
                val text = item.optString("text").trim()
                if (text.isNotEmpty()) steps.add(PlanStep(text, item.optBoolean("done", false)))
            }
            is String -> {
                val text = item.trim()
                if (text.isNotEmpty()) steps.add(PlanStep(text, false))
            }
        }
        if (steps.size >= MAX_STEPS) break
    }
    if (steps.isEmpty()) return null
    return PlanSpec(node.optString("title").trim(), steps)
}

private fun parsePlanText(lines: List<String>): PlanSpec? {
    var title = ""
    val steps = ArrayList<PlanStep>()
    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val checkbox = CHECKBOX.matchEntire(line)
        if (checkbox != null) {
            val text = cleanStep(checkbox.groupValues[2])
            if (text.isNotEmpty()) {
                val box = checkbox.groupValues[1]
                steps.add(PlanStep(text, box == "x" || box == "X" || box == "✓" || box == "✔"))
            }
            if (steps.size >= MAX_STEPS) break
            continue
        }
        val symbol = SYMBOL.matchEntire(line)
        if (symbol != null) {
            val text = cleanStep(symbol.groupValues[2])
            if (text.isNotEmpty()) steps.add(PlanStep(text, symbol.groupValues[1] in "☑✅✔✓"))
            if (steps.size >= MAX_STEPS) break
            continue
        }
        if (steps.isEmpty()) {
            if (PROGRESS.matches(line)) continue
            val heading = TITLE.matchEntire(line)
            if (heading != null) {
                title = heading.groupValues[1].trim()
                continue
            }
            if (TITLE_BARE.matches(line)) continue
        }
        val numbered = NUMBERED.matchEntire(line)
        if (numbered != null) {
            val text = cleanStep(numbered.groupValues[2])
            if (text.isNotEmpty()) steps.add(PlanStep(text, false))
            if (steps.size >= MAX_STEPS) break
        }
    }
    if (steps.isEmpty()) return null
    return PlanSpec(title, steps)
}

/** Drop the decoration a model puts around a step, keep the words. */
private fun cleanStep(raw: String): String = raw
    .replace("**", "")
    .replace("`", "")
    .trim()
    .trimEnd(':', '：', '。', '.', ',', '；', ';')
    .trim()

private const val MAX_STEPS = 200

/**
 * The checklist card. Read-only by design: the app has no channel to toggle a
 * step back into an extension, and faking one would desync the extension's own
 * state. Extensions mark progress themselves (`[DONE:n]`, tools, or their own
 * logic) and re-emit the widget.
 */
@Composable
fun PlanCard(spec: PlanSpec) {
    val scheme = MaterialTheme.colorScheme
    val radius = (LocalUiPack.current.theme.cornerRadius / 2).coerceAtLeast(8).dp
    val current = spec.steps.indexOfFirst { !it.done }
    Surface(
        color = scheme.surface,
        shape = RoundedCornerShape(radius),
        border = BorderStroke(1.dp, scheme.secondary.copy(alpha = 0.30f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    spec.title.ifBlank { packText("plan.title", "计划") },
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    packText("plan.progress", "{done}/{total}")
                        .replace("{done}", spec.done.toString())
                        .replace("{total}", spec.total.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (spec.total > 0 && spec.done == spec.total) {
                        scheme.tertiary
                    } else {
                        scheme.onSurfaceVariant
                    },
                )
            }
            if (spec.total > 0) {
                LinearProgressIndicator(
                    progress = { spec.done.toFloat() / spec.total },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(3.dp)
                        .clip(CircleShape),
                    color = scheme.secondary,
                    trackColor = scheme.surfaceVariant,
                )
            }
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                spec.steps.forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            if (step.done) "☑" else "☐",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (step.done) scheme.tertiary else scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            step.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                step.done -> scheme.onSurfaceVariant.copy(alpha = 0.65f)
                                index == current -> scheme.primary
                                else -> scheme.onSurface
                            },
                            textDecoration = if (step.done) TextDecoration.LineThrough else null,
                        )
                    }
                }
            }
        }
    }
}
