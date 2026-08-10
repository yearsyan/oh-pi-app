package io.github.yearsyan.ohpi.ui.privacy

import androidx.compose.runtime.Composable

private object PassThroughAiDataConsentPresenter : AiDataConsentPresenter {
    override val requiresExplicitConsent: Boolean = false

    override fun requestConsent(request: AiDataConsentRequest, onGranted: () -> Unit) {
        onGranted()
    }

    override fun revokeAllConsents() = Unit
}

@Composable
internal actual fun rememberAiDataConsentPresenter(): AiDataConsentPresenter =
    PassThroughAiDataConsentPresenter
