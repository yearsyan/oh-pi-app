package io.github.yearsyan.ohpi.ui

import io.github.yearsyan.ohpi.data.WorkspaceSummary
import io.github.yearsyan.ohpi.net.DEFAULT_GATEWAY_WORKSPACE_SESSION_PREVIEW
import io.github.yearsyan.ohpi.net.MAX_GATEWAY_WORKSPACE_SESSION_PAGE_SIZE
import io.github.yearsyan.ohpi.net.WorkspaceSessionPage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Visible pagination depth captured before a non-destructive session refresh. */
internal data class ExpandedWorkspaceSessionWindow(
    val visibleCount: Int,
    val fullyLoaded: Boolean,
)

/** Default previews are not expanded and therefore do not need restoring. */
internal fun expandedWorkspaceSessionWindows(
    workspaces: List<WorkspaceSummary>,
): Map<String, ExpandedWorkspaceSessionWindow> =
    workspaces.mapNotNull { workspace ->
        val visibleCount = workspace.sessions.size
        if (visibleCount <= DEFAULT_GATEWAY_WORKSPACE_SESSION_PREVIEW) {
            null
        } else {
            workspace.id to ExpandedWorkspaceSessionWindow(
                visibleCount = visibleCount,
                fullyLoaded = workspace.nextCursor.isBlank(),
            )
        }
    }.toMap()

internal fun refreshedWorkspaceSessionTarget(
    workspace: WorkspaceSummary,
    window: ExpandedWorkspaceSessionWindow,
): Int =
    if (window.fullyLoaded) {
        workspace.sessionCount
    } else {
        minOf(window.visibleCount, workspace.sessionCount)
    }

internal suspend fun restoreExpandedWorkspaceSessions(
    workspaces: List<WorkspaceSummary>,
    windows: Map<String, ExpandedWorkspaceSessionWindow>,
    loadPage: suspend (
        workspace: WorkspaceSummary,
        cursor: String,
        limit: Int,
    ) -> WorkspaceSessionPage,
): List<WorkspaceSummary> {
    if (windows.isEmpty()) return workspaces
    return coroutineScope {
        workspaces.map { workspace ->
            async {
                val window = windows[workspace.id] ?: return@async workspace
                val targetCount = refreshedWorkspaceSessionTarget(workspace, window)
                if (targetCount <= workspace.sessions.size || workspace.nextCursor.isBlank()) {
                    return@async workspace
                }

                var sessions = workspace.sessions
                var nextCursor = workspace.nextCursor
                while (sessions.size < targetCount && nextCursor.isNotBlank()) {
                    val requestedCursor = nextCursor
                    val page = loadPage(
                        workspace,
                        nextCursor,
                        minOf(
                            targetCount - sessions.size,
                            MAX_GATEWAY_WORKSPACE_SESSION_PAGE_SIZE,
                        ),
                    )
                    sessions = (sessions + page.sessions).distinctBy { it.id }
                    nextCursor = page.nextCursor
                    if (page.sessions.isEmpty() || nextCursor == requestedCursor) break
                }
                workspace.copy(sessions = sessions, nextCursor = nextCursor)
            }
        }.awaitAll()
    }
}
