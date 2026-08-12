package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.data.LocalSshKeyCandidate
import io.github.yearsyan.ohpi.data.SshPrivateKey
import io.github.yearsyan.ohpi.data.discoverLocalSshKeys
import io.github.yearsyan.ohpi.i18n.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Desktop-only panel that discovers ~/.ssh keys as soon as the SSH settings page opens. */
@Composable
internal fun DesktopLocalSshKeyPanel(
    savedKeys: List<SshPrivateKey>,
    onImport: (LocalSshKeyCandidate) -> Unit,
) {
    var scanGeneration by remember { mutableIntStateOf(0) }
    var candidates by remember { mutableStateOf<List<LocalSshKeyCandidate>?>(null) }
    var failureMessage by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(scanGeneration) {
        scanning = true
        failureMessage = null
        try {
            candidates = withContext(Dispatchers.Default) { discoverLocalSshKeys() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failureMessage = failure.message.orEmpty().ifBlank { failure.toString() }
        } finally {
            scanning = false
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        Icons.Filled.Computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(9.dp).size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        S.sshLocalKeysTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        localKeyScanStatus(candidates, failureMessage, scanning),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (failureMessage == null) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                    )
                }
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { scanGeneration += 1 }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = S.sshLocalKeysRefresh,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                S.sshLocalKeysDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            candidates?.takeIf { it.isNotEmpty() }?.let { discovered ->
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                discovered.forEachIndexed { index, candidate ->
                    val saved = savedKeys.firstOrNull { it.hasSameMaterialAs(candidate) }
                    LocalSshKeyCandidateRow(
                        candidate = candidate,
                        savedKey = saved,
                        actionLabel = if (saved == null) S.sshLocalKeyImport else S.sshLocalKeyImported,
                        actionIcon = if (saved == null) Icons.Filled.Download else Icons.Filled.Check,
                        onClick = if (saved == null) ({ onImport(candidate) }) else null,
                    )
                    if (index < discovered.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(start = 52.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Picker used inside SSH server setup to fill key fields without copy/paste. */
@Composable
internal fun LocalSshKeyPickerDialog(
    savedKeys: List<SshPrivateKey>,
    onDismiss: () -> Unit,
    onSelect: (candidate: LocalSshKeyCandidate, savedKey: SshPrivateKey?) -> Unit,
) {
    var scanGeneration by remember { mutableIntStateOf(0) }
    var candidates by remember { mutableStateOf<List<LocalSshKeyCandidate>?>(null) }
    var failureMessage by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(scanGeneration) {
        scanning = true
        failureMessage = null
        try {
            candidates = withContext(Dispatchers.Default) { discoverLocalSshKeys() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failureMessage = failure.message.orEmpty().ifBlank { failure.toString() }
        } finally {
            scanning = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.sshLocalKeyChooseTitle) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    S.sshLocalKeysDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                when {
                    candidates == null && scanning -> {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(S.sshLocalKeysScanning)
                        }
                    }

                    failureMessage != null -> Text(
                        S.sshLocalKeysReadFailed(failureMessage.orEmpty()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )

                    candidates.isNullOrEmpty() -> Text(
                        S.sshLocalKeysNone,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 18.dp),
                    )

                    else -> candidates.orEmpty().forEachIndexed { index, candidate ->
                        val saved = savedKeys.firstOrNull { it.hasSameMaterialAs(candidate) }
                        LocalSshKeyCandidateRow(
                            candidate = candidate,
                            savedKey = saved,
                            actionLabel =
                                if (saved == null) S.sshLocalKeyChoose else S.sshLocalKeyUseSaved,
                            actionIcon = if (saved == null) Icons.Filled.Download else Icons.Filled.Check,
                            onClick = {
                                onSelect(candidate, saved)
                                onDismiss()
                            },
                        )
                        if (index < candidates.orEmpty().lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { scanGeneration += 1 }, enabled = !scanning) {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(S.sshLocalKeysRefresh)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.cancel) } },
    )
}

/** Confirmation dialog that imports a discovered key without exposing its raw contents. */
@Composable
internal fun LocalSshKeyImportDialog(
    candidate: LocalSshKeyCandidate,
    onDismiss: () -> Unit,
    onSave: (SshPrivateKey) -> Unit,
) {
    val strings = S
    var name by remember(candidate.sourcePath) { mutableStateOf(candidate.fileName) }
    var passphrase by remember(candidate.sourcePath) { mutableStateOf("") }
    var error by remember(candidate.sourcePath) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.sshLocalKeyImportTitle) },
        text = {
            Column {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Icon(
                            Icons.Filled.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                candidate.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                localKeyMetadata(candidate),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (candidate.encrypted) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = S.sshLocalKeyEncrypted,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    S.sshLocalKeyImportDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text(S.sshKeyNameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it; error = null },
                    label = { Text(S.sshPrivateKeyPassphraseLabel) },
                    supportingText = {
                        if (candidate.encrypted) Text(S.sshLocalKeyEncryptedPassphraseHint)
                    },
                    singleLine = true,
                    isError = error != null,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (candidate.encrypted && passphrase.isEmpty()) {
                        error = strings.sshLocalKeyPassphraseRequired
                        return@Button
                    }
                    onSave(
                        SshPrivateKey(
                            id = "key-" + Random.nextLong().toString(16),
                            name = name.trim().ifBlank { candidate.fileName },
                            privateKey = candidate.privateKey,
                            passphrase = passphrase,
                            publicKey = candidate.publicKey,
                        ),
                    )
                },
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(S.sshLocalKeyImport)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.cancel) } },
    )
}

@Composable
private fun LocalSshKeyCandidateRow(
    candidate: LocalSshKeyCandidate,
    savedKey: SshPrivateKey?,
    actionLabel: String,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Icon(
            if (candidate.encrypted) Icons.Filled.Lock else Icons.Filled.Key,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                savedKey?.name ?: candidate.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                localKeyMetadata(candidate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { onClick?.invoke() }, enabled = onClick != null) {
            Icon(actionIcon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(actionLabel)
        }
    }
}

@Composable
private fun localKeyScanStatus(
    candidates: List<LocalSshKeyCandidate>?,
    failureMessage: String?,
    scanning: Boolean,
): String =
    when {
        candidates == null && scanning -> S.sshLocalKeysScanning
        failureMessage != null -> S.sshLocalKeysReadFailed(failureMessage)
        candidates.isNullOrEmpty() -> S.sshLocalKeysNone
        else -> S.sshLocalKeysFound(candidates.size)
    }

@Composable
private fun localKeyMetadata(candidate: LocalSshKeyCandidate): String =
    buildList {
        add(candidate.keyType)
        if (candidate.encrypted) add(S.sshLocalKeyEncrypted)
        add("~/.ssh/${candidate.fileName}")
    }.joinToString(" · ")

private fun SshPrivateKey.hasSameMaterialAs(candidate: LocalSshKeyCandidate): Boolean =
    normalizePrivateKey(privateKey) == normalizePrivateKey(candidate.privateKey)

private fun normalizePrivateKey(value: String): String =
    value.trim().replace("\r\n", "\n").replace('\r', '\n')
