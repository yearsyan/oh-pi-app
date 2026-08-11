package io.github.yearsyan.ohpi.ui.components

import io.github.yearsyan.ohpi.data.WorkspaceSummary
import io.github.yearsyan.ohpi.data.SavedSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionListOrderingTest {
    @Test
    fun workspaceDisplayNameUsesMetadataBeforeDirectory() {
        assertEquals(
            "Mobile client",
            WorkspaceSummary(id = "one", directory = "/srv/app", name = "Mobile client").displayName,
        )
        assertEquals("app", WorkspaceSummary(id = "two", directory = "/srv/app").displayName)
        assertEquals("app", WorkspaceSummary(id = "three", directory = "C:\\src\\app\\").displayName)
    }

    @Test
    fun technologyBadgesCoverPrimaryDetectedStacks() {
        assertEquals("Go", technologyVisual("go").label)
        assertEquals("Rs", technologyVisual("rust").label)
        assertEquals("TS", technologyVisual("typescript").label)
        assertEquals("V", technologyVisual("vite").label)
        assertEquals("<>", technologyVisual("unknown").label)
    }

    @Test
    fun onlyWorkspacesWithActivityInTheLastThreeDaysExpandByDefault() {
        val now = 10L * 24 * 60 * 60 * 1_000
        val recent =
            WorkspaceSummary(
                id = "recent",
                directory = "/recent",
                sessions = listOf(SavedSession(id = "chat", lastActive = now - 2 * 24 * 60 * 60 * 1_000)),
            )
        val stale =
            WorkspaceSummary(
                id = "stale",
                directory = "/stale",
                sessions = listOf(SavedSession(id = "chat", lastActive = now - 4 * 24 * 60 * 60 * 1_000)),
            )

        assertFalse(workspaceCollapsedByDefault(recent, now))
        assertTrue(workspaceCollapsedByDefault(stale, now))
        assertTrue(workspaceCollapsedByDefault(WorkspaceSummary("empty", "/empty"), now))
    }

    @Test
    fun runningWorkspaceExpandsEvenWhenItsTimestampIsOld() {
        val workspace =
            WorkspaceSummary(
                id = "running",
                directory = "/running",
                sessions = listOf(SavedSession(id = "chat", lastActive = 1L, running = true)),
            )

        assertFalse(workspaceCollapsedByDefault(workspace, now = 20L * 24 * 60 * 60 * 1_000))
    }
}
