package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.net.SshHostKeyPrompt

@Composable
fun SshHostKeyDialog(
    prompt: SshHostKeyPrompt,
    onReject: () -> Unit,
    onTrust: () -> Unit,
) {
    val endpoint = "${prompt.sshHost}:${prompt.sshPort}"
    AlertDialog(
        onDismissRequest = onReject,
        title = {
            Text(if (prompt.isChanged) S.sshHostKeyChangedTitle else S.sshHostKeyTitle)
        },
        text = {
            Column {
                Text(
                    if (prompt.isChanged) S.sshHostKeyChangedBody(endpoint)
                    else S.sshHostKeyBody(endpoint),
                    color =
                        if (prompt.isChanged) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                )
                prompt.expectedFingerprint?.takeIf { it.isNotBlank() }?.let { expected ->
                    Spacer(Modifier.height(16.dp))
                    Text(
                        S.sshExpectedFingerprint,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(expected, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    S.sshObservedFingerprint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        prompt.observedFingerprint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onTrust) { Text(S.sshTrustAndConnect) }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text(S.sshRejectHostKey) }
        },
    )
}
