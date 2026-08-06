package io.github.yearsyan.ohpi.net

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProviderApiException(message: String) : Exception(message)

@Serializable
data class GatewayProviderAuthMethod(
    val type: String,
    val name: String,
    val label: String = "",
)

@Serializable
data class GatewayProviderModel(
    val id: String,
    val name: String = "",
    val reasoning: Boolean = false,
    val input: List<String> = emptyList(),
)

@Serializable
data class GatewayProvider(
    val id: String,
    val name: String,
    val configured: Boolean = false,
    @SerialName("auth_source") val authSource: String = "",
    @SerialName("auth_label") val authLabel: String = "",
    @SerialName("stored_auth_type") val storedAuthType: String = "",
    @SerialName("auth_methods") val authMethods: List<GatewayProviderAuthMethod> = emptyList(),
    val models: List<GatewayProviderModel> = emptyList(),
)

@Serializable
private data class GatewayProvidersResponse(
    val providers: List<GatewayProvider> = emptyList(),
)

@Serializable
data class ProviderAuthLink(
    val url: String,
    val label: String = "",
)

@Serializable
data class ProviderAuthEvent(
    val type: String,
    val event: String,
    val id: String = "",
    val kind: String = "",
    val message: String = "",
    val placeholder: String = "",
    val options: List<String> = emptyList(),
    val descriptions: List<String> = emptyList(),
    val action: String = "",
    @SerialName("provider_id") val providerId: String = "",
    @SerialName("provider_name") val providerName: String = "",
    @SerialName("auth_type") val authType: String = "",
    val url: String = "",
    val instructions: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("interval_seconds") val intervalSeconds: Int = 0,
    @SerialName("expires_in_seconds") val expiresInSeconds: Int = 0,
    val links: List<ProviderAuthLink> = emptyList(),
)

suspend fun listGatewayProviders(gateway: String, token: String): List<GatewayProvider> {
    val response = gatewayHttp.get("${gatewayHttpBase(gateway)}/api/providers") {
        if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
    }
    val body = response.bodyAsText()
    if (response.status.value !in 200..299) {
        throw ProviderApiException(parseMessage(body)?.str("message") ?: "HTTP ${response.status.value}")
    }
    return runCatching {
        PiJson.decodeFromString(GatewayProvidersResponse.serializer(), body).providers
    }.getOrElse { throw ProviderApiException("invalid provider list from gateway") }
}

suspend fun logoutGatewayProvider(gateway: String, token: String, providerId: String) {
    val response = gatewayHttp.delete(
        "${gatewayHttpBase(gateway)}/api/providers/${urlEncode(providerId)}/credential",
    ) {
        if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
    }
    val body = response.bodyAsText()
    if (response.status.value !in 200..299) {
        throw ProviderApiException(parseMessage(body)?.str("message") ?: "HTTP ${response.status.value}")
    }
}

fun buildProviderAuthWsUrl(
    base: String,
    token: String,
    providerId: String,
    authType: String,
): String = buildString {
    append(normalizeGatewayUrl(base))
    append("/api/provider-auth?provider_id=").append(urlEncode(providerId))
    append("&auth_type=").append(urlEncode(authType))
    if (token.isNotBlank()) append("&token=").append(urlEncode(token))
}

fun parseProviderAuthEvent(raw: String): ProviderAuthEvent? =
    runCatching { PiJson.decodeFromString(ProviderAuthEvent.serializer(), raw) }
        .getOrNull()
        ?.takeIf { it.type == "ohpi_provider" && it.event.isNotBlank() }

fun providerAuthInputResponse(id: String, value: String): String =
    buildJsonObject {
        put("type", "extension_ui_response")
        put("id", id)
        put("value", value)
    }.toString()

fun providerAuthCancelResponse(id: String): String =
    buildJsonObject {
        put("type", "extension_ui_response")
        put("id", id)
        put("cancelled", true)
    }.toString()
