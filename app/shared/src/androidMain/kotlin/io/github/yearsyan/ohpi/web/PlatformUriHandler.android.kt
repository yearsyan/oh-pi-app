package io.github.yearsyan.ohpi.web

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.UriHandler

@Composable
internal actual fun rememberPlatformUriHandler(): UriHandler {
    val context = LocalContext.current
    return remember(context) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val scheme = runCatching { Uri.parse(uri).scheme?.lowercase() }.getOrNull()
                if (scheme == "http" || scheme == "https") {
                    WebViewActivity.start(context, uri)
                } else {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                    }
                }
            }
        }
    }
}
