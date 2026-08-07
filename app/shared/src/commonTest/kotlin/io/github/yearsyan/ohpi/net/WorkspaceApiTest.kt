package io.github.yearsyan.ohpi.net

import io.github.yearsyan.ohpi.data.WorkspaceSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspaceApiTest {
    @Test
    fun decodesWorkspacePreviewAndSessionOwnership() {
        val workspaces = decodeWorkspaceList(
            """
            {
              "workspaces": [{
                "id": "workspace-1",
                "directory": "/srv/project",
                "name": "Project",
                "additional_system_prompt": "Run tests.",
                "technology": "vite",
                "technologies": ["vite", "typescript", "javascript"],
                "session_count": 6,
                "sessions": [{
                  "id": "session-1",
                  "name": "Fix UI",
                  "created_at": 100,
                  "last_active": 200,
                  "running": true,
                  "outputting": false
                }],
                "next_cursor": "NQ",
                "created_at": 10,
                "updated_at": 20
              }]
            }
            """.trimIndent(),
        )

        val workspace = workspaces.single()
        assertEquals("workspace-1", workspace.id)
        assertEquals("vite", workspace.technology)
        assertEquals(listOf("vite", "typescript", "javascript"), workspace.technologies)
        assertEquals("workspace-1", workspace.sessions.single().workspaceId)
        assertEquals("/srv/project", workspace.sessions.single().workspaceDirectory)
        assertTrue(workspace.sessions.single().running)
    }

    @Test
    fun rejectsPageForAnotherWorkspace() {
        val workspace = WorkspaceSummary(id = "workspace-1", directory = "/srv/project")
        assertFailsWith<SessionApiException> {
            decodeWorkspaceSessionPage(
                workspace,
                """{"workspace_id":"workspace-2","sessions":[]}""",
            )
        }
    }
}
