@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yearsyan.ohpi.ui.privacy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import io.github.yearsyan.ohpi.web.openUrlInSystem
import io.github.yearsyan.ohpi.web.topViewController
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIViewController

private const val AiDataConsentResetGenerationKey =
    "ohpi.ai-data-consent.reset-generation"

@Composable
internal actual fun rememberAiDataConsentPresenter(): AiDataConsentPresenter {
    val viewController = LocalUIViewController.current
    return remember(viewController) {
        IosAiDataConsentPresenter(viewController, NSUserDefaults.standardUserDefaults)
    }
}

private class IosAiDataConsentPresenter(
    private val host: UIViewController,
    private val defaults: NSUserDefaults,
) : AiDataConsentPresenter {
    override val requiresExplicitConsent: Boolean = true
    private var activeAlert: UIAlertController? = null

    override fun requestConsent(request: AiDataConsentRequest, onGranted: () -> Unit) {
        val key = consentStorageKey(request)
        if (defaults.boolForKey(key)) {
            onGranted()
            return
        }
        if (activeAlert != null) return

        val alert =
            UIAlertController.alertControllerWithTitle(
                title = request.title,
                message = request.message,
                preferredStyle = UIAlertControllerStyleAlert,
            )
        activeAlert = alert
        alert.addAction(
            UIAlertAction.actionWithTitle(
                title = request.cancelLabel,
                style = UIAlertActionStyleCancel,
                handler = { activeAlert = null },
            ),
        )
        alert.addAction(
            UIAlertAction.actionWithTitle(
                title = request.privacyPolicyLabel,
                style = UIAlertActionStyleDefault,
                handler = {
                    activeAlert = null
                    NSURL.URLWithString(AiPrivacyPolicyUrl)?.let(::openUrlInSystem)
                },
            ),
        )
        alert.addAction(
            UIAlertAction.actionWithTitle(
                title = request.agreeAndSendLabel,
                style = UIAlertActionStyleDefault,
                handler = {
                    defaults.setBool(true, forKey = key)
                    activeAlert = null
                    onGranted()
                },
            ),
        )
        alert.preferredAction = alert.actions.lastOrNull() as? UIAlertAction
        topViewController(host).presentViewController(alert, animated = true, completion = null)
    }

    override fun revokeAllConsents() {
        val nextGeneration = defaults.integerForKey(AiDataConsentResetGenerationKey) + 1
        defaults.setInteger(nextGeneration, forKey = AiDataConsentResetGenerationKey)
    }

    private fun consentStorageKey(request: AiDataConsentRequest): String {
        val generation = defaults.integerForKey(AiDataConsentResetGenerationKey)
        return "${aiDataConsentKey(request.serverId, request.providerId)}.r$generation"
    }
}
