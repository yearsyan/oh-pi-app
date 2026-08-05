package io.github.yearsyan.ohpi.ui.screens

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.data.ServerConnectionMode
import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.SshAuthentication
import io.github.yearsyan.ohpi.data.SshServerProfile
import io.github.yearsyan.ohpi.getPlatform
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.i18n.Strings
import io.github.yearsyan.ohpi.net.buildGatewayUrl
import io.github.yearsyan.ohpi.net.parseGatewayAddress
import kotlin.random.Random

/** Default ohpi gateway port used for new server profiles on all platforms. */
internal const val DefaultGatewayPort = 18080

/** Fixed SSH-mode backend address on iOS: ohpi as seen by the SSH server itself. */
private const val FixedLoopbackHost = "127.0.0.1"

internal class ServerEditorState(initial: ServerProfile?) {
    private val id = initial?.id ?: Random.nextLong().toString(16)
    private val initialGateway = initial?.url?.let(::parseGatewayAddress)

    var name by mutableStateOf(initial?.name.orEmpty())
    var gatewayHost by mutableStateOf(initialGateway?.host.orEmpty())
    var gatewayPort by mutableStateOf((initialGateway?.port ?: DefaultGatewayPort).toString())
    var gatewayTls by mutableStateOf(initialGateway?.tls ?: false)
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
        val parsedGatewayPort = gatewayPort.toIntOrNull()
        val parsedSshPort = sshPort.toIntOrNull()
        // On iOS the SSH backend address is fixed to the loopback address: the
        // tunnel reaches ohpi exactly where the SSH server sees it.
        val fixedLoopback = getPlatform().isIos && connectionMode == ServerConnectionMode.Ssh
        val effectiveGatewayHost = if (fixedLoopback) FixedLoopbackHost else gatewayHost.trim()
        val effectiveGatewayTls = if (fixedLoopback) false else gatewayTls
        error =
            when {
                effectiveGatewayHost.isBlank() -> strings.serverHostRequired
                parsedGatewayPort !in 1..65535 -> strings.serverPortInvalid
                !fixedLoopback && connectionMode == ServerConnectionMode.Ssh && effectiveGatewayTls ->
                    strings.sshGatewayTlsInvalid
                connectionMode == ServerConnectionMode.Ssh && sshHost.isBlank() -> strings.sshHostRequired
                connectionMode == ServerConnectionMode.Ssh && parsedSshPort !in 1..65535 -> strings.sshPortInvalid
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
                    port = parsedSshPort ?: 22,
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
            url = buildGatewayUrl(effectiveGatewayHost, parsedGatewayPort ?: DefaultGatewayPort, effectiveGatewayTls),
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
                onClick = {
                    editor.connectionMode = mode
                    if (mode == ServerConnectionMode.Ssh) editor.gatewayTls = false
                    editor.clearError()
                },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label) }
        }
    }
    Spacer(Modifier.height(10.dp))

    // On iOS the SSH backend address is fixed to 127.0.0.1, so the host field
    // and the TLS switch are hidden; only the backend port stays editable.
    val fixedLoopback = getPlatform().isIos && editor.connectionMode == ServerConnectionMode.Ssh
    Row(Modifier.fillMaxWidth()) {
        if (!fixedLoopback) {
            OutlinedTextField(
                value = editor.gatewayHost,
                onValueChange = { editor.gatewayHost = it; editor.clearError() },
                label = { Text(S.serverHostLabel) },
                placeholder = { Text(S.serverHostPlaceholder) },
                singleLine = true,
                isError = editor.error == S.serverHostRequired,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
        }
        OutlinedTextField(
            value = editor.gatewayPort,
            onValueChange = { editor.gatewayPort = it.filter(Char::isDigit); editor.clearError() },
            label = { Text(if (fixedLoopback) S.backendPortLabel else S.serverPortLabel) },
            singleLine = true,
            isError = editor.error == S.serverPortInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(if (fixedLoopback) 1f else 0.38f),
        )
    }
    if (!fixedLoopback) {
        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                S.serverTlsLabel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = editor.gatewayTls,
                onCheckedChange = { editor.gatewayTls = it; editor.clearError() },
                enabled = editor.connectionMode == ServerConnectionMode.Direct,
            )
        }
    }
    if (editor.connectionMode == ServerConnectionMode.Ssh) {
        Text(
            if (fixedLoopback) S.sshGatewayIosFixedHostHint else S.sshGatewayPlaintextHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
