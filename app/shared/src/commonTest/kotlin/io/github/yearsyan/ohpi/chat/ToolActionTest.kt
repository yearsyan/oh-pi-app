package io.github.yearsyan.ohpi.chat

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

    @Test
    fun fileNameDropsDirectoryPrefix() {
        assertEquals("index.ts", fileNameOf("src/components/index.ts"))
        assertEquals("server.go", fileNameOf("/home/dev/workspace/oh-pi-app/internal/gateway/server.go"))
        assertEquals("app.kt", fileNameOf("app.kt"))
        assertEquals("win.kt", fileNameOf("C:\\repo\\win.kt"))
        assertEquals("dir", fileNameOf("a/b/dir/"))
    }

    @Test
    fun longFilePathsStayWholeSoTheBasenameIsNotMangled() {
        val longPath = "/home/dev/workspace/oh-pi-app/" + "x".repeat(80) + "/MessageItems.kt"
        val action = toolAction("edit", """{"path":"$longPath"}""")
        // the full path must survive parsing so fileNameOf can find the real basename;
        // truncating it would turn the name into a "…" fragment
        assertEquals(longPath, action.target)
        assertEquals("MessageItems.kt", fileNameOf(action.target))
    }
}
