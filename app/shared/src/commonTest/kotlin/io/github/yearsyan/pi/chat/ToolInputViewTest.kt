package io.github.yearsyan.pi.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolInputViewTest {
    @Test
    fun writeToolShowsFileContentInsteadOfJson() {
        assertEquals(
            ToolInputView.FileContent("fun main() {\n    println(\"hi\")\n}"),
            toolInputView("write", """{"path":"Main.kt","content":"fun main() {\n    println(\"hi\")\n}"}"""),
        )
    }

    @Test
    fun editToolShowsRemovedAndAddedLines() {
        assertEquals(
            ToolInputView.FileEdit(listOf(ToolInputView.EditHunk(oldText = "val a = 1", newText = "val a = 2"))),
            toolInputView("edit", """{"path":"A.kt","oldText":"val a = 1","newText":"val a = 2"}"""),
        )
    }

    @Test
    fun editToolAcceptsSnakeCaseFields() {
        assertEquals(
            ToolInputView.FileEdit(listOf(ToolInputView.EditHunk(oldText = "x", newText = "y"))),
            toolInputView("edit", """{"old_string":"x","new_string":"y"}"""),
        )
    }

    @Test
    fun editToolParsesPiEditsArraySchema() {
        val args =
            """{
              "edits": [
                {"newText": "foo()", "oldText": "bar()"},
                {"newText": "one", "oldText": "two"}
              ],
              "path": "/tmp/MessageItems.kt"
            }""".trimIndent()
        assertEquals(
            ToolInputView.FileEdit(
                listOf(
                    ToolInputView.EditHunk(oldText = "bar()", newText = "foo()"),
                    ToolInputView.EditHunk(oldText = "two", newText = "one"),
                ),
            ),
            toolInputView("edit", args),
        )
    }

    @Test
    fun executeToolShowsTerminalCommand() {
        assertEquals(
            ToolInputView.Command("git status"),
            toolInputView("bash", """{"command":"git status"}"""),
        )
    }

    @Test
    fun readSearchAndListHideTheirInput() {
        assertEquals(ToolInputView.Hidden, toolInputView("read", """{"path":"a.kt"}"""))
        assertEquals(ToolInputView.Hidden, toolInputView("grep", """{"pattern":"foo"}"""))
        assertEquals(ToolInputView.Hidden, toolInputView("ls", """{"path":"."}"""))
        assertEquals(ToolInputView.Hidden, toolInputView("write", ""))
    }

    @Test
    fun unparseableOrUnknownToolsFallBackToRawJson() {
        val writeWithoutContent = """{"path":"a.kt"}"""
        assertEquals(ToolInputView.Raw(writeWithoutContent), toolInputView("write", writeWithoutContent))

        val unknown = """{"url":"https://example.com"}"""
        assertEquals(ToolInputView.Raw(unknown), toolInputView("browser", unknown))

        val notJson = "not-json"
        assertEquals(ToolInputView.Raw(notJson), toolInputView("bash", notJson))
    }
}
