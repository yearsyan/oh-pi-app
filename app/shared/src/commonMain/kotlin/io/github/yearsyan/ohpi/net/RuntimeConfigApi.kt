package io.github.yearsyan.ohpi.net

import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

const val DEFAULT_SCHEDULED_SESSION_RETENTION_SECONDS = 7L * 24 * 60 * 60

/** Gateway settings that take effect after its process restarts. */
@Serializable
data class GatewayRuntimeConfig(
    @SerialName("title_model") val titleModel: String,
    @SerialName("pi_env_file") val piEnvironmentFile: String = "",
    @SerialName("pi_env_shell") val piEnvironmentShell: String = "",
    @SerialName("scheduled_session_retention_seconds")
    val scheduledSessionRetentionSeconds: Long = DEFAULT_SCHEDULED_SESSION_RETENTION_SECONDS,
    @SerialName("restart_required") val restartRequired: Boolean = false,
    @SerialName("restart_supported") val restartSupported: Boolean = false,
)

/** Failure returned by the gateway runtime configuration endpoints. */
class GatewayRuntimeConfigException(message: String) : Exception(message)

suspend fun getGatewayRuntimeConfig(gateway: String, token: String): GatewayRuntimeConfig {
    val response = gatewayHttp.get("${gatewayHttpBase(gateway)}/api/runtime-config") {
        gatewayAuthorization(token)
    }
    return decodeRuntimeConfigResponse(response.status.value, response.bodyAsText())
}

suspend fun updateGatewayRuntimeConfig(
    gateway: String,
    token: String,
    titleModel: String,
    piEnvironmentFile: String,
    piEnvironmentShell: String,
    scheduledSessionRetentionSeconds: Long? = null,
): GatewayRuntimeConfig {
    val payload = buildJsonObject {
        put("title_model", JsonPrimitive(titleModel))
        put("pi_env_file", JsonPrimitive(piEnvironmentFile))
        put("pi_env_shell", JsonPrimitive(piEnvironmentShell))
        scheduledSessionRetentionSeconds?.let {
            put("scheduled_session_retention_seconds", JsonPrimitive(it))
        }
    }.toString()
    val response = gatewayHttp.patch("${gatewayHttpBase(gateway)}/api/runtime-config") {
        gatewayAuthorization(token)
        contentType(ContentType.Application.Json)
        setBody(payload)
    }
    return decodeRuntimeConfigResponse(response.status.value, response.bodyAsText())
}

suspend fun requestGatewayRuntimeRestart(gateway: String, token: String) {
    val response = gatewayHttp.post("${gatewayHttpBase(gateway)}/api/runtime-restart") {
        gatewayAuthorization(token)
    }
    val body = response.bodyAsText()
    if (response.status.value !in 200..299) {
        throw GatewayRuntimeConfigException(gatewayRuntimeErrorMessage(response.status.value, body))
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.gatewayAuthorization(token: String) {
    if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
}

private fun decodeRuntimeConfigResponse(status: Int, body: String): GatewayRuntimeConfig {
    if (status !in 200..299) {
        throw GatewayRuntimeConfigException(gatewayRuntimeErrorMessage(status, body))
    }
    return runCatching { PiJson.decodeFromString(GatewayRuntimeConfig.serializer(), body) }
        .getOrElse { throw GatewayRuntimeConfigException("invalid response from gateway") }
}

private fun gatewayRuntimeErrorMessage(status: Int, body: String): String =
    parseMessage(body)?.str("message") ?: "HTTP $status"
