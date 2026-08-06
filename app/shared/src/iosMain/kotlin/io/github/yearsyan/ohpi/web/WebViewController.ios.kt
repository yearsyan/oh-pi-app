package io.github.yearsyan.ohpi.web

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSURLRequest
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIBarButtonItem
import platform.UIKit.UIBarButtonSystemItem
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController
import platform.UIKit.navigationItem
import platform.WebKit.WKAudiovisualMediaTypeNone
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWindowFeatures

/**
 * In-app browser presented modally for http/https links. JavaScript and
 * inline media are enabled by default; the extra delegates keep target=_blank
 * navigations inside the view and hand non-http(s) schemes to the system.
 */
@OptIn(ExperimentalForeignApi::class)
internal class WebViewController(private val url: String) :
    UIViewController(nibName = null, bundle = null),
    WKNavigationDelegateProtocol,
    WKUIDelegateProtocol {

    private var webView: WKWebView? = null

    override fun viewDidLoad() {
        super.viewDidLoad()
        val configuration = WKWebViewConfiguration().apply {
            allowsInlineMediaPlayback = true
            mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeNone
            defaultWebpagePreferences.allowsContentJavaScript = true
        }
        val webView = WKWebView(frame = view.bounds, configuration = configuration)
        webView.autoresizingMask =
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight
        webView.allowsBackForwardNavigationGestures = true
        webView.navigationDelegate = this
        webView.UIDelegate = this
        view.addSubview(webView)
        this.webView = webView

        navigationItem.title = NSURL.URLWithString(url)?.host ?: url
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem = UIBarButtonSystemItem.UIBarButtonSystemItemDone,
            target = this,
            action = NSSelectorFromString("close"),
        )
        NSURL.URLWithString(url)?.let { webView.loadRequest(NSURLRequest(it)) }
    }

    @ObjCAction
    fun close() {
        dismissViewControllerAnimated(true, completion = null)
    }

    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        webView.title?.takeIf { it.isNotBlank() }?.let { navigationItem.title = it }
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val target = decidePolicyForNavigationAction.request.URL
        val scheme = target?.scheme?.lowercase()
        if (target != null && scheme != null && scheme != "http" && scheme != "https") {
            // tel:, mailto:, app deep links, ... hand off to the system.
            UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any>(), null)
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        } else {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        }
    }

    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        // No real multi-window support: load target=_blank in the same view.
        if (forNavigationAction.targetFrame?.mainFrame != true) {
            forNavigationAction.request.URL?.let {
                webView.loadRequest(NSURLRequest(it))
            }
        }
        return null
    }
}
