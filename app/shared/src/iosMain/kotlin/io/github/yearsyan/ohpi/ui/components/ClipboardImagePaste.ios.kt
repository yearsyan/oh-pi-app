package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent

@Composable
internal actual fun rememberClipboardImagePasteHandler(
    enabled: Boolean,
    onResult: (ImagePickResult) -> Unit,
): (KeyEvent) -> Boolean = remember { { _: KeyEvent -> false } }
