package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.ui.LoopbackLinkPrompt

/**
 * Shown when a loopback link is tapped on an SSH-connected server that has no
 * port forward covering the link's port.
 */
@Composable
fun LoopbackLinkDialog(
    prompt: LoopbackLinkPrompt,
    onMapAndOpen: () -> Unit,
    onOpenLocal: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(S.loopbackPromptTitle) },
        text = { Text(S.loopbackPromptBody(prompt.remotePort)) },
        confirmButton = {
            TextButton(onClick = onMapAndOpen) { Text(S.loopbackMapAndOpen) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onOpenLocal) { Text(S.loopbackOpenLocal) }
                TextButton(onClick = onCancel) { Text(S.cancel) }
            }
        },
    )
}
