package io.github.yearsyan.ohpi.net

import io.github.yearsyan.ohpi.data.SavedSession
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ScheduledTaskApiException(message: String) : Exception(message)

object ScheduledTaskKinds {
    const val Cron = "cron"
    const val Interval = "interval"
    const val Once = "once"
}

@Serializable
data class GatewayTaskSchedule(
    val kind: String,
    val expression: String = "",
    val timezone: String = "",
    @SerialName("every_seconds") val everySeconds: Long = 0L,
    @SerialName("anchor_at") val anchorAt: String? = null,
    val at: String? = null,
)

@Serializable
data class GatewayScheduledRun(
    val id: String,
    @SerialName("scheduled_for") val scheduledFor: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String? = null,
    val status: String,
    @SerialName("session_id") val sessionId: String = "",
    val error: String = "",
    val manual: Boolean = false,
)

@Serializable
data class GatewayScheduledTask(
    val id: String,
    val name: String,
    @SerialName("workspace_id") val workspaceId: String,
    val model: String = "",
    val thinking: String = "",
    @SerialName("skill_paths") val skillPaths: List<String> = emptyList(),
    @SerialName("no_skills") val noSkills: Boolean = false,
    val prompt: String,
    val schedule: GatewayTaskSchedule,
    val enabled: Boolean,
    @SerialName("next_run_at") val nextRunAt: String? = null,
    @SerialName("current_run") val currentRun: GatewayScheduledRun? = null,
    @SerialName("last_run") val lastRun: GatewayScheduledRun? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class GatewayScheduledTaskMutation(
    val name: String,
    @SerialName("workspace_id") val workspaceId: String,
    val model: String = "",
    val thinking: String = "",
    @SerialName("skill_paths") val skillPaths: List<String> = emptyList(),
    @SerialName("no_skills") val noSkills: Boolean = false,
    val prompt: String,
    val schedule: GatewayTaskSchedule,
    val enabled: Boolean = true,
)

@Serializable
private data class LegacyGatewayScheduledTaskMutation(
    val name: String,
    @SerialName("workspace_id") val workspaceId: String,
    val model: String,
    val thinking: String,
    val prompt: String,
    val schedule: GatewayTaskSchedule,
    val enabled: Boolean,
)

@Serializable
private data class GatewayScheduledTaskList(
    val tasks: List<GatewayScheduledTask> = emptyList(),
)

@Serializable
private data class GatewayScheduledTaskSessionWire(
    val id: String,
    val name: String = "",
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("last_active") val lastActive: Long = 0L,
    val running: Boolean = false,
    val outputting: Boolean = false,
    val source: String = "",
    @SerialName("scheduled_task_id") val scheduledTaskId: String = "",
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("workspace_directory") val workspaceDirectory: String = "",
    @SerialName("workspace_name") val workspaceName: String = "",
    @SerialName("workspace_deleted") val workspaceDeleted: Boolean = false,
) {
    fun session(): GatewayScheduledTaskSession =
        GatewayScheduledTaskSession(
            session = SavedSession(
                id = id,
                name = name,
                createdAt = createdAt,
                lastActive = lastActive,
                workspaceId = workspaceId,
                workspaceDirectory = workspaceDirectory,
                running = running,
                outputting = outputting,
                source = source,
                scheduledTaskId = scheduledTaskId,
            ),
            workspaceName = workspaceName,
            workspaceDeleted = workspaceDeleted,
        )
}

@Serializable
private data class GatewayScheduledTaskSessionPageWire(
    @SerialName("task_id") val taskId: String,
    @SerialName("session_count") val sessionCount: Int = 0,
    val sessions: List<GatewayScheduledTaskSessionWire> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String = "",
)

data class GatewayScheduledTaskSession(
    val session: SavedSession,
    val workspaceName: String,
    val workspaceDeleted: Boolean,
)

data class GatewayScheduledTaskSessionPage(
    val taskId: String,
    val sessionCount: Int,
    val sessions: List<GatewayScheduledTaskSession>,
    val nextCursor: String,
)

suspend fun listGatewayScheduledTasks(gateway: String, token: String): List<GatewayScheduledTask> {
    val body = gatewayHttp.get(scheduledTasksEndpoint(gateway)) { scheduledTaskAuth(token) }.scheduledTaskBody()
    return runCatching {
        PiJson.decodeFromString(GatewayScheduledTaskList.serializer(), body).tasks
    }.getOrElse { throw ScheduledTaskApiException("invalid scheduled task list from gateway") }
}

suspend fun getGatewayScheduledTask(
    gateway: String,
    token: String,
    taskId: String,
): GatewayScheduledTask {
    val body = gatewayHttp.get(scheduledTaskEndpoint(gateway, taskId)) { scheduledTaskAuth(token) }.scheduledTaskBody()
    return decodeScheduledTask(body)
}

suspend fun createGatewayScheduledTask(
    gateway: String,
    token: String,
    mutation: GatewayScheduledTaskMutation,
    includeSkillConfiguration: Boolean = true,
): GatewayScheduledTask {
    val response = gatewayHttp.post(scheduledTasksEndpoint(gateway)) {
        scheduledTaskAuth(token)
        contentType(ContentType.Application.Json)
        setBody(encodeGatewayScheduledTaskMutation(mutation, includeSkillConfiguration))
    }
    return decodeScheduledTask(response.scheduledTaskBody())
}

suspend fun updateGatewayScheduledTask(
    gateway: String,
    token: String,
    taskId: String,
    mutation: GatewayScheduledTaskMutation,
    includeSkillConfiguration: Boolean = true,
): GatewayScheduledTask {
    val response = gatewayHttp.patch(scheduledTaskEndpoint(gateway, taskId)) {
        scheduledTaskAuth(token)
        contentType(ContentType.Application.Json)
        setBody(encodeGatewayScheduledTaskMutation(mutation, includeSkillConfiguration))
    }
    return decodeScheduledTask(response.scheduledTaskBody())
}

internal fun encodeGatewayScheduledTaskMutation(
    mutation: GatewayScheduledTaskMutation,
    includeSkillConfiguration: Boolean,
): String =
    if (includeSkillConfiguration) {
        PiJson.encodeToString(GatewayScheduledTaskMutation.serializer(), mutation)
    } else {
        PiJson.encodeToString(
            LegacyGatewayScheduledTaskMutation.serializer(),
            LegacyGatewayScheduledTaskMutation(
                name = mutation.name,
                workspaceId = mutation.workspaceId,
                model = mutation.model,
                thinking = mutation.thinking,
                prompt = mutation.prompt,
                schedule = mutation.schedule,
                enabled = mutation.enabled,
            ),
        )
    }

suspend fun deleteGatewayScheduledTask(gateway: String, token: String, taskId: String) {
    gatewayHttp.delete(scheduledTaskEndpoint(gateway, taskId)) {
        scheduledTaskAuth(token)
    }.scheduledTaskBody()
}

suspend fun runGatewayScheduledTaskNow(
    gateway: String,
    token: String,
    taskId: String,
): GatewayScheduledTask {
    val response = gatewayHttp.post(scheduledTaskEndpoint(gateway, taskId) + "/run") {
        scheduledTaskAuth(token)
    }
    return decodeScheduledTask(response.scheduledTaskBody())
}

suspend fun listGatewayScheduledTaskSessions(
    gateway: String,
    token: String,
    taskId: String,
    cursor: String = "",
    limit: Int = 50,
): GatewayScheduledTaskSessionPage {
    val query = buildString {
        append("?limit=").append(limit.coerceIn(1, MAX_GATEWAY_WORKSPACE_SESSION_PAGE_SIZE))
        if (cursor.isNotBlank()) append("&cursor=").append(urlEncode(cursor))
    }
    val body = gatewayHttp.get(scheduledTaskEndpoint(gateway, taskId) + "/sessions" + query) {
        scheduledTaskAuth(token)
    }.scheduledTaskBody()
    return decodeScheduledTaskSessionPage(body)
}

private fun scheduledTasksEndpoint(gateway: String): String =
    gatewayHttpBase(gateway) + "/api/tasks"

private fun scheduledTaskEndpoint(gateway: String, taskId: String): String =
    scheduledTasksEndpoint(gateway) + "/" + urlEncode(taskId)

private fun io.ktor.client.request.HttpRequestBuilder.scheduledTaskAuth(token: String) {
    if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
}

private suspend fun io.ktor.client.statement.HttpResponse.scheduledTaskBody(): String {
    val body = bodyAsText()
    if (status.value !in 200..299) {
        throw ScheduledTaskApiException(parseMessage(body)?.str("message") ?: "HTTP ${status.value}")
    }
    return body
}

private fun decodeScheduledTask(body: String): GatewayScheduledTask =
    runCatching { PiJson.decodeFromString(GatewayScheduledTask.serializer(), body) }
        .getOrElse { throw ScheduledTaskApiException("invalid scheduled task response from gateway") }

internal fun decodeScheduledTaskSessionPage(body: String): GatewayScheduledTaskSessionPage =
    runCatching {
        val page = PiJson.decodeFromString(GatewayScheduledTaskSessionPageWire.serializer(), body)
        GatewayScheduledTaskSessionPage(
            taskId = page.taskId,
            sessionCount = page.sessionCount,
            sessions = page.sessions.map(GatewayScheduledTaskSessionWire::session),
            nextCursor = page.nextCursor,
        )
    }.getOrElse { throw ScheduledTaskApiException("invalid scheduled task session list from gateway") }
