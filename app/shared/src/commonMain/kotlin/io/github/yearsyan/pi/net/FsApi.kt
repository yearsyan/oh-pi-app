package io.github.yearsyan.pi.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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

internal val gatewayHttp = HttpClient(CIO)

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

    // pi2ws releases before the HTTP session API authenticated /fs/list only
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
