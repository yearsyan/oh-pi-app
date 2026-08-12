package io.github.yearsyan.ohpi.net

import io.github.yearsyan.ohpi.data.WorkspaceSummary
import io.github.yearsyan.ohpi.data.WorkspaceResourceConfiguration
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
                "skill_paths": ["skills/team", "/srv/shared-skills"],
                "no_skills": true,
                "extension_paths": ["extensions/team.ts"],
                "no_extensions": true,
                "technology": "vite",
                "technologies": ["vite", "typescript", "javascript"],
                "session_count": 6,
                "sessions": [{
                  "id": "session-1",
                  "name": "Fix UI",
                  "created_at": 100,
                  "last_active": 200,
                  "running": true,
                  "outputting": false,
                  "source": "scheduled_task"
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
        assertEquals(listOf("skills/team", "/srv/shared-skills"), workspace.skillPaths)
        assertTrue(workspace.noSkills)
        assertEquals(listOf("extensions/team.ts"), workspace.extensionPaths)
        assertTrue(workspace.noExtensions)
        assertEquals("workspace-1", workspace.sessions.single().workspaceId)
        assertEquals("/srv/project", workspace.sessions.single().workspaceDirectory)
        assertTrue(workspace.sessions.single().running)
        assertEquals("scheduled_task", workspace.sessions.single().source)
    }

    @Test
    fun encodesWorkspaceResourceConfiguration() {
        val json = PiJson.parseToJsonElement(
            encodeWorkspaceUpdate(
                name = "Project",
                additionalSystemPrompt = "Run tests.",
                resources = WorkspaceResourceConfiguration(
                    skillPaths = listOf("skills/team", "/srv/shared-skills"),
                    noSkills = true,
                    extensionPaths = listOf("extensions/team.ts"),
                    noExtensions = true,
                ),
            ),
        ).jsonObject

        assertEquals(
            listOf("skills/team", "/srv/shared-skills"),
            json.getValue("skill_paths").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(json.getValue("no_skills").jsonPrimitive.boolean)
        assertEquals(
            listOf("extensions/team.ts"),
            json.getValue("extension_paths").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(json.getValue("no_extensions").jsonPrimitive.boolean)
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
