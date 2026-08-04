package io.github.yearsyan.pi.ui.components

import io.github.yearsyan.pi.data.SavedSession
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
