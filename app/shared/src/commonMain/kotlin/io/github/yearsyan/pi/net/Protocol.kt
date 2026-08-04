package io.github.yearsyan.pi.net

import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

val PiJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.strOrEmpty(key: String): String = str(key) ?: ""

/** Extracts readable text from a pi message `content` field (string or block array). */
fun contentText(element: JsonElement?): String = when (element) {
    null -> ""
    is JsonPrimitive -> element.contentOrNull ?: ""
    is JsonArray -> element.mapNotNull { block ->
        (block as? JsonObject)?.let { it.str("text") ?: it.str("thinking") }
    }.filter { it.isNotEmpty() }.joinToString("\n")
    else -> ""
}

/** Extracts only plain text blocks (used for tool results). */
fun plainText(element: JsonElement?): String = when (element) {
    null -> ""
    is JsonPrimitive -> element.contentOrNull ?: ""
    is JsonArray -> element.mapNotNull { block ->
        (block as? JsonObject)?.str("text")
    }.filter { it.isNotEmpty() }.joinToString("\n")
    else -> ""
}

fun argsToString(element: JsonElement?): String = when (element) {
    null -> ""
    is JsonPrimitive -> element.contentOrNull ?: ""
    else -> try {
        PiJson.encodeToString(JsonElement.serializer(), element).let { pretty ->
            // cheap pretty print: re-parse through a configured Json with prettyPrint
            Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(pretty))
        }
    } catch (_: Throwable) {
        element.toString()
    }
}

fun parseMessage(text: String): JsonObject? =
    runCatching { PiJson.parseToJsonElement(text).jsonObject }.getOrNull()

fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/** Normalizes a user-entered gateway address into a ws(s) base URL. */
fun normalizeGatewayUrl(raw: String): String {
    var url = raw.trim().trimEnd('/')
    url = when {
        url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
        url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
        else -> url
    }
    return url
}

internal data class GatewayAddress(
    val host: String,
    val port: Int,
    val tls: Boolean,
)

/** Splits a persisted legacy gateway URL into the fields shown by the editor. */
internal fun parseGatewayAddress(raw: String): GatewayAddress? {
    val url = runCatching { Url(normalizeGatewayUrl(raw)) }.getOrNull() ?: return null
    val scheme = url.protocol.name.lowercase()
    if (scheme != "ws" && scheme != "wss") return null
    val host = url.host.removePrefix("[").removeSuffix("]")
    if (host.isBlank() || url.port !in 1..65535) return null
    return GatewayAddress(host = host, port = url.port, tls = scheme == "wss")
}

/** Builds the protocol-bearing representation kept internally by ServerProfile. */
internal fun buildGatewayUrl(host: String, port: Int, tls: Boolean): String {
    val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
    val authorityHost = if (':' in cleanHost) "[$cleanHost]" else cleanHost
    return "${if (tls) "wss" else "ws"}://$authorityHost:$port"
}

/** User-facing gateway label that avoids exposing the internal HTTP/WS scheme. */
internal fun gatewayAddressLabel(raw: String): String {
    val address = parseGatewayAddress(raw) ?: return raw
    val host = if (':' in address.host) "[${address.host}]" else address.host
    return buildString {
        append(host).append(':').append(address.port)
        if (address.tls) append(" · TLS")
    }
}

fun isValidGatewayUrl(raw: String): Boolean {
    return parseGatewayAddress(raw) != null
}

fun buildWsUrl(
    base: String,
    token: String,
    action: String,
    sessionId: String?,
    workDir: String = "",
    initialModel: String = "",
    initialThinking: String = "",
    entrySince: String = "",
): String {
    val b = StringBuilder(normalizeGatewayUrl(base)).append("/ws?action=").append(action)
    if (!sessionId.isNullOrBlank()) b.append("&session_id=").append(urlEncode(sessionId))
    if (workDir.isNotBlank()) b.append("&work_dir=").append(urlEncode(workDir))
    if (action == "create" && initialModel.isNotBlank()) {
        b.append("&model=").append(urlEncode(initialModel))
    }
    if (action == "create" && initialThinking.isNotBlank()) {
        b.append("&thinking=").append(urlEncode(initialThinking))
    }
    if (action == "attach" && entrySince.isNotBlank()) {
        b.append("&entry_since=").append(urlEncode(entrySince))
    }
    if (token.isNotBlank()) b.append("&token=").append(urlEncode(token))
    return b.toString()
}

fun urlEncode(value: String): String = buildString {
    for (c in value) {
        when {
            c.isLetterOrDigit() || c in "-_.~" -> append(c)
            else -> append('%')
                .append(c.code.toByte().toInt().and(0xFF).toString(16).uppercase().padStart(2, '0'))
        }
    }
}
