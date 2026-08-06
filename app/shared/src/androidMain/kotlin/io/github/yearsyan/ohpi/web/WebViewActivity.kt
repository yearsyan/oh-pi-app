package io.github.yearsyan.ohpi.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import io.github.yearsyan.ohpi.i18n.LocalStrings
import io.github.yearsyan.ohpi.theme.PiTheme

/** Full-screen in-app browser for http/https links tapped inside the app. */
class WebViewActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private val openDocuments =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            pendingFileCallback?.onReceiveValue(uris.toTypedArray())
            pendingFileCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            PiTheme(darkTheme = isSystemInDarkTheme()) {
                WebViewScreen(
                    initialUrl = url,
                    onClose = ::finish,
                    onWebView = { webView = it },
                    onFileChooser = ::launchFileChooser,
                )
            }
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    private fun launchFileChooser(
        callback: ValueCallback<Array<Uri>>,
        acceptTypes: Array<String>,
    ): Boolean {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback
        return try {
            openDocuments.launch(acceptTypes)
            true
        } catch (_: ActivityNotFoundException) {
            pendingFileCallback = null
            false
        }
    }

    companion object {
        private const val EXTRA_URL = "url"

        fun start(context: Context, url: String) {
            context.startActivity(
                Intent(context, WebViewActivity::class.java).putExtra(EXTRA_URL, url),
            )
        }
    }
}

@Suppress("SetJavaScriptEnabled")
private fun configureWebView(settings: WebSettings) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    // target=_blank navigations fall back to shouldOverrideUrlLoading instead.
    settings.setSupportMultipleWindows(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebViewScreen(
    initialUrl: String,
    onClose: () -> Unit,
    onWebView: (WebView) -> Unit,
    onFileChooser: (ValueCallback<Array<Uri>>, Array<String>) -> Boolean,
) {
    var progress by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val fallbackTitle = remember(initialUrl) { Uri.parse(initialUrl).host ?: initialUrl }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            title.ifBlank { fallbackTitle },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = LocalStrings.current.close,
                            )
                        }
                    },
                )
                if (progress in 1 until 100) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    configureWebView(settings)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val scheme = request.url.scheme?.lowercase()
                            if (scheme == "http" || scheme == "https") return false
                            // tel:, mailto:, intent:, ... hand off to the system.
                            runCatching {
                                view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                            }
                            return true
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView,
                            url: String?,
                            isReload: Boolean,
                        ) {
                            canGoBack = view.canGoBack()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onReceivedTitle(view: WebView, pageTitle: String?) {
                            title = pageTitle.orEmpty()
                        }

                        override fun onShowFileChooser(
                            view: WebView,
                            filePathCallback: ValueCallback<Array<Uri>>,
                            fileChooserParams: FileChooserParams,
                        ): Boolean {
                            val accept = fileChooserParams.acceptTypes
                                .filter { it.isNotBlank() }
                                .toTypedArray()
                            return onFileChooser(
                                filePathCallback,
                                if (accept.isEmpty()) arrayOf("*/*") else accept,
                            )
                        }
                    }
                    loadUrl(initialUrl)
                }.also { created ->
                    webView = created
                    onWebView(created)
                }
            },
        )
    }
}
