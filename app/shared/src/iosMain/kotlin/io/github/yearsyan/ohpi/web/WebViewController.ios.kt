package io.github.yearsyan.ohpi.web

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.Foundation.NSURLRequest
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSURL
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
 * In-app browser presented modally for loopback HTTP(S) links. JavaScript and
 * inline media are enabled by default. Navigation is kept inside the view only
 * for loopback destinations; everything else is handed to the system.
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
        if (target != null && isIosInAppBrowserUrl(target)) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            return
        }

        // Do not let an external page or custom scheme replace the loopback
        // page. Main-frame and target=_blank navigations go to the user's
        // system browser/app; external subframes are simply blocked.
        if (target != null && decidePolicyForNavigationAction.targetFrame?.mainFrame != false) {
            openUrlInSystem(target)
        }
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
    }

    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        // No real multi-window support: keep loopback target=_blank links in
        // this view. A defensive external fallback covers WebKit versions that
        // reach this delegate without first invoking the policy callback.
        if (forNavigationAction.targetFrame?.mainFrame != true) {
            forNavigationAction.request.URL?.let { target ->
                if (isIosInAppBrowserUrl(target)) {
                    webView.loadRequest(NSURLRequest(target))
                } else {
                    openUrlInSystem(target)
                }
            }
        }
        return null
    }
}
