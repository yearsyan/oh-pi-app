package io.github.yearsyan.ohpi.ui.privacy

import androidx.compose.runtime.Composable

internal const val AiDataConsentVersion = 1
internal const val AiPrivacyPolicyUrl = "https://yearsyan.github.io/oh-pi-app/"

/** Fully localized disclosure presented before an iOS prompt leaves the device. */
internal data class AiDataConsentRequest(
    val serverId: String,
    val providerId: String,
    val title: String,
    val message: String,
    val cancelLabel: String,
    val privacyPolicyLabel: String,
    val agreeAndSendLabel: String,
    val rememberGrant: Boolean = true,
)

/** Platform gate; non-iOS targets grant immediately without presenting UI. */
internal interface AiDataConsentPresenter {
    val requiresExplicitConsent: Boolean

    fun requestConsent(request: AiDataConsentRequest, onGranted: () -> Unit)

    /** Invalidates all prior grants so the next send to each provider asks again. */
    fun revokeAllConsents()
}

@Composable
internal expect fun rememberAiDataConsentPresenter(): AiDataConsentPresenter

/** Length-prefixing prevents different server/provider boundaries from colliding. */
internal fun aiDataConsentKey(
    serverId: String,
    providerId: String,
    version: Int = AiDataConsentVersion,
): String {
    val server = serverId.trim()
    val provider = providerId.trim().lowercase()
    return "ohpi.ai-data-consent.v$version.${server.length}:$server.${provider.length}:$provider"
}
