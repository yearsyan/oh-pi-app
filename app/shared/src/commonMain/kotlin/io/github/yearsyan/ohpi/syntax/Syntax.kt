package io.github.yearsyan.ohpi.syntax

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme

/** Above this size previews stay plain to bound token and span allocations. */
const val MAX_HIGHLIGHT_LENGTH = 512 * 1024

/** Token categories produced by the lightweight tokenizer. */
enum class TokenType { Keyword, String, Comment, Number, Annotation }

/** Half-open source range [start, end) carrying one token type. */
data class Token(val start: Int, val end: Int, val type: TokenType)

/** Per-token colors for one theme (light/dark). */
@Immutable
data class SyntaxColors(
    val plain: Color,
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
    /** String delimiters that may span lines, checked before [stringQuotes]. */
    val multilineStringDelimiters: List<Pair<String, String>> = emptyList(),
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

    /**
     * Resolves a language spec from a markdown fence tag, e.g. ```kotlin.
     * Common info-string forms such as `kotlin linenums` and `{.kotlin}` are accepted.
     */
    fun specForLangName(lang: String): LanguageSpec? = byLangName[normalizeLangName(lang)]

    /** Splits [code] into styled token ranges for [spec]. */
    fun tokenize(code: String, spec: LanguageSpec): List<Token> =
        if (spec.markup) tokenizeMarkup(code) else tokenizeCode(code, spec)

    /**
     * Builds an [AnnotatedString] with [colors] applied to [code].
     *
     * Highlights is the primary engine for supported programming languages. The
     * local tokenizer remains as a deterministic fallback for data, markup, and
     * stylesheet formats that Highlights does not support.
     */
    fun highlight(code: String, spec: LanguageSpec, colors: SyntaxColors): AnnotatedString {
        engineLanguages[spec.name]?.let { language ->
            highlightWithEngine(code, spec, language, colors)?.let { return it }
        }
        return highlightWithFallback(code, spec, colors)
    }

    /** True when [spec] is handled by the third-party highlighting engine. */
    fun usesHighlightEngine(spec: LanguageSpec): Boolean = spec.name in engineLanguages

    private fun highlightWithEngine(
        code: String,
        spec: LanguageSpec,
        language: SyntaxLanguage,
        colors: SyntaxColors,
    ): AnnotatedString? = runCatching {
        // Highlights 1.1 uses a shared comment/string scanner for every language.
        // Mask ranges already recognized by our language-aware scanner so URLs,
        // hashes inside strings, and language-specific comments cannot bleed.
        val localTokens = tokenize(code, spec)
        val protectedTokens = localTokens.filter {
            it.type == TokenType.String ||
                it.type == TokenType.Comment ||
                it.type == TokenType.Annotation
        }
        val engineInput = maskProtectedRanges(code, protectedTokens)
        val highlights = Highlights.Builder()
            .code(engineInput)
            .language(language)
            .theme(colors.toSyntaxTheme())
            .build()
            .getHighlights()
        val plainArgb = colors.plain.toArgb()

        buildAnnotatedString {
            append(code)
            for (highlight in highlights) {
                val start = highlight.location.start.coerceIn(0, code.length)
                val end = highlight.location.end.coerceIn(start, code.length)
                if (start == end) continue
                val style = when (highlight) {
                    is ColorHighlight -> {
                        // Local specs fill gaps in the engine grammars (notably
                        // Bash control flow). Avoid duplicating exact color spans;
                        // local tokens are applied authoritatively below.
                        if (localTokens.hasExactRange(start, end)) continue
                        // Marks and punctuation use the base text color, so an
                        // explicit span only adds memory and layout work.
                        if (highlight.rgb == plainArgb) continue
                        SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f))
                    }
                    is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
                }
                addStyle(style, start, end)
            }
            for (token in localTokens) {
                addStyle(styleFor(token.type, colors), token.start, token.end)
            }
        }
    }.getOrNull()

    /** [tokenize] emits source-ordered, non-overlapping tokens, so binary search is enough. */
    private fun List<Token>.hasExactRange(start: Int, end: Int): Boolean {
        var low = 0
        var high = lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val token = this[middle]
            when {
                token.start < start -> low = middle + 1
                token.start > start -> high = middle - 1
                else -> return token.end == end
            }
        }
        return false
    }

    private fun maskProtectedRanges(code: String, tokens: List<Token>): String {
        if (tokens.isEmpty()) return code
        val chars = code.toCharArray()
        for (token in tokens) {
            for (index in token.start until token.end) {
                if (chars[index] != '\n' && chars[index] != '\r') chars[index] = ' '
            }
        }
        return chars.concatToString()
    }

    private fun highlightWithFallback(
        code: String,
        spec: LanguageSpec,
        colors: SyntaxColors,
    ): AnnotatedString {
        val tokens = tokenize(code, spec)
        if (tokens.isEmpty()) return AnnotatedString(code)
        return buildAnnotatedString {
            append(code)
            for (token in tokens) {
                addStyle(styleFor(token.type, colors), token.start, token.end)
            }
        }
    }

    private fun SyntaxColors.toSyntaxTheme(): SyntaxTheme = SyntaxTheme(
        key = "oh-pi",
        code = plain.toArgb(),
        keyword = keyword.toArgb(),
        string = string.toArgb(),
        literal = number.toArgb(),
        comment = comment.toArgb(),
        metadata = annotation.toArgb(),
        multilineComment = comment.toArgb(),
        punctuation = plain.toArgb(),
        mark = plain.toArgb(),
    )

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
        val multilineStrings = spec.multilineStringDelimiters.sortedByDescending { it.first.length }
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

            val multilineString = multilineStrings.firstOrNull { code.startsWith(it.first, i) }
            if (multilineString != null) {
                var end = i + multilineString.first.length
                while (end < n) {
                    when {
                        code[end] == '\\' -> end = (end + 2).coerceAtMost(n)
                        code.startsWith(multilineString.second, end) -> {
                            end += multilineString.second.length
                            break
                        }
                        else -> end++
                    }
                }
                tokens += Token(i, end.coerceAtMost(n), TokenType.String)
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

    private val engineLanguages: Map<String, SyntaxLanguage> = mapOf(
        "c" to SyntaxLanguage.C,
        "cpp" to SyntaxLanguage.CPP,
        "dart" to SyntaxLanguage.DART,
        "java" to SyntaxLanguage.JAVA,
        "kotlin" to SyntaxLanguage.KOTLIN,
        "rust" to SyntaxLanguage.RUST,
        "csharp" to SyntaxLanguage.CSHARP,
        "coffeescript" to SyntaxLanguage.COFFEESCRIPT,
        "javascript" to SyntaxLanguage.JAVASCRIPT,
        "perl" to SyntaxLanguage.PERL,
        "python" to SyntaxLanguage.PYTHON,
        "ruby" to SyntaxLanguage.RUBY,
        "shell" to SyntaxLanguage.SHELL,
        "swift" to SyntaxLanguage.SWIFT,
        "typescript" to SyntaxLanguage.TYPESCRIPT,
        "go" to SyntaxLanguage.GO,
        "php" to SyntaxLanguage.PHP,
    )

    private fun normalizeLangName(language: String): String {
        val token = language.trim()
            .substringBefore(' ')
            .substringBefore('\t')
            .lowercase()
        return token.removePrefix("{.").removePrefix(".").removeSuffix("}")
    }
}
