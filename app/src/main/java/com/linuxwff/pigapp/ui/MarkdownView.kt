package com.linuxwff.pigapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.linuxwff.pigapp.TypeScale
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.utils.buildMarkdownAnnotatedString
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * Markdown images (`![alt](url)`): the bytes come from [RemoteImages]; a null
 * return while they are in flight means "nothing to draw yet". The snapshot
 * map flips when the fetch finishes and the block redraws.
 */
private class PigImageTransformer(private val cornerRadius: Int) : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? {
        LaunchedEffect(link) { RemoteImages.request(link) }
        val image = RemoteImages.get(link) ?: return null
        return ImageData(
            painter = BitmapPainter(image.bitmap),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(cornerRadius.dp))
                .clickable { ImageViewer.show(image.bitmap, image.mimeType) },
            contentDescription = link,
        )
    }
}

/** Markdown with a GFM table renderer (the m3 renderer has no table component). */
@Composable
fun PigMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    types: TypeScale = TypeScale(),
    cornerRadius: Int = 16,
) {
    val components = remember(cornerRadius) {
        markdownComponents(
            custom = { type, model ->
                if (type == GFMElementTypes.TABLE) {
                    MarkdownTable(model, cornerRadius)
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
    val imageTransformer = remember(cornerRadius) { PigImageTransformer(cornerRadius) }
    val themeTypography = MaterialTheme.typography
    val typography = markdownTypography(
        h1 = themeTypography.headlineLarge,
        h2 = themeTypography.headlineMedium,
        h3 = themeTypography.headlineSmall,
        h4 = themeTypography.titleLarge,
        h5 = themeTypography.titleMedium,
        h6 = themeTypography.titleSmall,
        text = themeTypography.bodyLarge,
        paragraph = themeTypography.bodyLarge,
        ordered = themeTypography.bodyLarge,
        bullet = themeTypography.bodyLarge,
        list = themeTypography.bodyLarge,
        code = themeTypography.bodyMedium.copy(
            fontFamily = packCodeFont(),
            fontSize = types.code.sp,
        ),
        quote = themeTypography.bodyMedium.copy(
            fontStyle = FontStyle.Italic,
            color = packColor(LocalUiPack.current.layout.markdown.quoteTextColor),
        ),
    )
    val md = LocalUiPack.current.layout.markdown
    val colors = markdownColor(
        codeText = packColor(md.inlineCodeColor),
        inlineCodeText = packColor(md.inlineCodeColor),
        linkText = packColor(md.linkColor),
        codeBackground = packColor(md.codeBlockBackground).copy(alpha = md.codeBlockAlpha),
        inlineCodeBackground = packColor(md.inlineCodeBackground),
        dividerColor = packColor(md.hrColor),
    )
    Markdown(
        content = text,
        modifier = modifier,
        typography = typography,
        colors = colors,
        imageTransformer = imageTransformer,
        components = components,
    )
}

@Composable
private fun MarkdownTable(model: MarkdownComponentModel, cornerRadius: Int) {
    val md = LocalUiPack.current.layout.markdown
    val headerBackground = packColor(md.tableHeaderBackground)
    val borderColor = packColor(md.tableBorderColor).copy(alpha = md.tableBorderAlpha)
    val zebraColor = packColor(md.tableHeaderBackground).copy(alpha = md.tableZebraAlpha)
    val content = model.content
    val headerNode = model.node.findChildOfType(GFMElementTypes.HEADER)
    val separator = model.node.findChildOfType(GFMTokenTypes.TABLE_SEPARATOR)

    val headerCells = headerNode?.children.orEmpty()
        .filter { it.type == GFMTokenTypes.CELL }
    val rows = model.node.children
        .filter { it.type == GFMElementTypes.ROW }
        .map { row ->
            row.children.filter { it.type == GFMTokenTypes.CELL }
        }

    val columns = maxOf(headerCells.size, rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    val alignments = parseAlignments(separator?.getTextInNode(content)?.toString(), columns)

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (headerCells.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape((cornerRadius / 2).coerceAtLeast(4).dp))
                    .background(headerBackground)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            ) {
                for (index in 0 until columns) {
                    TableCell(
                        content = content,
                        node = headerCells.getOrNull(index),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = alignments[index],
                        bold = true,
                    )
                }
            }
        }
        rows.forEachIndexed { rowIndex, row ->
            HorizontalDivider(color = borderColor)
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (rowIndex % 2 == 1 && md.tableZebraAlpha > 0f) {
                            Modifier.background(zebraColor)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            ) {
                for (index in 0 until columns) {
                    TableCell(
                        content = content,
                        node = row.getOrNull(index),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = alignments[index],
                    )
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    content: String,
    node: ASTNode?,
    modifier: Modifier,
    style: TextStyle,
    textAlign: TextAlign,
    bold: Boolean = false,
) {
    val text = buildAnnotatedString {
        if (bold) pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
        if (node != null) buildMarkdownAnnotatedString(content, node)
        if (bold) pop()
    }
    Text(
        text,
        modifier = modifier,
        style = style,
        textAlign = textAlign,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
    )
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


sealed interface MarkdownSegment {
    data class Text(val text: String) : MarkdownSegment
    data class Code(val language: String, val code: String) : MarkdownSegment
}

private val fenceRegex = Regex("""^\s*(```|~~~)(.*)$""")

fun splitCodeFences(text: String): List<MarkdownSegment> {
    val lines = text.lines()
    val out = mutableListOf<MarkdownSegment>()
    val buffer = StringBuilder()
    fun flushText() {
        val value = buffer.toString().trimEnd()
        if (value.isNotBlank()) out.add(MarkdownSegment.Text(value))
        buffer.setLength(0)
    }
    var index = 0
    while (index < lines.size) {
        val match = fenceRegex.find(lines[index])
        if (match == null) {
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(lines[index])
            index++
            continue
        }
        flushText()
        val fence = match.groupValues[1]
        val language = match.groupValues[2].trim()
        val code = StringBuilder()
        index++
        while (index < lines.size && !lines[index].trimStart().startsWith(fence)) {
            if (code.isNotEmpty()) code.append('\n')
            code.append(lines[index])
            index++
        }
        if (index < lines.size) index++ // closing fence
        out.add(MarkdownSegment.Code(language, code.toString()))
    }
    flushText()
    return out
}

/** Markdown with fenced code blocks rendered as collapsed cards. */
@Composable
fun AssistantMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    types: TypeScale = TypeScale(),
    cornerRadius: Int = 16,
    codeBlockRadius: Int = -1,
    codeBlockBorderAlpha: Float = 0.35f,
) {
    val segments = remember(text) { splitCodeFences(text) }
    Column(modifier) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            when (segment) {
                is MarkdownSegment.Text -> PigMarkdown(
                    text = segment.text,
                    modifier = Modifier.fillMaxWidth(),
                    types = types,
                    cornerRadius = cornerRadius,
                )
                is MarkdownSegment.Code -> CollapsibleCode(
                    language = segment.language,
                    code = segment.code,
                    cornerRadius = if (codeBlockRadius >= 0) codeBlockRadius else cornerRadius,
                    borderAlpha = codeBlockBorderAlpha,
                )
            }
        }
    }
}

@Composable
private fun CollapsibleCode(
    language: String,
    code: String,
    cornerRadius: Int,
    borderAlpha: Float,
) {
    var expanded by remember(code) { mutableStateOf(false) }
    val md = LocalUiPack.current.layout.markdown
    val scheme = MaterialTheme.colorScheme
    val components = packComponents()
    val shape = RoundedCornerShape((cornerRadius / 2).coerceAtLeast(6).dp)
    val lineCount = remember(code) { code.count { it == '\n' } + 1 }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(packColor(md.codeBlockBackground).copy(alpha = md.codeBlockAlpha))
            .border(1.dp, packColor(md.codeBlockBorderColor).copy(alpha = borderAlpha), shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                language.ifBlank { packText("markdown.code", "code") },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.secondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                packText("markdown.lines", "{count} 行").replace("{count}", lineCount.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (expanded) {
                    packText("tool.collapse", "收起")
                } else {
                    packText("tool.expand", "展开")
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
            )
            Icon(
                packIcon(if (expanded) "chevronUp" else "chevronDown", if (expanded) "up" else "down"),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = scheme.primary,
            )
        }
        if (expanded) {
            val shown = remember(code, components.codeFenceMaxLines) {
                val lines = code.lineSequence().take(components.codeFenceMaxLines).toList()
                lines.joinToString("\n").ifEmpty { " " } +
                    if (code.lineSequence().count() > components.codeFenceMaxLines) "\n…" else ""
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                Text(
                    shown,
                    fontFamily = packCodeFont(),
                    fontSize = 11.sp,
                    softWrap = false,
                )
            }
        }
    }
}
