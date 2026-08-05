package io.github.yearsyan.pi.syntax

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle

/** Token categories produced by the lightweight tokenizer. */
enum class TokenType { Keyword, String, Comment, Number, Annotation }

/** Half-open source range [start, end) carrying one token type. */
data class Token(val start: Int, val end: Int, val type: TokenType)

/** Per-token colors for one theme (light/dark). */
@Immutable
data class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val annotation: Color,
)

/**
 * Declarative description of one programming language for the tokenizer.
 *
 * The tokenizer is deliberately approximate: it recognizes comments, strings,
 * numbers, identifiers matched against [keywords], and `@`-style annotations.
 * That is enough for read-only preview highlighting without a full parser.
 */
data class LanguageSpec(
    val name: String,
    val extensions: List<String>,
    val keywords: Set<String>,
    val caseInsensitiveKeywords: Boolean = false,
    val lineComments: List<String> = listOf("//"),
    val blockComments: List<Pair<String, String>> = listOf("/*" to "*/"),
    val stringQuotes: Set<Char> = setOf('"', '\''),
    val annotationChar: Char? = null,
    /** Markup mode (XML/HTML): highlights tag names, strings and comments. */
    val markup: Boolean = false,
    /** Extra names accepted by [Syntax.specForLangName], e.g. markdown fence tags. */
    val langAliases: List<String> = emptyList(),
)

/** Approximate, allocation-lean syntax highlighting for read-only previews. */
object Syntax {

    /** Resolves a language spec from a file name extension, or null if unknown. */
    fun specForFileName(fileName: String): LanguageSpec? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext == fileName.lowercase()) return null
        return byExtension[ext]
    }

    /** Resolves a language spec from a markdown fence tag, e.g. ```kotlin. */
    fun specForLangName(lang: String): LanguageSpec? = byLangName[lang.trim().lowercase()]

    /** Splits [code] into styled token ranges for [spec]. */
    fun tokenize(code: String, spec: LanguageSpec): List<Token> =
        if (spec.markup) tokenizeMarkup(code) else tokenizeCode(code, spec)

    /** Builds an [AnnotatedString] with [colors] applied to tokens of [spec]. */
    fun highlight(code: String, spec: LanguageSpec, colors: SyntaxColors): AnnotatedString {
        val tokens = tokenize(code, spec)
        if (tokens.isEmpty()) return AnnotatedString(code)
        return buildAnnotatedString {
            append(code)
            for (token in tokens) {
                addStyle(styleFor(token.type, colors), token.start, token.end)
            }
        }
    }

    private fun styleFor(type: TokenType, colors: SyntaxColors): SpanStyle = when (type) {
        TokenType.Keyword -> SpanStyle(color = colors.keyword)
        TokenType.String -> SpanStyle(color = colors.string)
        TokenType.Comment -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
        TokenType.Number -> SpanStyle(color = colors.number)
        TokenType.Annotation -> SpanStyle(color = colors.annotation)
    }

    private fun tokenizeCode(code: String, spec: LanguageSpec): List<Token> {
        val tokens = ArrayList<Token>()
        val keywords = if (spec.caseInsensitiveKeywords) {
            spec.keywords.mapTo(HashSet()) { it.lowercase() }
        } else {
            spec.keywords
        }
        // Longest prefix first so "--" wins over "-" style overlaps.
        val lineComments = spec.lineComments.sortedByDescending { it.length }
        val n = code.length
        var i = 0
        while (i < n) {
            val c = code[i]

            val lineComment = lineComments.firstOrNull { code.startsWith(it, i) }
            if (lineComment != null) {
                var end = code.indexOf('\n', i + lineComment.length)
                if (end < 0) end = n
                tokens += Token(i, end, TokenType.Comment)
                i = end
                continue
            }

            val blockComment = spec.blockComments.firstOrNull { code.startsWith(it.first, i) }
            if (blockComment != null) {
                var end = code.indexOf(blockComment.second, i + blockComment.first.length)
                end = if (end < 0) n else end + blockComment.second.length
                tokens += Token(i, end, TokenType.Comment)
                i = end
                continue
            }

            if (c in spec.stringQuotes) {
                var end = i + 1
                while (end < n) {
                    when (code[end]) {
                        '\\' -> end += 2
                        c -> { end++; break }
                        '\n' -> break // unterminated; do not swallow following lines
                        else -> end++
                    }
                }
                tokens += Token(i, end.coerceAtMost(n), TokenType.String)
                i = end
                continue
            }

            if (c.isDigit()) {
                // Covers 0x…, 1e10, 3.14f, 100L and friends in one scan.
                var end = i + 1
                while (end < n && (code[end].isLetterOrDigit() || code[end] == '.' || code[end] == '_')) end++
                tokens += Token(i, end, TokenType.Number)
                i = end
                continue
            }

            val annotationChar = spec.annotationChar
            if (annotationChar != null && c == annotationChar &&
                i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '_')
            ) {
                var end = i + 2
                while (end < n && (code[end].isLetterOrDigit() || code[end] == '_')) end++
                tokens += Token(i, end, TokenType.Annotation)
                i = end
                continue
            }

            if (c.isLetter() || c == '_') {
                var end = i + 1
                while (end < n && (code[end].isLetterOrDigit() || code[end] == '_')) end++
                val word = code.substring(i, end)
                val lookup = if (spec.caseInsensitiveKeywords) word.lowercase() else word
                if (lookup in keywords) tokens += Token(i, end, TokenType.Keyword)
                i = end
                continue
            }

            i++
        }
        return tokens
    }

    private fun tokenizeMarkup(code: String): List<Token> {
        val tokens = ArrayList<Token>()
        val n = code.length
        var i = 0
        while (i < n) {
            if (code.startsWith("<!--", i)) {
                var end = code.indexOf("-->", i + 4)
                end = if (end < 0) n else end + 3
                tokens += Token(i, end, TokenType.Comment)
                i = end
                continue
            }
            val c = code[i]
            if (c == '"' || c == '\'') {
                var end = i + 1
                while (end < n && code[end] != c) end++
                if (end < n) end++
                tokens += Token(i, end, TokenType.String)
                i = end
                continue
            }
            if (c == '<') {
                var nameStart = i + 1
                if (nameStart < n && (code[nameStart] == '/' || code[nameStart] == '!' || code[nameStart] == '?')) {
                    nameStart++
                }
                var end = nameStart
                while (end < n && (code[end].isLetterOrDigit() || code[end] == '-' || code[end] == '_' || code[end] == ':')) end++
                if (end > nameStart) tokens += Token(nameStart, end, TokenType.Keyword)
                i = end
                continue
            }
            i++
        }
        return tokens
    }

    private val byExtension: Map<String, LanguageSpec> =
        Languages.all.flatMap { spec -> spec.extensions.map { it to spec } }.toMap()

    private val byLangName: Map<String, LanguageSpec> =
        Languages.all
            .flatMap { spec -> (listOf(spec.name) + spec.extensions + spec.langAliases).map { it to spec } }
            .toMap()
}
