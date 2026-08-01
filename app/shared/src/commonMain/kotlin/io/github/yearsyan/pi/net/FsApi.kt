package io.github.yearsyan.pi.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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

private val fsHttp = HttpClient(CIO)

private fun httpBase(gateway: String): String {
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
        append(httpBase(gateway)).append("/fs/list?path=").append(urlEncode(path.trim()))
        if (token.isNotBlank()) append("&token=").append(urlEncode(token))
    }
    val response = fsHttp.get(url)
    val body = response.bodyAsText()
    if (response.status.value !in 200..299) {
        val message = parseMessage(body)?.str("message") ?: "HTTP ${response.status.value}"
        throw FsListException(message)
    }
    return runCatching { PiJson.decodeFromString(FsListResponse.serializer(), body) }
        .getOrElse { throw FsListException("invalid response from gateway") }
}
