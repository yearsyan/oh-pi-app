package io.github.yearsyan.ohpi.net

import io.github.yearsyan.ohpi.data.SavedSession
import io.github.yearsyan.ohpi.data.WorkspaceSummary
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Failure returned by the gateway workspace/session API. */
class SessionApiException(message: String) : Exception(message)

@Serializable
private data class GatewayWorkspaceList(
    val workspaces: List<GatewayWorkspace> = emptyList(),
)

@Serializable
private data class GatewayWorkspaceSessionPage(
    @SerialName("workspace_id") val workspaceId: String,
    val sessions: List<GatewaySession> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String = "",
)

@Serializable
private data class GatewayWorkspace(
    val id: String,
    val directory: String,
    val name: String = "",
    @SerialName("additional_system_prompt") val additionalSystemPrompt: String = "",
    val technology: String = "generic",
    val technologies: List<String> = emptyList(),
    @SerialName("session_count") val sessionCount: Int = 0,
    val sessions: List<GatewaySession> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String = "",
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L,
) {
    fun summary(): WorkspaceSummary =
        WorkspaceSummary(
            id = id,
            directory = directory,
            name = name,
            additionalSystemPrompt = additionalSystemPrompt,
            technology = technology,
            technologies = technologies,
            sessionCount = sessionCount,
            sessions = sessions.map { it.summary(id, directory) },
            nextCursor = nextCursor,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}

@Serializable
private data class GatewaySession(
    val id: String,
    val name: String = "",
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("last_active") val lastActive: Long = 0L,
    val running: Boolean = false,
    val outputting: Boolean = false,
) {
    fun summary(workspaceId: String, directory: String): SavedSession =
        SavedSession(
            id = id,
            name = name,
            createdAt = createdAt,
            lastActive = lastActive,
            workspaceId = workspaceId,
            workspaceDirectory = directory,
            running = running,
            outputting = outputting,
        )
}

@Serializable
private data class WorkspaceCreate(val directory: String)

@Serializable
private data class WorkspaceUpdate(
    val name: String,
    @SerialName("additional_system_prompt") val additionalSystemPrompt: String,
)

@Serializable
private data class SessionNameUpdate(val name: String)

data class WorkspaceSessionPage(
    val sessions: List<SavedSession>,
    val nextCursor: String,
)

/** One selectable model returned by the sessionless capability probe. */
@Serializable
data class GatewayModelCapability(
    val id: String,
    val name: String = "",
    val provider: String,
    @SerialName("thinking_levels") val thinkingLevels: List<String> = emptyList(),
)

@Serializable
data class GatewayCapabilitySelection(
    val provider: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("thinking_level") val thinkingLevel: String = "",
)

@Serializable
data class GatewayCommandCapability(
    val name: String,
    val description: String = "",
    val source: String = "",
)

/** Sessionless model, thinking, and slash-command options for one workspace. */
@Serializable
data class GatewayCapabilities(
    @SerialName("workspace_id") val workspaceId: String,
    val directory: String,
    @SerialName("default") val defaultSelection: GatewayCapabilitySelection? = null,
    val models: List<GatewayModelCapability> = emptyList(),
    val commands: List<GatewayCommandCapability> = emptyList(),
)

@Serializable
data class GatewaySessionMetrics(
    @SerialName("sample_count") val sampleCount: Long = 0L,
    @SerialName("average_tps") val averageTps: Double? = null,
    @SerialName("average_ttft_ms") val averageTtftMs: Double? = null,
)

suspend fun listGatewayWorkspaces(
    gateway: String,
    token: String,
    sessionLimit: Int = 5,
): List<WorkspaceSummary> {
    val response = gatewayHttp.get(
        "${gatewayHttpBase(gateway)}/api/workspaces?session_limit=${sessionLimit.coerceIn(1, 100)}",
    ) { authenticate(token) }
    val body = response.requireSuccess()
    return decodeWorkspaceList(body)
}

suspend fun createGatewayWorkspace(
    gateway: String,
    token: String,
    directory: String,
): WorkspaceSummary {
    val response = gatewayHttp.post("${gatewayHttpBase(gateway)}/api/workspaces") {
        authenticate(token)
        contentType(ContentType.Application.Json)
        setBody(PiJson.encodeToString(WorkspaceCreate.serializer(), WorkspaceCreate(directory)))
    }
    val body = response.requireSuccess()
    return decodeWorkspace(body)
}

suspend fun updateGatewayWorkspace(
    gateway: String,
    token: String,
    workspaceId: String,
    name: String,
    additionalSystemPrompt: String,
): WorkspaceSummary {
    val response = gatewayHttp.patch(
        "${gatewayHttpBase(gateway)}/api/workspaces/${urlEncode(workspaceId)}",
    ) {
        authenticate(token)
        contentType(ContentType.Application.Json)
        setBody(
            PiJson.encodeToString(
                WorkspaceUpdate.serializer(),
                WorkspaceUpdate(name, additionalSystemPrompt),
            ),
        )
    }
    return decodeWorkspace(response.requireSuccess())
}

suspend fun listGatewayWorkspaceSessions(
    gateway: String,
    token: String,
    workspace: WorkspaceSummary,
    cursor: String,
    limit: Int = 20,
): WorkspaceSessionPage {
    val suffix = buildString {
        append("?limit=").append(limit.coerceIn(1, 100))
        if (cursor.isNotBlank()) append("&cursor=").append(urlEncode(cursor))
    }
    val response = gatewayHttp.get(
        "${gatewayHttpBase(gateway)}/api/workspaces/${urlEncode(workspace.id)}/sessions$suffix",
    ) { authenticate(token) }
    val body = response.requireSuccess()
    return decodeWorkspaceSessionPage(workspace, body)
}

suspend fun getGatewayCapabilities(
    gateway: String,
    token: String,
    workspaceId: String,
): GatewayCapabilities {
    val response = gatewayHttp.get(
        "${gatewayHttpBase(gateway)}/api/workspaces/${urlEncode(workspaceId)}/capabilities",
    ) { authenticate(token) }
    val body = response.requireSuccess()
    return runCatching {
        PiJson.decodeFromString(GatewayCapabilities.serializer(), body)
    }.getOrElse { throw SessionApiException("invalid capabilities response from gateway") }
}

suspend fun getGatewaySessionMetrics(
    gateway: String,
    token: String,
    workspaceId: String,
    sessionId: String,
): GatewaySessionMetrics {
    val response = gatewayHttp.get(sessionEndpoint(gateway, workspaceId, sessionId) + "/metrics") {
        authenticate(token)
    }
    val body = response.requireSuccess()
    return runCatching {
        PiJson.decodeFromString(GatewaySessionMetrics.serializer(), body)
    }.getOrElse { throw SessionApiException("invalid session metrics response from gateway") }
}

suspend fun renameGatewaySession(
    gateway: String,
    token: String,
    workspaceId: String,
    workspaceDirectory: String,
    sessionId: String,
    name: String,
): SavedSession {
    val response = gatewayHttp.patch(sessionEndpoint(gateway, workspaceId, sessionId)) {
        authenticate(token)
        contentType(ContentType.Application.Json)
        setBody(PiJson.encodeToString(SessionNameUpdate.serializer(), SessionNameUpdate(name)))
    }
    val body = response.requireSuccess()
    return runCatching {
        PiJson.decodeFromString(GatewaySession.serializer(), body).summary(workspaceId, workspaceDirectory)
    }.getOrElse { throw SessionApiException("invalid session response from gateway") }
}

suspend fun deleteGatewaySession(gateway: String, token: String, workspaceId: String, sessionId: String) {
    gatewayHttp.delete(sessionEndpoint(gateway, workspaceId, sessionId)) {
        authenticate(token)
    }.requireSuccess()
}

suspend fun stopGatewaySessionProcess(gateway: String, token: String, workspaceId: String, sessionId: String) {
    gatewayHttp.delete(sessionEndpoint(gateway, workspaceId, sessionId) + "/process") {
        authenticate(token)
    }.requireSuccess()
}

private fun decodeWorkspace(body: String): WorkspaceSummary =
    runCatching { PiJson.decodeFromString(GatewayWorkspace.serializer(), body).summary() }
        .getOrElse { throw SessionApiException("invalid workspace response from gateway") }

internal fun decodeWorkspaceList(body: String): List<WorkspaceSummary> =
    runCatching {
        PiJson.decodeFromString(GatewayWorkspaceList.serializer(), body).workspaces.map { it.summary() }
    }.getOrElse { throw SessionApiException("invalid workspace list from gateway") }

internal fun decodeWorkspaceSessionPage(
    workspace: WorkspaceSummary,
    body: String,
): WorkspaceSessionPage =
    runCatching {
        val page = PiJson.decodeFromString(GatewayWorkspaceSessionPage.serializer(), body)
        if (page.workspaceId != workspace.id) {
            throw SessionApiException("workspace page belongs to another workspace")
        }
        WorkspaceSessionPage(
            sessions = page.sessions.map { it.summary(workspace.id, workspace.directory) },
            nextCursor = page.nextCursor,
        )
    }.getOrElse { failure ->
        if (failure is SessionApiException) throw failure
        throw SessionApiException("invalid workspace session page from gateway")
    }

private fun sessionEndpoint(gateway: String, workspaceId: String, sessionId: String): String =
    "${gatewayHttpBase(gateway)}/api/workspaces/${urlEncode(workspaceId)}/sessions/${urlEncode(sessionId)}"

private fun io.ktor.client.request.HttpRequestBuilder.authenticate(token: String) {
    if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
}

private suspend fun HttpResponse.requireSuccess(): String {
    val body = bodyAsText()
    if (status.value !in 200..299) {
        val message = parseMessage(body)?.str("message") ?: "HTTP ${status.value}"
        throw SessionApiException(message)
    }
    return body
}
