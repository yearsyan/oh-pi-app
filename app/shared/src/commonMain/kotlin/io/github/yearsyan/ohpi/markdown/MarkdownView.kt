package io.github.yearsyan.ohpi.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yearsyan.ohpi.syntax.Syntax
import io.github.yearsyan.ohpi.theme.piExtras

private const val InlineCodeTag = "inline-code"

private enum class MdAlign { Left, Center, Right }

private sealed class MdBlock {
    data class Code(val lang: String, val code: String) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Para(val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Bullet(val ordered: Boolean, val items: List<String>) : MdBlock()
    data class Table(val headers: List<String>, val aligns: List<MdAlign>, val rows: List<List<String>>) : MdBlock()
    data object Rule : MdBlock()
}

/** GFM table divider row, e.g. `| --- | :---: | ---: |`. */
private fun isTableDivider(line: String): Boolean {
    val t = line.trim().trim('|').trim()
    if (t.isEmpty() || !t.contains('-')) return false
    return t.split('|').all { cell -> cell.trim().matches(Regex("^:?-+:?\$")) }
}

/** Splits a table row on unescaped pipes, dropping the outer border pipes. */
private fun splitTableRow(line: String): List<String> {
    var t = line.trim()
    if (t.startsWith("|")) t = t.substring(1)
    if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
    return t.split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }
}

private fun parseBlocks(markdown: String): List<MdBlock> {
    val lines = markdown.replace("\r\n", "\n").split("\n")
    val blocks = ArrayList<MdBlock>()
    var i = 0
    val para = StringBuilder()

    fun flushPara() {
        val t = para.toString().trim()
        if (t.isNotEmpty()) blocks.add(MdBlock.Para(t))
        para.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                flushPara()
                val lang = line.trim().removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(lines[i]).append("\n")
                    i++
                }
                blocks.add(MdBlock.Code(lang, code.toString().trimEnd('\n')))
            }
            line.trim().matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> {
                flushPara()
                blocks.add(MdBlock.Rule)
            }
            line.contains('|') && i + 1 < lines.size && isTableDivider(lines[i + 1]) -> {
                flushPara()
                val headers = splitTableRow(line)
                val aligns = splitTableRow(lines[i + 1]).map { cell ->
                    val c = cell.trim()
                    when {
                        c.startsWith(":") && c.endsWith(":") -> MdAlign.Center
                        c.endsWith(":") -> MdAlign.Right
                        else -> MdAlign.Left
                    }
                }
                val rows = ArrayList<List<String>>()
                i += 2
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                    rows.add(splitTableRow(lines[i]))
                    i++
                }
                i-- // loop increments
                blocks.add(MdBlock.Table(headers, aligns, rows))
            }
            line.startsWith("#") -> {
                val m = Regex("^(#{1,6})\\s+(.*)$").find(line)
                if (m != null) {
                    flushPara()
                    blocks.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
                } else {
                    para.append(line).append("\n")
                }
            }
            line.trimStart().startsWith(">") -> {
                flushPara()
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quote.append(lines[i].trimStart().removePrefix(">").trim()).append("\n")
                    i++
                }
                i-- // loop increments
                blocks.add(MdBlock.Quote(quote.toString().trim()))
            }
            Regex("^\\s*([-*+]|\\d+\\.)\\s+.*$").matches(line) -> {
                flushPara()
                val items = ArrayList<String>()
                var ordered = false
                while (i < lines.size) {
                    val lm = Regex("^\\s*([-*+]|(\\d+)\\.)\\s+(.*)$").find(lines[i]) ?: break
                    ordered = lm.groupValues[2].isNotEmpty()
                    items.add(lm.groupValues[3].trim())
                    i++
                }
                i--
                blocks.add(MdBlock.Bullet(ordered, items))
            }
            line.isBlank() -> flushPara()
            else -> para.append(line).append("\n")
        }
        i++
    }
    flushPara()
    return blocks
}

/** Renders inline markdown (`code`, **bold**, *italic*, ~~strike~~, [text](url)). */
@Composable
fun inlineMarkdown(text: String, base: SpanStyle = SpanStyle()): AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.primary
    val linkColor = MaterialTheme.colorScheme.tertiary
    return buildAnnotatedString {
        var i = 0
        var bold = false
        var italic = false
        var strike = false
        fun style(): SpanStyle = base.copy(
            fontWeight = if (bold) FontWeight.Bold else base.fontWeight,
            fontStyle = if (italic) FontStyle.Italic else base.fontStyle,
            textDecoration = if (strike) TextDecoration.LineThrough else base.textDecoration,
        )
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                withStyle(style()) { append(buf.toString()) }
                buf.clear()
            }
        }
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> { flush(); bold = !bold; i += 2 }
                text.startsWith("~~", i) -> { flush(); strike = !strike; i += 2 }
                text[i] == '*' && !text.startsWith("**", i) -> { flush(); italic = !italic; i += 1 }
                text[i] == '`' -> {
                    flush()
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        pushStringAnnotation(InlineCodeTag, "")
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.5.sp,
                                color = codeColor,
                            ),
                        ) { append(text.substring(i + 1, end)) }
                        pop()
                        i = end + 1
                    } else {
                        buf.append('`'); i++
                    }
                }
                text[i] == '[' -> {
                    val close = text.indexOf(']', i)
                    if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                        val endUrl = text.indexOf(')', close)
                        if (endUrl > close) {
                            flush()
                            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                                append(text.substring(i + 1, close))
                            }
                            i = endUrl + 1
                        } else { buf.append(text[i]); i++ }
                    } else { buf.append(text[i]); i++ }
                }
                else -> { buf.append(text[i]); i++ }
            }
        }
        flush()
    }
}

@Composable
private fun InlineMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
) {
    val annotatedText = inlineMarkdown(text)
    val codeBackground = piExtras.codeBackground
    var layoutResult by remember(annotatedText) { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = annotatedText,
        modifier = modifier.drawBehind {
            layoutResult?.let { layout ->
                drawInlineCodeBackgrounds(annotatedText, layout, codeBackground)
            }
        },
        color = color,
        fontWeight = fontWeight,
        textAlign = textAlign,
        style = style,
        onTextLayout = { layoutResult = it },
    )
}

private fun DrawScope.drawInlineCodeBackgrounds(
    text: AnnotatedString,
    layout: TextLayoutResult,
    background: Color,
) {
    val textLength = layout.layoutInput.text.length
    if (textLength == 0) return

    val horizontalPadding = 3.dp.toPx()
    val verticalInset = 1.dp.toPx()
    val cornerRadius = CornerRadius(4.dp.toPx())
    for (range in text.getStringAnnotations(InlineCodeTag, 0, text.length)) {
        val start = range.start.coerceIn(0, textLength)
        val end = range.end.coerceIn(start, textLength)
        if (start == end) continue

        val firstLine = layout.getLineForOffset(start)
        val lastLine = layout.getLineForOffset(end - 1)
        for (line in firstLine..lastLine) {
            val segmentStart = maxOf(start, layout.getLineStart(line))
            var segmentEnd = minOf(end, layout.getLineEnd(line, visibleEnd = false))
            while (segmentEnd > segmentStart && text[segmentEnd - 1] == '\n') segmentEnd--
            if (segmentStart >= segmentEnd) continue

            val firstBox = layout.getBoundingBox(segmentStart)
            val lastBox = layout.getBoundingBox(segmentEnd - 1)
            val left = (minOf(firstBox.left, lastBox.left) - horizontalPadding).coerceAtLeast(0f)
            val right = (maxOf(firstBox.right, lastBox.right) + horizontalPadding).coerceAtMost(size.width)
            val top = layout.getLineTop(line) + verticalInset
            val bottom = layout.getLineBottom(line) - verticalInset
            if (right <= left || bottom <= top) continue

            drawRoundRect(
                color = background,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = cornerRadius,
            )
        }
    }
}

/** Above this size code fences stay plain; highlighting is not worth the cost. */
private const val MaxHighlightLength = 256 * 1024

/** Lightweight markdown renderer tuned for chat messages. */
@Composable
fun MarkdownView(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    val codeBg = piExtras.codeBackground
    val onCode = piExtras.onCode
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (block in blocks) {
                when (block) {
                    is MdBlock.Code -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(codeBg, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    ) {
                        if (block.lang.isNotBlank()) {
                            Text(
                                block.lang,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val codeSpec = remember(block.lang) { Syntax.specForLangName(block.lang) }
                        val syntaxColors = piExtras.syntax
                        val codeText = remember(block.code, codeSpec, syntaxColors) {
                            if (codeSpec != null && block.code.length <= MaxHighlightLength) {
                                Syntax.highlight(block.code, codeSpec, syntaxColors)
                            } else {
                                AnnotatedString(block.code)
                            }
                        }
                        Text(
                            codeText,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.5.sp,
                                lineHeight = 18.sp,
                            ),
                            color = onCode,
                        )
                    }
                    is MdBlock.Heading -> {
                        val style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        }
                        InlineMarkdownText(
                            text = block.text,
                            style = style,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    is MdBlock.Para -> InlineMarkdownText(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                    )
                    is MdBlock.Quote -> Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            "▍",
                            color = MaterialTheme.colorScheme.outline,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        InlineMarkdownText(
                            text = block.text,
                            modifier = Modifier.padding(start = 4.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is MdBlock.Bullet -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.items.forEachIndexed { idx, item ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    if (block.ordered) "${idx + 1}." else "•",
                                    modifier = Modifier.padding(end = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                InlineMarkdownText(
                                    text = item,
                                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                                )
                            }
                        }
                    }
                    MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    is MdBlock.Table -> MarkdownTable(block)
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val columnCount = table.headers.size.coerceAtLeast(1)

    @Composable
    fun rowCells(cells: List<String>, header: Boolean, background: Color) {
        Row(Modifier.fillMaxWidth().background(background)) {
            for (c in 0 until columnCount) {
                InlineMarkdownText(
                    text = cells.getOrElse(c) { "" },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
                    fontWeight = if (header) FontWeight.SemiBold else null,
                    textAlign = when (table.aligns.getOrElse(c) { MdAlign.Left }) {
                        MdAlign.Left -> TextAlign.Left
                        MdAlign.Center -> TextAlign.Center
                        MdAlign.Right -> TextAlign.Right
                    },
                )
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, outline, RoundedCornerShape(8.dp)),
    ) {
        rowCells(table.headers, header = true, background = headerBg)
        table.rows.forEach { row ->
            HorizontalDivider(color = outline)
            rowCells(row, header = false, background = Color.Transparent)
        }
    }
}
