package io.github.yearsyan.ohpi.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIModalPresentationPageSheet
import platform.UIKit.UINavigationController
import platform.UIKit.UIViewController

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberPlatformUriHandler(): UriHandler {
    val viewController = LocalUIViewController.current
    return remember(viewController) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val url = NSURL.URLWithString(uri) ?: return
                when (url.scheme?.lowercase()) {
                    "http", "https" -> presentWebView(viewController, uri)
                    else -> UIApplication.sharedApplication.openURL(
                        url,
                        emptyMap<Any?, Any>(),
                        null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun presentWebView(host: UIViewController, url: String) {
    val navigation = UINavigationController(rootViewController = WebViewController(url))
    navigation.modalPresentationStyle = UIModalPresentationPageSheet
    topViewController(host).presentViewController(navigation, animated = true, completion = null)
}

internal fun topViewController(root: UIViewController): UIViewController {
    var top = root
    while (true) {
        top = top.presentedViewController ?: return top
    }
}
