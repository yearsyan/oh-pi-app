package io.github.yearsyan.ohpi.net

import io.github.yearsyan.ohpi.data.SavedSession
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Failure returned by the gateway session-management API. */
class SessionApiException(message: String) : Exception(message)

@Serializable
private data class GatewaySessionList(
    val sessions: List<GatewaySession> = emptyList(),
)

@Serializable
private data class GatewaySession(
    val id: String,
    val name: String = "",
    @SerialName("work_dir") val workDir: String = "",
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("last_active") val lastActive: Long = 0L,
    val running: Boolean = false,
    val outputting: Boolean = false,
) {
    fun summary(): SavedSession = SavedSession(
        id = id,
        name = name,
        createdAt = createdAt,
        lastActive = lastActive,
        workDir = workDir,
        running = running,
        outputting = outputting,
    )
}

@Serializable
private data class SessionNameUpdate(val name: String)

/** One selectable model returned by the sessionless capability probe. */
@Serializable
data class GatewayModelCapability(
    val id: String,
    val name: String = "",
    val provider: String,
    @SerialName("thinking_levels") val thinkingLevels: List<String> = emptyList(),
)

/** The defaults pi would use for a new session in this workspace. */
@Serializable
data class GatewayCapabilitySelection(
    val provider: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("thinking_level") val thinkingLevel: String = "",
)

/** One extension, prompt template, or skill available in a gateway workspace. */
@Serializable
data class GatewayCommandCapability(
    val name: String,
    val description: String = "",
    val source: String = "",
)

/** Sessionless model, thinking, and slash-command options for one gateway workspace. */
@Serializable
data class GatewayCapabilities(
    @SerialName("work_dir") val workDir: String,
    @SerialName("default") val defaultSelection: GatewayCapabilitySelection? = null,
    val models: List<GatewayModelCapability> = emptyList(),
    val commands: List<GatewayCommandCapability> = emptyList(),
)

/** Gateway-observed generation performance aggregated across one session. */
@Serializable
data class GatewaySessionMetrics(
    @SerialName("sample_count") val sampleCount: Long = 0L,
    @SerialName("average_tps") val averageTps: Double? = null,
    @SerialName("average_ttft_ms") val averageTtftMs: Double? = null,
)

/** Returns the server-owned sessions for one ohpi gateway. */
suspend fun listGatewaySessions(gateway: String, token: String): List<SavedSession> {
    val response = gatewayHttp.get("${gatewayHttpBase(gateway)}/api/sessions") {
        authenticate(token)
    }
    val body = response.requireSuccess()
    return runCatching {
        PiJson.decodeFromString(GatewaySessionList.serializer(), body).sessions.map { it.summary() }
    }.getOrElse { throw SessionApiException("invalid session list from gateway") }
}

/** Loads model options without creating or persisting a gateway session. */
suspend fun getGatewayCapabilities(gateway: String, token: String, workDir: String): GatewayCapabilities {
    val response = gatewayHttp.get(
        "${gatewayHttpBase(gateway)}/api/capabilities?work_dir=${urlEncode(workDir.trim())}",
    ) {
        authenticate(token)
    }
    val body = response.requireSuccess()
    return runCatching { PiJson.decodeFromString(GatewayCapabilities.serializer(), body) }
        .getOrElse { throw SessionApiException("invalid capabilities response from gateway") }
}

/** Loads persisted, gateway-observed TPS and TTFT averages for one session. */
suspend fun getGatewaySessionMetrics(
    gateway: String,
    token: String,
    sessionId: String,
): GatewaySessionMetrics {
    val response = gatewayHttp.get(
        "${gatewayHttpBase(gateway)}/api/sessions/${urlEncode(sessionId)}/metrics",
    ) {
        authenticate(token)
    }
    val body = response.requireSuccess()
    return runCatching { PiJson.decodeFromString(GatewaySessionMetrics.serializer(), body) }
        .getOrElse { throw SessionApiException("invalid session metrics response from gateway") }
}

/** Changes a server-owned session name and returns the updated summary. */
suspend fun renameGatewaySession(
    gateway: String,
    token: String,
    sessionId: String,
    name: String,
): SavedSession {
    val response = gatewayHttp.patch(
        "${gatewayHttpBase(gateway)}/api/sessions/${urlEncode(sessionId)}",
    ) {
        authenticate(token)
        contentType(ContentType.Application.Json)
        setBody(PiJson.encodeToString(SessionNameUpdate.serializer(), SessionNameUpdate(name)))
    }
    val body = response.requireSuccess()
    return runCatching {
        PiJson.decodeFromString(GatewaySession.serializer(), body).summary()
    }.getOrElse { throw SessionApiException("invalid session response from gateway") }
}

/** Stops and permanently deletes a server-owned session. */
suspend fun deleteGatewaySession(gateway: String, token: String, sessionId: String) {
    gatewayHttp.delete("${gatewayHttpBase(gateway)}/api/sessions/${urlEncode(sessionId)}") {
        authenticate(token)
    }.requireSuccess()
}

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
