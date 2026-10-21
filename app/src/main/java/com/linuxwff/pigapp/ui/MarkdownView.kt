package com.linuxwff.pigapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/** Markdown with a GFM table renderer (the m3 renderer has no table component). */
@Composable
fun PigMarkdown(text: String, modifier: Modifier = Modifier) {
    val components = remember {
        markdownComponents(
            custom = { type, model ->
                if (type == GFMElementTypes.TABLE) {
                    MarkdownTable(model)
                } else {
                    // Unknown block: show its raw source instead of dropping it.
                    Text(
                        model.node.getTextInNode(model.content).toString().trim(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
        )
    }
    Markdown(
        content = text,
        modifier = modifier,
        components = components,
    )
}

@Composable
private fun MarkdownTable(model: MarkdownComponentModel) {
    val content = model.content
    val headerNode = model.node.findChildOfType(GFMElementTypes.HEADER)
    val separator = model.node.findChildOfType(GFMTokenTypes.TABLE_SEPARATOR)

    val headers = headerNode?.children.orEmpty()
        .filter { it.type == GFMTokenTypes.CELL }
        .map { it.getTextInNode(content).toString().trim() }
    val rows = model.node.children
        .filter { it.type == GFMElementTypes.ROW }
        .map { row ->
            row.children
                .filter { it.type == GFMTokenTypes.CELL }
                .map { it.getTextInNode(content).toString().trim() }
        }

    val columns = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    val alignments = parseAlignments(separator?.getTextInNode(content)?.toString(), columns)

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (headers.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            ) {
                for (index in 0 until columns) {
                    Text(
                        headers.getOrElse(index) { "" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = alignments[index],
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        rows.forEach { row ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            ) {
                for (index in 0 until columns) {
                    Text(
                        row.getOrElse(index) { "" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = alignments[index],
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun parseAlignments(separator: String?, columns: Int): List<TextAlign> {
    val cells = separator
        ?.trim()
        ?.trim('|')
        ?.split('|')
        ?: emptyList()
    return (0 until columns).map { index ->
        val cell = cells.getOrNull(index)?.trim() ?: ""
        when {
            cell.startsWith(":") && cell.endsWith(":") -> TextAlign.Center
            cell.endsWith(":") -> TextAlign.End
            else -> TextAlign.Start
        }
    }
}
