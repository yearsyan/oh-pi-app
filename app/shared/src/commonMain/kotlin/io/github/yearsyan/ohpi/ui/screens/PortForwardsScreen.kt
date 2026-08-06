package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.data.PortForward
import io.github.yearsyan.ohpi.data.ServerConnectionMode
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.net.PortForwardStatus
import io.github.yearsyan.ohpi.ui.AppViewModel
import kotlinx.coroutines.delay

/** Manages the SSH port forwards of the active server. */
@Composable
fun PortForwardsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val server = vm.activeServer
    val sshAvailable = server?.connectionMode?.usesSsh == true
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PortForward?>(null) }

    // Tunnel states change outside the UI (SSH drops, remote closes); poll
    // while the page is visible so statuses stay honest.
    LaunchedEffect(server?.id) {
        while (true) {
            vm.refreshPortForwardStates()
            delay(2000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S.back)
            }
            Text(
                S.portForwardsTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (sshAvailable) {
                IconButton(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, contentDescription = S.portForwardAdd)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            when {
                !sshAvailable -> Text(
                    S.portForwardSshOnly,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                )
                server.portForwards.isEmpty() -> Text(
                    S.portForwardEmpty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(server.portForwards, key = { it.id }) { forward ->
                        PortForwardRow(
                            forward = forward,
                            status = vm.portForwardStatuses[forward.id],
                            onToggle = { vm.setPortForwardEnabled(forward.id, it) },
                            onEdit = { editing = forward },
                            onDelete = { vm.removePortForward(forward.id) },
                            onRetry = { vm.restartPortForward(forward.id) },
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        PortForwardDialog(
            initial = null,
            onDismiss = { adding = false },
            onConfirm = { host, port ->
                vm.addPortForward(host, port)
                adding = false
            },
        )
    }
    editing?.let { forward ->
        PortForwardDialog(
            initial = forward,
            onDismiss = { editing = null },
            onConfirm = { host, port ->
                vm.updatePortForward(forward.id, host, port)
                editing = null
            },
        )
    }
}

@Composable
private fun PortForwardRow(
    forward: PortForward,
    status: PortForwardStatus?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${forward.remoteHost}:${forward.remotePort}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    statusText(forward, status),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        status?.error != null -> MaterialTheme.colorScheme.error
                        status?.running == true -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (forward.enabled && status?.error != null) {
                TextButton(onClick = onRetry) { Text(S.portForwardRetry) }
            }
            Switch(checked = forward.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = S.portForwardEdit)
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = S.delete,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun statusText(forward: PortForward, status: PortForwardStatus?): String = when {
    !forward.enabled -> S.portForwardStopped
    status == null -> S.portForwardStopped
    status.error != null -> status.error
    status.starting -> S.portForwardStarting
    status.running -> S.portForwardRunning(status.localPort)
    else -> S.portForwardStopped
}

@Composable
private fun PortForwardDialog(
    initial: PortForward?,
    onDismiss: () -> Unit,
    onConfirm: (remoteHost: String, remotePort: Int) -> Unit,
) {
    var host by remember { mutableStateOf(initial?.remoteHost ?: "127.0.0.1") }
    var port by remember { mutableStateOf(initial?.remotePort?.toString() ?: "") }
    val portValue = port.toIntOrNull()
    val valid = portValue != null && portValue in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) S.portForwardAdd else S.portForwardEdit) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(S.portForwardRemoteHost) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(S.portForwardRemotePort) },
                    isError = port.isNotEmpty() && !valid,
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(host.trim().ifBlank { "127.0.0.1" }, portValue!!)
                },
            ) { Text(S.confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S.cancel) }
        },
    )
}
