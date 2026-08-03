package io.github.yearsyan.pi.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.yearsyan.pi.data.ServerConnectionMode
import io.github.yearsyan.pi.data.ServerProfile
import io.github.yearsyan.pi.data.SshAuthentication
import io.github.yearsyan.pi.data.SshServerProfile
import io.github.yearsyan.pi.i18n.S
import io.github.yearsyan.pi.i18n.Strings
import io.github.yearsyan.pi.net.isValidGatewayUrl
import io.github.yearsyan.pi.net.normalizeGatewayUrl
import kotlin.random.Random

internal class ServerEditorState(initial: ServerProfile?) {
    private val id = initial?.id ?: Random.nextLong().toString(16)

    var name by mutableStateOf(initial?.name.orEmpty())
    var url by mutableStateOf(initial?.url.orEmpty())
    var token by mutableStateOf(initial?.token.orEmpty())
    var connectionMode by mutableStateOf(initial?.connectionMode ?: ServerConnectionMode.Direct)
    var sshHost by mutableStateOf(initial?.ssh?.host.orEmpty())
    var sshPort by mutableStateOf((initial?.ssh?.port ?: 22).toString())
    var sshUsername by mutableStateOf(initial?.ssh?.username.orEmpty())
    var sshAuthentication by
        mutableStateOf(initial?.ssh?.authentication ?: SshAuthentication.Password)
    var sshPassword by mutableStateOf(initial?.ssh?.password.orEmpty())
    var sshPrivateKey by mutableStateOf(initial?.ssh?.privateKey.orEmpty())
    var sshPrivateKeyPassphrase by mutableStateOf(initial?.ssh?.privateKeyPassphrase.orEmpty())
    var sshHostKeySha256 by mutableStateOf(initial?.ssh?.hostKeySha256.orEmpty())
    var error by mutableStateOf<String?>(null)

    fun clearError() {
        error = null
    }

    fun build(strings: Strings): ServerProfile? {
        val parsedPort = sshPort.toIntOrNull()
        error =
            when {
                url.isBlank() -> strings.serverRequired
                !isValidGatewayUrl(url) -> strings.serverUrlInvalid
                connectionMode == ServerConnectionMode.Ssh &&
                    normalizeGatewayUrl(url).startsWith("wss://") -> strings.sshGatewayTlsInvalid
                connectionMode == ServerConnectionMode.Ssh && sshHost.isBlank() -> strings.sshHostRequired
                connectionMode == ServerConnectionMode.Ssh && parsedPort !in 1..65535 -> strings.sshPortInvalid
                connectionMode == ServerConnectionMode.Ssh && sshUsername.isBlank() ->
                    strings.sshUsernameRequired
                connectionMode == ServerConnectionMode.Ssh &&
                    sshAuthentication == SshAuthentication.Password &&
                    sshPassword.isEmpty() -> strings.sshPasswordRequired
                connectionMode == ServerConnectionMode.Ssh &&
                    sshAuthentication == SshAuthentication.PrivateKey &&
                    sshPrivateKey.isBlank() -> strings.sshPrivateKeyRequired
                else -> null
            }
        if (error != null) return null

        val ssh =
            if (connectionMode == ServerConnectionMode.Ssh) {
                SshServerProfile(
                    host = sshHost.trim(),
                    port = parsedPort ?: 22,
                    username = sshUsername.trim(),
                    authentication = sshAuthentication,
                    password = sshPassword.takeIf { sshAuthentication == SshAuthentication.Password }.orEmpty(),
                    privateKey =
                        sshPrivateKey
                            .takeIf { sshAuthentication == SshAuthentication.PrivateKey }
                            .orEmpty(),
                    privateKeyPassphrase =
                        sshPrivateKeyPassphrase
                            .takeIf { sshAuthentication == SshAuthentication.PrivateKey }
                            .orEmpty(),
                    hostKeySha256 = sshHostKeySha256.trim(),
                )
            } else {
                SshServerProfile()
            }
        return ServerProfile(
            id = id,
            name = name.trim(),
            url = url.trim(),
            token = token.trim(),
            connectionMode = connectionMode,
            ssh = ssh,
        )
    }
}

@Composable
internal fun rememberServerEditor(initial: ServerProfile?): ServerEditorState =
    remember(initial) { ServerEditorState(initial) }

@Composable
internal fun ServerEditorFields(editor: ServerEditorState) {
    OutlinedTextField(
        value = editor.name,
        onValueChange = { editor.name = it; editor.clearError() },
        label = { Text(S.serverNameLabel) },
        placeholder = { Text(S.serverNamePlaceholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))

    Text(
        S.connectionModeLabel,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val options =
            listOf(
                ServerConnectionMode.Direct to S.directConnection,
                ServerConnectionMode.Ssh to S.sshConnection,
            )
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = editor.connectionMode == mode,
                onClick = { editor.connectionMode = mode; editor.clearError() },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label) }
        }
    }
    Spacer(Modifier.height(10.dp))

    OutlinedTextField(
        value = editor.url,
        onValueChange = { editor.url = it; editor.clearError() },
        label = { Text(S.serverUrlLabel) },
        placeholder = { Text(S.serverUrlPlaceholder) },
        singleLine = true,
        isError = editor.error != null && editor.url.isBlank(),
        supportingText =
            if (editor.connectionMode == ServerConnectionMode.Ssh) {
                { Text(S.sshGatewayPlaintextHint) }
            } else {
                null
            },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = editor.token,
        onValueChange = { editor.token = it; editor.clearError() },
        label = { Text(S.serverTokenLabel) },
        placeholder = { Text(S.serverTokenPlaceholder) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    if (editor.connectionMode == ServerConnectionMode.Ssh) {
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = editor.sshHost,
                onValueChange = { editor.sshHost = it; editor.clearError() },
                label = { Text(S.sshHostLabel) },
                placeholder = { Text("server.example.com") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = editor.sshPort,
                onValueChange = { editor.sshPort = it.filter(Char::isDigit); editor.clearError() },
                label = { Text(S.sshPortLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.38f),
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = editor.sshUsername,
            onValueChange = { editor.sshUsername = it; editor.clearError() },
            label = { Text(S.sshUsernameLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        Text(
            S.sshAuthenticationLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options =
                listOf(
                    SshAuthentication.Password to S.sshPasswordAuthentication,
                    SshAuthentication.PrivateKey to S.sshPrivateKeyAuthentication,
                )
            options.forEachIndexed { index, (authentication, label) ->
                SegmentedButton(
                    selected = editor.sshAuthentication == authentication,
                    onClick = { editor.sshAuthentication = authentication; editor.clearError() },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label) }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (editor.sshAuthentication == SshAuthentication.Password) {
            OutlinedTextField(
                value = editor.sshPassword,
                onValueChange = { editor.sshPassword = it; editor.clearError() },
                label = { Text(S.sshPasswordLabel) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = editor.sshPrivateKey,
                onValueChange = { editor.sshPrivateKey = it; editor.clearError() },
                label = { Text(S.sshPrivateKeyLabel) },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = editor.sshPrivateKeyPassphrase,
                onValueChange = { editor.sshPrivateKeyPassphrase = it; editor.clearError() },
                label = { Text(S.sshPrivateKeyPassphraseLabel) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(8.dp))
        if (editor.sshHostKeySha256.isBlank()) {
            Text(
                S.sshTrustOnFirstUseHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                S.sshTrustedHostKeyLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    editor.sshHostKeySha256,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            TextButton(onClick = { editor.sshHostKeySha256 = "" }) {
                Text(S.sshForgetHostKey)
            }
        }
    }

    editor.error?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
