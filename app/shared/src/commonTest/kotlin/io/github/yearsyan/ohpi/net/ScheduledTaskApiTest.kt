package io.github.yearsyan.ohpi.net

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScheduledTaskApiTest {
    @Test
    fun mutationUsesGatewayScheduleFieldNames() {
        val mutation = GatewayScheduledTaskMutation(
            name = "hourly check",
            workspaceId = "workspace-1",
            model = "openai/gpt-5",
            thinking = "high",
            skillPaths = listOf("skills/task", "/srv/shared-skills"),
            noSkills = true,
            prompt = "Run tests",
            schedule = GatewayTaskSchedule(
                kind = ScheduledTaskKinds.Interval,
                everySeconds = 3_600,
                anchorAt = "2026-08-12T09:00:00+08:00",
            ),
        )

        val json = PiJson.parseToJsonElement(
            PiJson.encodeToString(GatewayScheduledTaskMutation.serializer(), mutation),
        ).jsonObject
        assertEquals("workspace-1", json.getValue("workspace_id").jsonPrimitive.content)
        assertEquals(
            listOf("skills/task", "/srv/shared-skills"),
            json.getValue("skill_paths").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("true", json.getValue("no_skills").jsonPrimitive.content)
        val schedule = json.getValue("schedule").jsonObject
        assertEquals("interval", schedule.getValue("kind").jsonPrimitive.content)
        assertEquals("3600", schedule.getValue("every_seconds").jsonPrimitive.content)
        assertEquals(
            "2026-08-12T09:00:00+08:00",
            schedule.getValue("anchor_at").jsonPrimitive.content,
        )
    }

    @Test
    fun legacyMutationOmitsTaskSkillFields() {
        val mutation = GatewayScheduledTaskMutation(
            name = "hourly check",
            workspaceId = "workspace-1",
            skillPaths = listOf("skills/task"),
            noSkills = true,
            prompt = "Run tests",
            schedule = GatewayTaskSchedule(kind = ScheduledTaskKinds.Interval, everySeconds = 3_600),
        )

        val json = PiJson.parseToJsonElement(
            encodeGatewayScheduledTaskMutation(mutation, includeSkillConfiguration = false),
        ).jsonObject
        assertFalse("skill_paths" in json)
        assertFalse("no_skills" in json)
    }

    @Test
    fun decodesTaskAndLastRun() {
        val task = PiJson.decodeFromString(
            GatewayScheduledTask.serializer(),
            """
            {
              "id":"task-1",
              "name":"weekday check",
              "workspace_id":"workspace-1",
              "skill_paths":["skills/task","/srv/shared-skills"],
              "no_skills":true,
              "prompt":"Run tests",
              "schedule":{"kind":"cron","expression":"0 9 * * 1-5","timezone":"Asia/Shanghai"},
              "enabled":true,
              "next_run_at":"2026-08-13T01:00:00Z",
              "last_run":{
                "id":"run-1",
                "scheduled_for":"2026-08-12T01:00:00Z",
                "started_at":"2026-08-12T01:00:01Z",
                "finished_at":"2026-08-12T01:01:00Z",
                "status":"succeeded",
                "session_id":"session-1"
              },
              "created_at":"2026-08-11T00:00:00Z",
              "updated_at":"2026-08-12T01:01:00Z"
            }
            """.trimIndent(),
        )

        assertEquals(ScheduledTaskKinds.Cron, task.schedule.kind)
        assertEquals("0 9 * * 1-5", task.schedule.expression)
        assertEquals(listOf("skills/task", "/srv/shared-skills"), task.skillPaths)
        assertEquals(true, task.noSkills)
        assertEquals("session-1", assertNotNull(task.lastRun).sessionId)
    }

    @Test
    fun decodesAssociatedSessionPageWithWorkspaceContext() {
        val page = decodeScheduledTaskSessionPage(
            """
            {
              "task_id":"task-1",
              "session_count":2,
              "sessions":[{
                "id":"session-1",
                "name":"[定时] weekday check",
                "created_at":1785736800000,
                "last_active":1785738600000,
                "running":false,
                "outputting":false,
                "source":"scheduled_task",
                "scheduled_task_id":"task-1",
                "workspace_id":"workspace-1",
                "workspace_directory":"/srv/project",
                "workspace_name":"Project",
                "workspace_deleted":true
              }],
              "next_cursor":"MQ"
            }
            """.trimIndent(),
        )

        assertEquals("task-1", page.taskId)
        assertEquals(2, page.sessionCount)
        assertEquals("MQ", page.nextCursor)
        val item = page.sessions.single()
        assertEquals("session-1", item.session.id)
        assertEquals("scheduled_task", item.session.source)
        assertEquals("task-1", item.session.scheduledTaskId)
        assertEquals("/srv/project", item.session.workspaceDirectory)
        assertEquals("Project", item.workspaceName)
        assertTrue(item.workspaceDeleted)
    }
}
