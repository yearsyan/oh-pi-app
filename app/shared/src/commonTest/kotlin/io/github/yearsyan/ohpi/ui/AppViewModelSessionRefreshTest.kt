package io.github.yearsyan.ohpi.ui

import io.github.yearsyan.ohpi.data.SavedSession
import io.github.yearsyan.ohpi.data.WorkspaceSummary
import io.github.yearsyan.ohpi.net.WorkspaceSessionPage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppViewModelSessionRefreshTest {
    @Test
    fun refreshOnlyTracksWorkspacesExpandedBeyondThePreview() {
        val preview = workspace(id = "preview", visibleCount = 5, nextCursor = "next")
        val completePreview = workspace(id = "complete-preview", visibleCount = 5)
        val expanded = workspace(id = "expanded", visibleCount = 25, nextCursor = "next")
        val fullyExpanded = workspace(id = "fully-expanded", visibleCount = 25)

        val windows = expandedWorkspaceSessionWindows(
            listOf(preview, completePreview, expanded, fullyExpanded),
        )

        assertFalse("preview" in windows)
        assertFalse("complete-preview" in windows)
        assertEquals(
            ExpandedWorkspaceSessionWindow(visibleCount = 25, fullyLoaded = false),
            windows["expanded"],
        )
        assertEquals(
            ExpandedWorkspaceSessionWindow(visibleCount = 25, fullyLoaded = true),
            windows["fully-expanded"],
        )
    }

    @Test
    fun partialExpansionKeepsItsVisibleDepthAfterRefresh() {
        val window = ExpandedWorkspaceSessionWindow(visibleCount = 25, fullyLoaded = false)

        assertEquals(25, refreshedWorkspaceSessionTarget(workspace(sessionCount = 60), window))
        assertEquals(12, refreshedWorkspaceSessionTarget(workspace(sessionCount = 12), window))
    }

    @Test
    fun fullExpansionIncludesSessionsAddedBeforeRefresh() {
        val window = ExpandedWorkspaceSessionWindow(visibleCount = 25, fullyLoaded = true)

        assertEquals(27, refreshedWorkspaceSessionTarget(workspace(sessionCount = 27), window))
    }

    @Test
    fun refreshLoadsBackToThePreviousPartialExpansionDepth() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val refreshed = restoreExpandedWorkspaceSessions(
            workspaces = listOf(workspace(visibleCount = 5, sessionCount = 60, nextCursor = "5")),
            windows = mapOf(
                "workspace" to ExpandedWorkspaceSessionWindow(
                    visibleCount = 25,
                    fullyLoaded = false,
                ),
            ),
            loadPage = { workspace, cursor, limit ->
                calls += cursor to limit
                sessionPage(workspace, cursor, limit)
            },
        ).single()

        assertEquals(listOf("5" to 20), calls)
        assertEquals(25, refreshed.sessions.size)
        assertEquals("25", refreshed.nextCursor)
    }

    @Test
    fun refreshRestoresAFullExpansionAcrossMultiplePages() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val refreshed = restoreExpandedWorkspaceSessions(
            workspaces = listOf(workspace(visibleCount = 5, sessionCount = 127, nextCursor = "5")),
            windows = mapOf(
                "workspace" to ExpandedWorkspaceSessionWindow(
                    visibleCount = 25,
                    fullyLoaded = true,
                ),
            ),
            loadPage = { workspace, cursor, limit ->
                calls += cursor to limit
                sessionPage(workspace, cursor, limit)
            },
        ).single()

        assertEquals(listOf("5" to 100, "105" to 22), calls)
        assertEquals(127, refreshed.sessions.size)
        assertEquals("", refreshed.nextCursor)
    }

    private fun workspace(
        id: String = "workspace",
        visibleCount: Int = 0,
        sessionCount: Int = visibleCount,
        nextCursor: String = "",
    ) = WorkspaceSummary(
        id = id,
        directory = "/workspace/$id",
        sessionCount = sessionCount,
        sessions = List(visibleCount) { index -> SavedSession(id = "$id-$index") },
        nextCursor = nextCursor,
    )

    private fun sessionPage(
        workspace: WorkspaceSummary,
        cursor: String,
        limit: Int,
    ): WorkspaceSessionPage {
        val start = cursor.toInt()
        val end = minOf(start + limit, workspace.sessionCount)
        return WorkspaceSessionPage(
            sessions = List(end - start) { index ->
                SavedSession(id = "${workspace.id}-${start + index}")
            },
            nextCursor = if (end < workspace.sessionCount) "$end" else "",
        )
    }
}
