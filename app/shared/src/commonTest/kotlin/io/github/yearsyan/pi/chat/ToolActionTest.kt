package io.github.yearsyan.pi.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolActionTest {
    @Test
    fun derivesFriendlyActionsFromKnownTools() {
        assertEquals(
            ToolAction(ToolActionKind.Execute, "git status --short"),
            toolAction("bash", """{"command":"git status --short"}"""),
        )
        assertEquals(
            ToolAction(ToolActionKind.Read, "README.md"),
            toolAction("read", """{"path":"README.md"}"""),
        )
        assertEquals(
            ToolAction(ToolActionKind.Write, "src/App.kt"),
            toolAction("write", """{"path":"src/App.kt","content":"..."}"""),
        )
    }

    @Test
    fun unknownToolFallsBackToItsName() {
        assertEquals(
            ToolAction(ToolActionKind.Call, "browser"),
            toolAction("browser", "{}"),
        )
    }
}
