package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.data.ServerConnectionMode
import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.SshAuthentication
import io.github.yearsyan.ohpi.data.SshPrivateKey
import io.github.yearsyan.ohpi.data.SshServerProfile
import io.github.yearsyan.ohpi.getPlatform
import io.github.yearsyan.ohpi.secureRandomHex
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.i18n.Strings
import io.github.yearsyan.ohpi.net.buildGatewayUrl
import io.github.yearsyan.ohpi.net.parseGatewayAddress
import io.github.yearsyan.ohpi.ui.components.AppDropdownMenu
import io.github.yearsyan.ohpi.ui.components.AppMenuItem
import kotlin.random.Random

/** Default ohpi gateway port used for new server profiles on all platforms. */
internal const val DefaultGatewayPort = 18080

/** Fixed SSH-mode backend address on iOS: ohpi as seen by the SSH server itself. */
private const val FixedLoopbackHost = "127.0.0.1"
private const val ImportSshKeyMenuId = "__import_ssh_key__"

/** The outcome of a successfully validated server editor: a profile plus any new managed key. */
internal data class ServerEditorResult(
    val profile: ServerProfile,
    val newKey: SshPrivateKey? = null,
)

/**
 * Selection state for private-key authentication: either one of the centrally
 * managed keys or a freshly pasted key that should be imported on save.
 */
internal class SshKeySelectionState(initialKeyId: String = "") {
    var selectedKeyId by mutableStateOf(initialKeyId)
    var creatingNew by mutableStateOf(false)
    var newKeyName by mutableStateOf("")
    var newKeyContents by mutableStateOf("")
    var newKeyPassphrase by mutableStateOf("")

    /** With no managed keys at all, importing a new key is the only option. */
    fun effectiveCreatingNew(keys: List<SshPrivateKey>): Boolean =
        creatingNew || keys.isEmpty()

    fun validate(strings: Strings, keys: List<SshPrivateKey>): String? =
        when {
            effectiveCreatingNew(keys) && newKeyContents.isBlank() ->
                strings.sshPrivateKeyRequired
            !effectiveCreatingNew(keys) && keys.none { it.id == selectedKeyId } ->
                strings.sshKeyRequired
            else -> null
        }

    /** Builds the key to import, or null when an existing key is selected. */
    fun buildNewKey(strings: Strings, keys: List<SshPrivateKey>): SshPrivateKey? {
        if (!effectiveCreatingNew(keys)) return null
        return SshPrivateKey(
            id = "key-" + Random.nextLong().toString(16),
            name = newKeyName.trim().ifBlank { strings.sshKeyDefaultName },
            privateKey = newKeyContents.trim(),
            passphrase = newKeyPassphrase,
        )
    }
}

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
    val keySelection = SshKeySelectionState(initial?.ssh?.privateKeyId.orEmpty())
    var sshHostKeySha256 by mutableStateOf(initial?.ssh?.hostKeySha256.orEmpty())
    var error by mutableStateOf<String?>(null)

    fun clearError() {
        error = null
    }

    fun build(strings: Strings, keys: List<SshPrivateKey> = emptyList()): ServerEditorResult? {
        val parsedGatewayPort = gatewayPort.toIntOrNull()
        val parsedSshPort = sshPort.toIntOrNull()
        // On iOS the SSH backend address is fixed to the loopback address: the
        // tunnel reaches ohpi exactly where the SSH server sees it.
        val managed = connectionMode.isManaged
        val fixedLoopback = managed || (getPlatform().isIos && connectionMode.usesSsh)
        val effectiveGatewayHost = if (fixedLoopback) FixedLoopbackHost else gatewayHost.trim()
        val effectiveGatewayTls = if (fixedLoopback) false else gatewayTls
        val effectiveGatewayPort = if (managed) DefaultGatewayPort else parsedGatewayPort
        val usesPrivateKey =
            connectionMode.usesSsh && sshAuthentication == SshAuthentication.PrivateKey
        error =
            when {
                effectiveGatewayHost.isBlank() -> strings.serverHostRequired
                effectiveGatewayPort !in 1..65535 -> strings.serverPortInvalid
                !fixedLoopback && connectionMode.usesSsh && effectiveGatewayTls ->
                    strings.sshGatewayTlsInvalid
                connectionMode.usesSsh && sshHost.isBlank() -> strings.sshHostRequired
                connectionMode.usesSsh && parsedSshPort !in 1..65535 -> strings.sshPortInvalid
                connectionMode.usesSsh && sshUsername.isBlank() ->
                    strings.sshUsernameRequired
                connectionMode.usesSsh &&
                    sshAuthentication == SshAuthentication.Password &&
                    sshPassword.isEmpty() -> strings.sshPasswordRequired
                usesPrivateKey -> keySelection.validate(strings, keys)
                else -> null
            }
        if (error != null) return null

        val newKey = if (usesPrivateKey) keySelection.buildNewKey(strings, keys) else null
        val ssh =
            if (connectionMode.usesSsh) {
                SshServerProfile(
                    host = sshHost.trim(),
                    port = parsedSshPort ?: 22,
                    username = sshUsername.trim(),
                    authentication = sshAuthentication,
                    password = sshPassword.takeIf { sshAuthentication == SshAuthentication.Password }.orEmpty(),
                    privateKeyId =
                        if (usesPrivateKey) (newKey?.id ?: keySelection.selectedKeyId) else "",
                    hostKeySha256 = sshHostKeySha256.trim(),
                )
            } else {
                SshServerProfile()
            }
        val profile =
            ServerProfile(
                id = id,
                name = name.trim().ifBlank { if (connectionMode.usesSsh) sshHost.trim() else "" },
                url = buildGatewayUrl(effectiveGatewayHost, effectiveGatewayPort ?: DefaultGatewayPort, effectiveGatewayTls),
                token = if (managed) token.ifBlank { secureRandomHex(32) } else token.trim(),
                connectionMode = connectionMode,
                ssh = ssh,
            )
        return ServerEditorResult(profile, newKey)
    }
}

@Composable
internal fun rememberServerEditor(initial: ServerProfile?): ServerEditorState =
    remember(initial) { ServerEditorState(initial) }

@Composable
internal fun ServerEditorFields(
    editor: ServerEditorState,
    keys: List<SshPrivateKey> = emptyList(),
    modes: List<ServerConnectionMode> = ServerConnectionMode.entries,
) {
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
            modes.map { mode ->
                mode to
                    when (mode) {
                        ServerConnectionMode.Direct -> S.directConnection
                        ServerConnectionMode.Ssh -> S.sshConnection
                        ServerConnectionMode.ManagedSsh -> S.managedSshConnection
                    }
            }
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = editor.connectionMode == mode,
                onClick = {
                    editor.connectionMode = mode
                    if (mode.usesSsh) editor.gatewayTls = false
                    editor.clearError()
                },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.weight(1f),
            ) {
                // Keep every segment two lines tall so a wrapping label does not
                // make its segment taller than its siblings.
                Text(label, minLines = 2, maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    val managed = editor.connectionMode.isManaged
    // On iOS an ordinary SSH tunnel has a fixed remote loopback host. Managed
    // SSH also owns the gateway address and token, so neither is user-editable.
    val fixedLoopback = getPlatform().isIos && editor.connectionMode == ServerConnectionMode.Ssh
    if (!managed) {
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
    } else {
        Text(
            S.managedSshHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (editor.connectionMode.usesSsh) {
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

        SshAuthenticationFields(
            authentication = editor.sshAuthentication,
            onAuthenticationChange = { editor.sshAuthentication = it; editor.clearError() },
            password = editor.sshPassword,
            onPasswordChange = { editor.sshPassword = it; editor.clearError() },
            keySelection = editor.keySelection,
            keys = keys,
            onFieldEdited = editor::clearError,
        )

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

/**
 * Password-or-key authentication controls shared by the server editor and the
 * first-run onboarding wizard.
 */
@Composable
internal fun SshAuthenticationFields(
    authentication: SshAuthentication,
    onAuthenticationChange: (SshAuthentication) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    keySelection: SshKeySelectionState,
    keys: List<SshPrivateKey>,
    onFieldEdited: () -> Unit = {},
) {
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
        options.forEachIndexed { index, (option, label) ->
            SegmentedButton(
                selected = authentication == option,
                onClick = { onAuthenticationChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.weight(1f),
            ) { Text(label, maxLines = 1, textAlign = TextAlign.Center) }
        }
    }
    Spacer(Modifier.height(10.dp))

    if (authentication == SshAuthentication.Password) {
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(S.sshPasswordLabel) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        SshKeyPickerFields(keySelection = keySelection, keys = keys, onFieldEdited = onFieldEdited)
    }
}

/**
 * Managed-key picker: choose one of the app's stored keys or import a new one.
 * Key material lives in the key store so one key can serve many machines.
 */
@Composable
internal fun SshKeyPickerFields(
    keySelection: SshKeySelectionState,
    keys: List<SshPrivateKey>,
    onFieldEdited: () -> Unit = {},
) {
    val creatingNew = keySelection.effectiveCreatingNew(keys)
    if (keys.isNotEmpty()) {
        val selectedName =
            if (creatingNew) S.sshKeyCreateNew
            else keys.firstOrNull { it.id == keySelection.selectedKeyId }?.name
                ?: S.sshKeySelectPlaceholder
        AppDropdownMenu(
            items =
                buildList {
                    keys.forEach { key ->
                        add(AppMenuItem(id = key.id, title = key.name))
                    }
                    add(
                        AppMenuItem(
                            id = ImportSshKeyMenuId,
                            title = S.sshKeyCreateNew,
                            startsSection = true,
                        ),
                    )
                },
            onItemClick = { id ->
                if (id == ImportSshKeyMenuId) {
                    keySelection.creatingNew = true
                    keySelection.selectedKeyId = ""
                } else if (keys.any { it.id == id }) {
                    keySelection.selectedKeyId = id
                    keySelection.creatingNew = false
                }
                onFieldEdited()
            },
            modifier = Modifier.fillMaxWidth(),
            accessibilityLabel = selectedName,
        ) { openMenu ->
            OutlinedButton(
                onClick = openMenu,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.Key,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    selectedName,
                    modifier = Modifier.weight(1f),
                    color =
                        if (creatingNew || keys.any { it.id == keySelection.selectedKeyId }) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    if (creatingNew) {
        OutlinedTextField(
            value = keySelection.newKeyName,
            onValueChange = { keySelection.newKeyName = it; onFieldEdited() },
            label = { Text(S.sshKeyNameLabel) },
            placeholder = { Text(S.sshKeyNamePlaceholder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = keySelection.newKeyContents,
            onValueChange = { keySelection.newKeyContents = it; onFieldEdited() },
            label = { Text(S.sshPrivateKeyLabel) },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = keySelection.newKeyPassphrase,
            onValueChange = { keySelection.newKeyPassphrase = it; onFieldEdited() },
            label = { Text(S.sshPrivateKeyPassphraseLabel) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
