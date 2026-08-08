package io.github.yearsyan.ohpi.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpanPainter
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.SpanDrawInstructions
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownExtendedSpans
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import io.github.yearsyan.ohpi.syntax.MAX_HIGHLIGHT_LENGTH
import io.github.yearsyan.ohpi.syntax.Syntax
import io.github.yearsyan.ohpi.theme.piExtras
import io.github.yearsyan.ohpi.theme.rememberCodeFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val highlightedCodeFence: MarkdownComponent = { model ->
    MarkdownCodeFence(
        content = model.content,
        node = model.node,
        style = model.typography.code,
    ) { code, language, style ->
        HighlightedCodeBlock(code = code, language = language, style = style)
    }
}

private val highlightedCodeBlock: MarkdownComponent = { model ->
    MarkdownCodeBlock(
        content = model.content,
        node = model.node,
        style = model.typography.code,
    ) { code, language, style ->
        HighlightedCodeBlock(code = code, language = language, style = style)
    }
}

/**
 * GFM renderer shared by chat messages and file previews.
 *
 * Parsing is handled asynchronously by JetBrains Markdown. Code fences use the
 * same client-side highlighter and language aliases as the file browser.
 */
@Composable
fun MarkdownView(markdown: String, modifier: Modifier = Modifier) {
    val extras = piExtras
    val colors = markdownColor(
        text = MaterialTheme.colorScheme.onSurface,
        codeBackground = extras.codeBackground,
        inlineCodeBackground = extras.codeBackground,
        dividerColor = MaterialTheme.colorScheme.outlineVariant,
        tableBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    val typography = ohPiMarkdownTypography()
    val components = remember {
        markdownComponents(
            codeFence = highlightedCodeFence,
            codeBlock = highlightedCodeBlock,
        )
    }
    // Compose's SpanStyle.background is always rectangular. Promote inline
    // code to a custom painter so wrapped fragments remain separate rounded
    // boxes without splitting selectable Markdown text.
    val extendedSpans = markdownExtendedSpans {
        remember(extras.codeBackground) {
            ExtendedSpans(InlineCodeSpanPainter(extras.codeBackground))
        }
    }
    val state = rememberMarkdownState(
        content = markdown,
        retainState = true,
    )

    SelectionContainer(modifier) {
        Markdown(
            markdownState = state,
            modifier = Modifier.fillMaxWidth(),
            colors = colors,
            typography = typography,
            padding = markdownPadding(
                block = 4.dp,
                list = 2.dp,
                listItemTop = 2.dp,
                listItemBottom = 2.dp,
                listIndent = 12.dp,
            ),
            dimens = markdownDimens(
                codeBackgroundCornerSize = 12.dp,
                tableCellPadding = 8.dp,
            ),
            components = components,
            extendedSpans = extendedSpans,
        )
    }
}

@Composable
private fun ohPiMarkdownTypography() = markdownTypography(
    h1 = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    h2 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    h3 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    h4 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    h5 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    h6 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    text = markdownBodyStyle(),
    paragraph = markdownBodyStyle(),
    ordered = markdownBodyStyle(),
    bullet = markdownBodyStyle(),
    list = markdownBodyStyle(),
    quote = markdownBodyStyle().copy(fontStyle = FontStyle.Italic),
    code = MaterialTheme.typography.bodySmall.copy(
        fontFamily = rememberCodeFontFamily(),
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
    ),
    inlineCode = markdownBodyStyle().copy(
        fontFamily = rememberCodeFontFamily(),
        fontSize = 12.sp,
    ),
    textLink = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.tertiary,
            textDecoration = TextDecoration.Underline,
        ),
    ),
    table = MaterialTheme.typography.bodySmall.copy(lineHeight = 17.sp),
)

@Composable
private fun markdownBodyStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp)

private class InlineCodeSpanPainter(
    private val backgroundColor: Color,
    private val cornerRadius: TextUnit = 6.sp,
    private val horizontalPadding: TextUnit = 0.sp,
    private val verticalInset: TextUnit = 2.sp,
) : ExtendedSpanPainter() {
    override fun decorate(
        span: SpanStyle,
        start: Int,
        end: Int,
        text: AnnotatedString,
        builder: AnnotatedString.Builder,
    ): SpanStyle {
        if (span.background == Color.Unspecified) return span

        builder.addStringAnnotation(
            tag = INLINE_CODE_BACKGROUND_TAG,
            annotation = "inline-code",
            start = start,
            end = end,
        )
        return span.copy(background = Color.Unspecified)
    }

    override fun decorate(
        linkAnnotation: LinkAnnotation,
        start: Int,
        end: Int,
        text: AnnotatedString,
        builder: AnnotatedString.Builder,
    ): LinkAnnotation = linkAnnotation

    override fun drawInstructionsFor(
        layoutResult: TextLayoutResult,
        color: Color?,
    ): SpanDrawInstructions {
        val text = layoutResult.layoutInput.text
        val fragmentBounds = text
            .getStringAnnotations(INLINE_CODE_BACKGROUND_TAG, 0, text.length)
            .flatMap { range ->
                // Do not flatten full-paragraph spans: every visual line gets
                // its own complete rounded box when inline code wraps.
                layoutResult.getBoundingBoxes(range.start, range.end)
            }

        return SpanDrawInstructions {
            val radiusPx = cornerRadius.toPx()
            val horizontalPaddingPx = horizontalPadding.toPx()
            val verticalInsetPx = verticalInset.toPx()

            fragmentBounds.forEach { bounds ->
                // Compose may report a zero-width box on the previous line
                // when an entire code token wraps. Padding that box would
                // otherwise leave a stray rounded pill at the line ending.
                if (bounds.width <= 0.5f) return@forEach

                val left = (bounds.left - horizontalPaddingPx).coerceAtLeast(0f)
                val right = (bounds.right + horizontalPaddingPx).coerceAtMost(size.width)
                val top = bounds.top + verticalInsetPx
                val bottom = bounds.bottom - verticalInsetPx

                if (right > left && bottom > top) {
                    drawRoundRect(
                        color = backgroundColor,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        cornerRadius = CornerRadius(radiusPx),
                    )
                }
            }
        }
    }
}

private const val INLINE_CODE_BACKGROUND_TAG = "oh-pi-inline-code-background"

@Composable
private fun HighlightedCodeBlock(
    code: String,
    language: String?,
    style: TextStyle,
) {
    val extras = piExtras
    val spec = remember(language) { language?.let(Syntax::specForLangName) }
    val highlighted by produceState(
        initialValue = AnnotatedString(code),
        key1 = code,
        key2 = spec,
        key3 = extras.syntax,
    ) {
        if (spec != null && code.length <= MAX_HIGHLIGHT_LENGTH) {
            value = withContext(Dispatchers.Default) {
                Syntax.highlight(code, spec, extras.syntax)
            }
        }
    }
    val languageLabel = remember(language) {
        language
            ?.trim()
            ?.substringBefore(' ')
            ?.substringBefore('\t')
            ?.removePrefix("{.")
            ?.removePrefix(".")
            ?.removeSuffix("}")
            .orEmpty()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(extras.codeBackground, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        if (languageLabel.isNotBlank()) {
            Text(
                text = languageLabel,
                modifier = Modifier.padding(bottom = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = highlighted,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            style = style,
            color = extras.syntax.plain,
            softWrap = false,
        )
    }
}
