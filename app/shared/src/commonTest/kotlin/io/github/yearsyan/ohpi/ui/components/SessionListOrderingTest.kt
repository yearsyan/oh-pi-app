package io.github.yearsyan.ohpi.ui.components

import io.github.yearsyan.ohpi.data.SavedSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionListOrderingTest {
    @Test
    fun groupsAndSessionsUseNewestCreationTime() {
        val groups = groupSessionsByCreation(
            listOf(
                SavedSession(id = "workspace-a-old", createdAt = 100, lastActive = 900, workDir = "/a"),
                SavedSession(id = "workspace-b", createdAt = 200, lastActive = 800, workDir = "/b"),
                SavedSession(id = "workspace-a-new", createdAt = 300, lastActive = 400, workDir = "/a"),
            ),
        )

        assertEquals(listOf("/a", "/b"), groups.map { it.workDir })
        assertEquals(
            listOf("workspace-a-new", "workspace-a-old"),
            groups.first().sessions.map { it.id },
        )
    }

    @Test
    fun previewKeepsActiveSessionVisibleWithoutDuplicatingIt() {
        val sessions =
            (0 until 12).map { index ->
                SavedSession(id = "session-$index", createdAt = 100L - index, workDir = "/workspace")
            }

        val withOlderActive = workspaceSessionPreview(sessions, activeChatId = "session-11", expanded = false)
        assertEquals(11, withOlderActive.size)
        assertTrue(withOlderActive.any { it.id == "session-11" })
        assertFalse(withOlderActive.any { it.id == "session-10" })

        val withRecentActive = workspaceSessionPreview(sessions, activeChatId = "session-2", expanded = false)
        assertEquals(10, withRecentActive.size)
        assertEquals(1, withRecentActive.count { it.id == "session-2" })

        assertEquals(sessions, workspaceSessionPreview(sessions, activeChatId = "session-11", expanded = true))
    }
}
