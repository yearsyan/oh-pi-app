package io.github.yearsyan.ohpi.net

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Failure returned by the gateway file browsing API. */
class FileApiException(message: String) : Exception(message)

/** One file or subdirectory on the gateway host. */
@Serializable
data class FileEntry(
    val name: String,
    val path: String,
    @SerialName("is_dir") val isDir: Boolean = false,
    val size: Long = 0L,
    @SerialName("mod_time") val modTime: Long = 0L,
)

/** Response of the gateway `GET /api/files/list` endpoint. */
@Serializable
data class FileListResponse(
    val path: String,
    val parent: String = "",
    val truncated: Boolean = false,
    val entries: List<FileEntry> = emptyList(),
)

/** Response of the gateway `GET /api/files/read` endpoint (text files only). */
@Serializable
data class FileReadResponse(
    val path: String,
    val name: String = "",
    val size: Long = 0L,
    val truncated: Boolean = false,
    val content: String = "",
)

/** Lists files and subdirectories of [path] (empty = gateway start directory). */
suspend fun listGatewayFiles(gateway: String, token: String, path: String): FileListResponse {
    val response = gatewayHttp.get(
        "${gatewayHttpBase(gateway)}/api/files/list?path=${urlEncode(path.trim())}",
    ) {
        authenticate(token)
    }
    val body = response.requireFileSuccess()
    return runCatching { PiJson.decodeFromString(FileListResponse.serializer(), body) }
        .getOrElse { throw FileApiException("invalid file list from gateway") }
}

/** Reads a text file on the gateway host; binary files are rejected by the gateway. */
suspend fun readGatewayFile(gateway: String, token: String, path: String): FileReadResponse {
    val response = gatewayHttp.get(
        "${gatewayHttpBase(gateway)}/api/files/read?path=${urlEncode(path.trim())}",
    ) {
        authenticate(token)
    }
    val body = response.requireFileSuccess()
    return runCatching { PiJson.decodeFromString(FileReadResponse.serializer(), body) }
        .getOrElse { throw FileApiException("invalid file content from gateway") }
}

/** Downloads the raw bytes of a file on the gateway host. */
suspend fun downloadGatewayFile(gateway: String, token: String, path: String): ByteArray {
    val response = gatewayHttp.get(
        "${gatewayHttpBase(gateway)}/api/files/download?path=${urlEncode(path.trim())}",
    ) {
        authenticate(token)
    }
    if (response.status.value !in 200..299) {
        val message = parseMessage(response.bodyAsText())?.str("message")
            ?: "HTTP ${response.status.value}"
        throw FileApiException(message)
    }
    return response.bodyAsChannel().toByteArray()
}

private fun HttpRequestBuilder.authenticate(token: String) {
    if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
}

private suspend fun HttpResponse.requireFileSuccess(): String {
    val body = bodyAsText()
    if (status.value !in 200..299) {
        val message = parseMessage(body)?.str("message") ?: "HTTP ${status.value}"
        throw FileApiException(message)
    }
    return body
}
