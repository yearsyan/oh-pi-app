package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent

/** Handles platform paste shortcuts only when the clipboard contains an image. */
@Composable
internal expect fun rememberClipboardImagePasteHandler(
    enabled: Boolean,
    onResult: (ImagePickResult) -> Unit,
): (KeyEvent) -> Boolean
