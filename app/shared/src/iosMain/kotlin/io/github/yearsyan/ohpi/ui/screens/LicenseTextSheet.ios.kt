@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.window.ComposeUIViewController
import io.github.yearsyan.ohpi.i18n.LocalStrings
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.i18n.Strings
import io.github.yearsyan.ohpi.theme.PiTheme
import io.github.yearsyan.ohpi.web.topViewController
import platform.UIKit.UIModalPresentationPageSheet
import platform.UIKit.UISheetPresentationControllerDetent
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.addChildViewController
import platform.UIKit.didMoveToParentViewController
import platform.UIKit.sheetPresentationController

@Composable
internal actual fun LicenseTextSheet(
    title: String,
    text: String,
    onDismiss: () -> Unit,
) {
    val host = LocalUIViewController.current
    val strings = S
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val controller =
        remember(host, title, text, strings, darkTheme) {
            IosLicenseTextSheetController(
                title = title,
                text = text,
                strings = strings,
                darkTheme = darkTheme,
                onDismiss = { currentOnDismiss() },
            )
        }

    DisposableEffect(host, controller) {
        if (controller.presentingViewController == null) {
            topViewController(host).presentViewController(
                controller,
                animated = true,
                completion = null,
            )
        }
        onDispose {
            if (controller.presentingViewController != null && !controller.beingDismissed) {
                controller.dismissViewControllerAnimated(false, completion = null)
            }
        }
    }
}

private class IosLicenseTextSheetController(
    title: String,
    text: String,
    strings: Strings,
    darkTheme: Boolean,
    private val onDismiss: () -> Unit,
) : UIViewController(nibName = null, bundle = null) {
    private var notifiedDismissal = false
    private val contentController =
        ComposeUIViewController {
            CompositionLocalProvider(LocalStrings provides strings) {
                PiTheme(darkTheme = darkTheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        LicenseTextSheetScaffold(
                            title = title,
                            text = text,
                            onDismiss = ::dismissFromCompose,
                            modifier = Modifier.fillMaxSize(),
                            heightFraction = 1f,
                        )
                    }
                }
            }
        }

    init {
        modalPresentationStyle = UIModalPresentationPageSheet
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        sheetPresentationController?.apply {
            detents = listOf(
                UISheetPresentationControllerDetent.mediumDetent(),
                UISheetPresentationControllerDetent.largeDetent(),
            )
            prefersGrabberVisible = true
        }
        addChildViewController(contentController)
        sizeViewToParent(contentController.view, view)
        contentController.view.autoresizingMask =
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        view.addSubview(contentController.view)
        contentController.didMoveToParentViewController(this)
    }

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        if (beingDismissed) {
            notifyDismissed()
        }
    }

    private fun dismissFromCompose() {
        if (notifiedDismissal) return
        dismissViewControllerAnimated(
            true,
            completion = { notifyDismissed() },
        )
    }

    private fun notifyDismissed() {
        if (notifiedDismissal) return
        notifiedDismissal = true
        onDismiss()
    }
}
