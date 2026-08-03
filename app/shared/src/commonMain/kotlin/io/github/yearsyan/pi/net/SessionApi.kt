package io.github.yearsyan.pi.net

import io.github.yearsyan.pi.data.SavedSession
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
) {
    fun summary(): SavedSession = SavedSession(
        id = id,
        name = name,
        createdAt = createdAt,
        lastActive = lastActive,
        workDir = workDir,
    )
}

@Serializable
private data class SessionNameUpdate(val name: String)

/** Returns the server-owned sessions for one pi2ws gateway. */
suspend fun listGatewaySessions(gateway: String, token: String): List<SavedSession> {
    val response = gatewayHttp.get("${gatewayHttpBase(gateway)}/api/sessions") {
        authenticate(token)
    }
    val body = response.requireSuccess()
    return runCatching {
        PiJson.decodeFromString(GatewaySessionList.serializer(), body).sessions.map { it.summary() }
    }.getOrElse { throw SessionApiException("invalid session list from gateway") }
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
