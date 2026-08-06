package io.github.yearsyan.ohpi.net

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

/** One subdirectory of a listed directory on the gateway host. */
@Serializable
data class FsDirEntry(
    val name: String,
    val path: String,
)

/** Response of the gateway `GET /fs/list` endpoint. */
@Serializable
data class FsListResponse(
    val path: String,
    val parent: String = "",
    val dirs: List<FsDirEntry> = emptyList(),
)

/** Failure from /fs/list; [message] carries the gateway's error text. */
class FsListException(message: String) : Exception(message)

@Serializable
private data class FsMkdirRequest(
    val parent: String,
    val name: String,
)

internal val gatewayHttp = HttpClient()

internal fun gatewayHttpBase(gateway: String): String {
    val ws = normalizeGatewayUrl(gateway)
    return when {
        ws.startsWith("wss://") -> "https://" + ws.removePrefix("wss://")
        ws.startsWith("ws://") -> "http://" + ws.removePrefix("ws://")
        else -> ws
    }.trimEnd('/')
}

/**
 * Lists subdirectories of [path] on the gateway host (empty = gateway start
 * directory). Used by the workspace picker to browse the server filesystem.
 */
suspend fun listGatewayDirs(gateway: String, token: String, path: String): FsListResponse {
    val url = buildString {
        append(gatewayHttpBase(gateway)).append("/fs/list?path=").append(urlEncode(path.trim()))
    }
    var response = gatewayHttp.get(url) {
        if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
    }
    var body = response.bodyAsText()

    // ohpi releases before the HTTP session API authenticated /fs/list only
    // through the token query parameter. Prefer the Bearer header, but retry a
    // 401 once with the legacy form so existing gateway installations keep
    // working while they are upgraded.
    if (response.status == HttpStatusCode.Unauthorized && token.isNotBlank()) {
        response = gatewayHttp.get("$url&token=${urlEncode(token)}")
        body = response.bodyAsText()
    }
    if (response.status.value !in 200..299) {
        val message = parseMessage(body)?.str("message") ?: "HTTP ${response.status.value}"
        throw FsListException(message)
    }
    return runCatching { PiJson.decodeFromString(FsListResponse.serializer(), body) }
        .getOrElse { throw FsListException("invalid response from gateway") }
}

/**
 * Creates one directory named [name] below the absolute, existing [parent] on
 * the gateway host via `POST /fs/mkdir`.
 */
suspend fun createGatewayDir(
    gateway: String,
    token: String,
    parent: String,
    name: String,
): FsDirEntry {
    val url = gatewayHttpBase(gateway) + "/fs/mkdir"
    val payload =
        PiJson.encodeToString(
            FsMkdirRequest.serializer(),
            FsMkdirRequest(parent = parent.trim(), name = name.trim()),
        )
    var response = gatewayHttp.post(url) {
        if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody(payload)
    }
    var body = response.bodyAsText()

    // Same legacy token-query fallback as listGatewayDirs for older gateways.
    if (response.status == HttpStatusCode.Unauthorized && token.isNotBlank()) {
        response = gatewayHttp.post("$url?token=${urlEncode(token)}") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        body = response.bodyAsText()
    }
    if (response.status.value !in 200..299) {
        val message = parseMessage(body)?.str("message") ?: "HTTP ${response.status.value}"
        throw FsListException(message)
    }
    return runCatching { PiJson.decodeFromString(FsDirEntry.serializer(), body) }
        .getOrElse { throw FsListException("invalid response from gateway") }
}
