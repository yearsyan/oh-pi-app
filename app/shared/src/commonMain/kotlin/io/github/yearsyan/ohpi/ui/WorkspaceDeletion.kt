package io.github.yearsyan.ohpi.ui

import io.github.yearsyan.ohpi.data.SavedSession
import io.github.yearsyan.ohpi.data.WorkspaceSummary
import io.github.yearsyan.ohpi.net.MAX_GATEWAY_WORKSPACE_SESSION_PAGE_SIZE
import io.github.yearsyan.ohpi.net.WorkspaceSessionPage

/** Loads a stable snapshot of every remote session before destructive deletion begins. */
internal suspend fun loadAllWorkspaceSessions(
    workspace: WorkspaceSummary,
    loadPage: suspend (
        workspace: WorkspaceSummary,
        cursor: String,
        limit: Int,
    ) -> WorkspaceSessionPage,
): List<SavedSession> {
    val sessions = mutableListOf<SavedSession>()
    val visitedCursors = mutableSetOf<String>()
    var cursor = ""
    do {
        if (!visitedCursors.add(cursor)) {
            error("workspace session pagination repeated cursor $cursor")
        }
        val page = loadPage(workspace, cursor, MAX_GATEWAY_WORKSPACE_SESSION_PAGE_SIZE)
        sessions += page.sessions
        cursor = page.nextCursor
    } while (cursor.isNotBlank())
    return sessions.distinctBy(SavedSession::id)
}
