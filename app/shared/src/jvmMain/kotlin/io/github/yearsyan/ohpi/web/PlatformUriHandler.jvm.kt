package io.github.yearsyan.ohpi.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.UriHandler
import java.awt.Desktop
import java.net.URI

/** Desktop links always go to the system browser. */
@Composable
internal actual fun rememberPlatformUriHandler(): UriHandler =
    remember {
        object : UriHandler {
            override fun openUri(uri: String) {
                runCatching {
                    if (Desktop.isDesktopSupported()) {
                        val desktop = Desktop.getDesktop()
                        val target = URI(uri)
                        val action =
                            if (target.scheme.equals("mailto", ignoreCase = true)) {
                                Desktop.Action.MAIL
                            } else {
                                Desktop.Action.BROWSE
                            }
                        if (desktop.isSupported(action)) {
                            if (action == Desktop.Action.MAIL) desktop.mail(target)
                            else desktop.browse(target)
                        }
                    }
                }
            }
        }
    }
