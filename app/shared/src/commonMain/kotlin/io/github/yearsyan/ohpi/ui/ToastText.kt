package io.github.yearsyan.ohpi.ui

import io.github.yearsyan.ohpi.i18n.Strings
import io.github.yearsyan.ohpi.net.friendlyHttpError
import io.github.yearsyan.ohpi.net.isTransientNetworkError

private const val MaxToastTextLength = 160

private val toastWhitespace = Regex("\\s+")
private val toastUrlQuery =
    Regex("((?:https?|wss?)://[^\\s?,'\"}]+)\\?[^\\s,'\"}]+", RegexOption.IGNORE_CASE)
private val toastBearerSecret =
    Regex("(bearer\\s+)[a-z0-9._~-]+", RegexOption.IGNORE_CASE)
private val toastNamedSecret =
    Regex(
        "(token|access[_-]?token|api[_-]?key|authorization)(\\s*[=:]\\s*[\"']?)[^&\\s,\"'}]+",
        RegexOption.IGNORE_CASE,
    )
private val toastAuthenticationStatus = Regex("(^|[^0-9])(401|403)([^0-9]|$)")

/**
 * Turns transport/runtime diagnostics into a short localized message suitable
 * for transient UI. Detailed exceptions still belong in logs, never in a toast.
 */
internal fun localizedErrorToast(raw: String, strings: Strings): String {
    val text = friendlyHttpError(raw).trim()
    if (text.isBlank()) return strings.unknownError
    val lower = text.lowercase()

    val localized =
        when {
            lower == "error" || lower == "unknown error" -> strings.unknownError
            lower.containsAny(
                "appears to be offline",
                "not connected to the internet",
                "network is unreachable",
                "no network connection",
                "nsurlerrordomain code=-1009",
            ) -> strings.networkOfflineError
            lower.containsAny(
                "timed out",
                "timeout",
                "nsurlerrordomain code=-1001",
            ) -> strings.requestTimedOutError
            toastAuthenticationStatus.containsMatchIn(lower) ||
                lower.containsAny(
                    "unauthorized",
                    "forbidden",
                    "invalid token",
                    "authentication failed",
                ) -> strings.authenticationFailedError
            isTransientNetworkError(text) ||
                lower.containsAny(
                    "socket is not connected",
                    "nsposixerrordomain code=57",
                ) -> strings.connectionInterruptedError
            lower.containsAny(
                "connection refused",
                "could not connect",
                "cannot connect",
                "failed to connect",
                "host not found",
                "name or service not known",
                "dns lookup",
                "nsurlerrordomain code=-1003",
                "nsurlerrordomain code=-1004",
                "nsurlerrordomain code=-1006",
            ) -> strings.serverUnreachableError
            lower.containsAny(
                "exception in http request",
                "nserrorfailingurl",
                "nsurlerrordomain",
                "nsposixerrordomain",
            ) -> strings.serverUnreachableError
            else -> redactAndShortenToast(text)
        }

    return localized.ifBlank { strings.unknownError }
}

/** Keeps all toast kinds compact and prevents URL credentials reaching UI. */
internal fun conciseToastText(raw: String): String = redactAndShortenToast(raw)

private fun redactAndShortenToast(raw: String): String {
    var text = toastWhitespace.replace(raw.trim(), " ")
    text = toastUrlQuery.replace(text) { match -> match.groupValues[1] }
    text = toastBearerSecret.replace(text) { match -> "${match.groupValues[1]}•••" }
    text = toastNamedSecret.replace(text) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}•••"
    }
    if (text.length <= MaxToastTextLength) return text
    return text.take(MaxToastTextLength - 1).trimEnd() + "…"
}

private fun String.containsAny(vararg candidates: String): Boolean =
    candidates.any(::contains)
