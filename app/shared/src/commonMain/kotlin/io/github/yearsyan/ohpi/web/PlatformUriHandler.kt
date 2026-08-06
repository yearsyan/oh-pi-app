package io.github.yearsyan.ohpi.web

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.UriHandler

/**
 * App-wide [UriHandler]: http/https links open in an in-app WebView on
 * Android/iOS; desktop hands every link to the system browser.
 */
@Composable
internal expect fun rememberPlatformUriHandler(): UriHandler
