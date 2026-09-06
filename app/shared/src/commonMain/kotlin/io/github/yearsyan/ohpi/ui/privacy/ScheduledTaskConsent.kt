package io.github.yearsyan.ohpi.ui.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import io.github.yearsyan.ohpi.chat.Toast
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.net.GatewayCapabilities
import io.github.yearsyan.ohpi.net.GatewayModelCapability
import io.github.yearsyan.ohpi.ui.AppViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Resolve and pin the provider shown in the disclosure, including workspace defaults. */
internal fun scheduledTaskConsentModel(model: String, capabilities: GatewayCapabilities): GatewayModelCapability? {
    val selected = model.trim().ifBlank {
        capabilities.defaultSelection?.let { "${it.provider}/${it.modelId}" }.orEmpty()
    }
    return capabilities.models.singleOrNull { "${it.provider}/${it.id}" == selected }
}

/** Scheduled runs need their own explicit approval; a chat grant cannot authorize automation. */
@Composable
internal fun rememberScheduledTaskConsent(
    vm: AppViewModel,
): (String, String, (String) -> Unit) -> Unit {
    val presenter = rememberAiDataConsentPresenter()
    val scope = rememberCoroutineScope()
    val strings = S
    return { workspaceId, model, onGranted ->
        if (!presenter.requiresExplicitConsent) {
            onGranted(model)
        } else {
            val serverId = vm.activeServerId
            scope.launch {
                try {
                    val selected = scheduledTaskConsentModel(model, vm.loadScheduledTaskCapabilities(workspaceId))
                    if (selected == null) {
                        vm.toast(strings.scheduledTaskConsentModelRequired, Toast.Kind.Error)
                    } else if (vm.activeServerId == serverId) {
                        val pinnedModel = "${selected.provider}/${selected.id}"
                        presenter.requestConsent(
                            AiDataConsentRequest(
                                serverId = serverId,
                                providerId = selected.provider,
                                title = strings.aiDataConsentTitle(selected.provider),
                                message = strings.scheduledTaskConsentMessage(selected.provider, pinnedModel),
                                cancelLabel = strings.cancel,
                                privacyPolicyLabel = strings.privacyPolicy,
                                agreeAndSendLabel = strings.scheduledTaskConsentAgree,
                                rememberGrant = false,
                            ),
                            onGranted = { if (vm.activeServerId == serverId) onGranted(pinnedModel) },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    vm.toast(strings.modelOptionsFailed(failure.message ?: strings.unknownError), Toast.Kind.Error)
                }
            }
        }
    }
}
