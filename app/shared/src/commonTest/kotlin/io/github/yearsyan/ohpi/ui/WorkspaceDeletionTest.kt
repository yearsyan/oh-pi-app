package io.github.yearsyan.ohpi.ui

import io.github.yearsyan.ohpi.data.SavedSession
import io.github.yearsyan.ohpi.data.WorkspaceSummary
import io.github.yearsyan.ohpi.net.WorkspaceSessionPage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkspaceDeletionTest {
    @Test
    fun loadsEverySessionPageBeforeWorkspaceDeletion() = runTest {
        val workspace = WorkspaceSummary(id = "workspace", directory = "/workspace")
        val calls = mutableListOf<Pair<String, Int>>()

        val sessions = loadAllWorkspaceSessions(workspace) { _, cursor, limit ->
            calls += cursor to limit
            val start = cursor.ifBlank { "0" }.toInt()
            val end = minOf(start + limit, 230)
            WorkspaceSessionPage(
                sessions = List(end - start) { offset -> SavedSession(id = "session-${start + offset}") },
                nextCursor = if (end < 230) end.toString() else "",
            )
        }

        assertEquals(listOf("" to 100, "100" to 100, "200" to 100), calls)
        assertEquals(230, sessions.size)
        assertEquals("session-229", sessions.last().id)
    }

    @Test
    fun rejectsRepeatedPaginationCursorInsteadOfLoopingForever() = runTest {
        val workspace = WorkspaceSummary(id = "workspace", directory = "/workspace")

        assertFailsWith<IllegalStateException> {
            loadAllWorkspaceSessions(workspace) { _, _, _ ->
                WorkspaceSessionPage(emptyList(), nextCursor = "same")
            }
        }
    }
}
