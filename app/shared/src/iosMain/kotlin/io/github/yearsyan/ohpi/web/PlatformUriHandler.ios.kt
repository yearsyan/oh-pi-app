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
                if (isIosInAppBrowserUrl(url)) {
                    presentWebView(viewController, uri)
                } else {
                    openUrlInSystem(url)
                }
            }
        }
    }
}

/** Only loopback HTTP(S) pages are allowed in the iOS in-app browser. */
@OptIn(ExperimentalForeignApi::class)
internal fun isIosInAppBrowserUrl(url: NSURL): Boolean {
    val scheme = url.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false

    val host =
        url.host
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.removeSuffix(".")
            ?.lowercase()
            ?: return false
    return host == "localhost" ||
        host.endsWith(".localhost") ||
        isIpv4LoopbackHost(host) ||
        isIpv6LoopbackHost(host)
}

private fun isIpv4LoopbackHost(host: String): Boolean {
    val octets = host.split('.')
    return octets.size == 4 &&
        octets.first() == "127" &&
        octets.all { octet ->
            octet.isNotEmpty() &&
                octet.all { it in '0'..'9' } &&
                (octet.toIntOrNull() ?: -1) in 0..255
        }
}

private fun isIpv6LoopbackHost(host: String): Boolean {
    if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true

    val mappedIpv4 =
        when {
            host.startsWith("::ffff:") -> host.removePrefix("::ffff:")
            host.startsWith("0:0:0:0:0:ffff:") -> host.removePrefix("0:0:0:0:0:ffff:")
            else -> return false
        }
    return isIpv4LoopbackHost(mappedIpv4)
}

@OptIn(ExperimentalForeignApi::class)
internal fun openUrlInSystem(url: NSURL) {
    UIApplication.sharedApplication.openURL(
        url,
        emptyMap<Any?, Any>(),
        null,
    )
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
