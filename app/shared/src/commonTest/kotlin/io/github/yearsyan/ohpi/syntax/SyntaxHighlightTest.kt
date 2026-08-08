package io.github.yearsyan.ohpi.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyntaxHighlightTest {

    private val colors = SyntaxColors(
        plain = androidx.compose.ui.graphics.Color.Black,
        keyword = androidx.compose.ui.graphics.Color.Red,
        string = androidx.compose.ui.graphics.Color.Green,
        comment = androidx.compose.ui.graphics.Color.Gray,
        number = androidx.compose.ui.graphics.Color.Blue,
        annotation = androidx.compose.ui.graphics.Color.Magenta,
    )

    private fun spec(name: String): LanguageSpec =
        requireNotNull(Syntax.specForLangName(name)) { "missing spec for $name" }

    private fun tokensOf(code: String, lang: String, type: TokenType): List<String> =
        Syntax.tokenize(code, spec(lang))
            .filter { it.type == type }
            .map { code.substring(it.start, it.end) }

    @Test
    fun resolvesSpecsByExtensionAndFenceName() {
        assertEquals("kotlin", Syntax.specForFileName("Main.kt")?.name)
        assertEquals("typescript", Syntax.specForFileName("app/screen.tsx")?.name)
        assertEquals("shell", Syntax.specForFileName("deploy.sh")?.name)
        assertEquals("xml", Syntax.specForFileName("index.html")?.name)
        assertNull(Syntax.specForFileName("README"))
        assertNull(Syntax.specForFileName("notes.unknownext"))

        assertEquals("go", Syntax.specForLangName("golang")?.name)
        assertEquals("cpp", Syntax.specForLangName("c++")?.name)
        assertEquals("python", Syntax.specForLangName("py")?.name)
        assertEquals("javascript", Syntax.specForLangName("js linenums")?.name)
        assertEquals("typescript", Syntax.specForLangName("{.ts}")?.name)
        assertEquals("csharp", Syntax.specForLangName("c#")?.name)
        assertEquals("swift", Syntax.specForFileName("Client.swift")?.name)
        assertEquals("ruby", Syntax.specForFileName("build.gemspec")?.name)
        assertEquals("php", Syntax.specForFileName("index.PHP")?.name)
        assertNull(Syntax.specForLangName(""))
        assertNull(Syntax.specForLangName("not-a-language"))
    }

    @Test
    fun highlightsKotlinKeywordsAndComments() {
        val code = """
            package demo

            // entry point
            fun main() {
                val answer = 42
                println("answer is ${'$'}answer") // trailing
            }
        """.trimIndent()
        val tokens = Syntax.tokenize(code, spec("kotlin"))

        val keywords = tokens.filter { it.type == TokenType.Keyword }
            .map { code.substring(it.start, it.end) }
        assertTrue("package" in keywords)
        assertTrue("fun" in keywords)
        assertTrue("val" in keywords)
        // Identifiers must not be keywords.
        assertTrue("answer" !in keywords)
        assertTrue("println" !in keywords)

        val comments = tokens.filter { it.type == TokenType.Comment }
            .map { code.substring(it.start, it.end) }
        assertEquals(listOf("// entry point", "// trailing"), comments)

        val strings = tokens.filter { it.type == TokenType.String }
            .map { code.substring(it.start, it.end) }
        assertEquals(listOf("\"answer is ${'$'}answer\""), strings)

        val numbers = tokens.filter { it.type == TokenType.Number }
            .map { code.substring(it.start, it.end) }
        assertEquals(listOf("42"), numbers)
    }

    @Test
    fun keywordsInsideStringsAndCommentsAreNotHighlighted() {
        val code = "\"fun val\" // class return"
        val tokens = Syntax.tokenize(code, spec("kotlin"))
        assertTrue(tokens.none { it.type == TokenType.Keyword })
        assertEquals(1, tokens.count { it.type == TokenType.String })
        assertEquals(1, tokens.count { it.type == TokenType.Comment })
    }

    @Test
    fun handlesBlockCommentsAndAnnotations() {
        val code = "/* multi\nline */ @Override\nvoid run() {}"
        val tokens = Syntax.tokenize(code, spec("java"))
        val comment = tokens.single { it.type == TokenType.Comment }
        assertEquals("/* multi\nline */", code.substring(comment.start, comment.end))
        val annotation = tokens.single { it.type == TokenType.Annotation }
        assertEquals("@Override", code.substring(annotation.start, annotation.end))
        assertTrue(tokens.any { it.type == TokenType.Keyword && code.substring(it.start, it.end) == "void" })
    }

    @Test
    fun sqlKeywordsAreCaseInsensitive() {
        val code = "SELECT name FROM users WHERE id = 7 -- lookup"
        val keywords = tokensOf(code, "sql", TokenType.Keyword)
        assertEquals(listOf("SELECT", "FROM", "WHERE"), keywords)
        assertEquals(listOf("-- lookup"), tokensOf(code, "sql", TokenType.Comment))
        assertEquals(listOf("7"), tokensOf(code, "sql", TokenType.Number))
    }

    @Test
    fun shellUsesHashCommentsWithoutStringEscapeBleed() {
        val code = "if [ -f \"a.txt\" ]; then # check\necho done\nfi"
        val tokens = Syntax.tokenize(code, spec("shell"))
        val comments = tokens.filter { it.type == TokenType.Comment }
            .map { code.substring(it.start, it.end) }
        assertEquals(listOf("# check"), comments)
        val keywords = tokens.filter { it.type == TokenType.Keyword }
            .map { code.substring(it.start, it.end) }
        assertTrue("if" in keywords && "then" in keywords && "fi" in keywords)
    }

    @Test
    fun highlightedShellCommandStylesControlFlowStringsNumbersAndComments() {
        val code = """
            if [ -n "${'$'}TOKEN" ]; then
              printf '%s\n' "ready # literal"
              sleep 2
            fi # done
        """.trimIndent()
        val result = Syntax.highlight(code, spec("bash"), colors)

        fun textWithColor(color: androidx.compose.ui.graphics.Color): List<String> =
            result.spanStyles
                .filter { it.item.color == color }
                .map { code.substring(it.start, it.end) }

        val keywords = textWithColor(colors.keyword)
        assertTrue("if" in keywords && "then" in keywords && "fi" in keywords)
        assertEquals(
            listOf("\"${'$'}TOKEN\"", "'%s\\n'", "\"ready # literal\""),
            textWithColor(colors.string),
        )
        assertTrue("2" in textWithColor(colors.number))
        assertEquals(listOf("# done"), textWithColor(colors.comment))
    }

    @Test
    fun shellQuotesCanSpanLinesWithoutColoringTheFollowingPipeline() {
        val code = "python3 -c \"print('value')\nprint(2)\" 2>/dev/null | head -1"

        assertEquals(
            listOf("\"print('value')\nprint(2)\""),
            tokensOf(code, "bash", TokenType.String),
        )
        assertEquals(listOf("2", "1"), tokensOf(code, "bash", TokenType.Number))

        val result = Syntax.highlight(code, spec("bash"), colors)
        val strings = result.spanStyles
            .filter { it.item.color == colors.string }
            .map { code.substring(it.start, it.end) }
        assertEquals(listOf("\"print('value')\nprint(2)\""), strings)
    }

    @Test
    fun markupHighlightsTagNamesStringsAndComments() {
        val code = "<!-- head --><div class=\"box\">text</div>"
        val tokens = Syntax.tokenize(code, spec("html"))
        val tags = tokens.filter { it.type == TokenType.Keyword }
            .map { code.substring(it.start, it.end) }
        assertEquals(listOf("div", "div"), tags)
        val strings = tokens.filter { it.type == TokenType.String }
            .map { code.substring(it.start, it.end) }
        assertEquals(listOf("\"box\""), strings)
        val comments = tokens.filter { it.type == TokenType.Comment }
            .map { code.substring(it.start, it.end) }
        assertEquals(listOf("<!-- head -->"), comments)
    }

    @Test
    fun unterminatedStringStopsAtNewline() {
        val code = "val s = \"oops\nval t = 1"
        val tokens = Syntax.tokenize(code, spec("kotlin"))
        val string = tokens.single { it.type == TokenType.String }
        assertEquals("\"oops", code.substring(string.start, string.end))
        // The keyword on the next line must still be found.
        assertTrue(tokens.any { it.type == TokenType.Keyword && code.substring(it.start, it.end) == "val" })
    }

    @Test
    fun unterminatedBlockCommentConsumesToEnd() {
        val code = "int a; /* never closed\nint b;"
        val tokens = Syntax.tokenize(code, spec("c"))
        val comment = tokens.single { it.type == TokenType.Comment }
        assertEquals("/* never closed\nint b;", code.substring(comment.start, comment.end))
    }

    @Test
    fun cPreprocessorIsHighlightedAsAnnotation() {
        val code = "#include <stdio.h>"
        val annotations = tokensOf(code, "c", TokenType.Annotation)
        assertEquals(listOf("#include"), annotations)
    }

    @Test
    fun highlightProducesStylesCoveringTokens() {
        val code = "fun f() = 1 // tail"
        val result = Syntax.highlight(code, spec("kotlin"), colors)
        assertEquals(code, result.text)
        assertNotNull(result.spanStyles.firstOrNull())
        assertTrue(Syntax.usesHighlightEngine(spec("kotlin")))
        assertTrue(!Syntax.usesHighlightEngine(spec("yaml")))
    }

    @Test
    fun engineDoesNotTreatUrlOrFragmentInsideStringAsComment() {
        val code = """
            const url = "https://example.test/#anchor"
            return url
        """.trimIndent()
        val result = Syntax.highlight(code, spec("javascript"), colors)

        val strings = result.spanStyles
            .filter { it.item.color == colors.string }
            .map { code.substring(it.start, it.end) }
        assertEquals(listOf("\"https://example.test/#anchor\""), strings)
        assertTrue(result.spanStyles.none { it.item.color == colors.comment })
        assertTrue(
            result.spanStyles.any {
                it.item.color == colors.keyword && code.substring(it.start, it.end) == "return"
            },
        )
    }

    @Test
    fun multilinePythonStringProtectsCommentMarkers() {
        val code = "message = \"\"\"first\n# still a string\nsecond\"\"\"\nreturn message"
        val result = Syntax.highlight(code, spec("python"), colors)

        val stringRange = result.spanStyles.single { it.item.color == colors.string }
        assertEquals("\"\"\"first\n# still a string\nsecond\"\"\"", code.substring(stringRange.start, stringRange.end))
        assertTrue(result.spanStyles.none { it.item.color == colors.comment })
    }
}
