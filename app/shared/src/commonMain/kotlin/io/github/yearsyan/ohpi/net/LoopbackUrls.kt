package io.github.yearsyan.ohpi.net

import io.ktor.http.URLBuilder
import io.ktor.http.Url

private val LoopbackHostNames = setOf("localhost", "127.0.0.1", "::1")

/** Host names that always resolve to the machine the process runs on. */
internal fun isLoopbackHostName(host: String): Boolean {
    val normalized = host.trim().removePrefix("[").removeSuffix("]").lowercase()
    return normalized in LoopbackHostNames || normalized.endsWith(".localhost")
}

/**
 * If [raw] is an http(s) URL pointing at a loopback address, returns its
 * normalized host and effective port; otherwise null.
 */
internal fun loopbackUrlTarget(raw: String): Pair<String, Int>? {
    if (!raw.startsWith("http://", ignoreCase = true) &&
        !raw.startsWith("https://", ignoreCase = true)
    ) {
        return null
    }
    val url = runCatching { Url(raw) }.getOrNull() ?: return null
    if (url.host.isBlank() || !isLoopbackHostName(url.host)) return null
    val host = url.host.removePrefix("[").removeSuffix("]").lowercase()
    return host to url.port
}

/** Rewrites a loopback URL to this device's loopback and the mapped port. */
internal fun rewriteLoopbackUrl(raw: String, localPort: Int): String {
    val builder = URLBuilder(Url(raw))
    builder.host = "127.0.0.1"
    builder.port = localPort
    return builder.buildString()
}

/**
 * Rewrites a loopback URL to the gateway host itself. Used for direct (non-SSH)
 * connections, where a localhost link printed by pi refers to the gateway host.
 */
internal fun rewriteLoopbackUrlToGatewayHost(raw: String, gatewayHost: String): String {
    val builder = URLBuilder(Url(raw))
    builder.host = gatewayHost
    return builder.buildString()
}
