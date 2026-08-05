package io.github.yearsyan.pi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yearsyan.pi.appVersion
import io.github.yearsyan.pi.data.AppLanguage
import io.github.yearsyan.pi.data.ServerConnectionMode
import io.github.yearsyan.pi.data.ServerProfile
import io.github.yearsyan.pi.data.ThemeMode
import io.github.yearsyan.pi.i18n.S
import io.github.yearsyan.pi.net.gatewayAddressLabel
import io.github.yearsyan.pi.ui.components.ConfirmDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Circle

@Composable
fun SettingsScreen(
    servers: List<ServerProfile>,
    activeServerId: String,
    themeMode: ThemeMode,
    language: AppLanguage,
    onBack: (() -> Unit)?,
    onSelectServer: (String) -> Unit,
    onSaveServer: (ServerProfile) -> Unit,
    onDeleteServer: (String) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
) {
    var editing by remember { mutableStateOf<ServerProfile?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ServerProfile?>(null) }

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
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S.back)
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                S.settingsTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            // ---- servers ----
            SectionHeader(S.serversSection)
            servers.forEach { server ->
                ServerRow(
                    server = server,
                    active = server.id == activeServerId,
                    onSelect = { onSelectServer(server.id) },
                    onEdit = { editing = server },
                    onDelete = { deleting = server },
                )
                Spacer(Modifier.height(8.dp))
            }
            TextButton(onClick = { adding = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(S.addServer)
            }

            Spacer(Modifier.height(24.dp))

            // ---- appearance ----
            SectionHeader(S.appearanceSection)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val items = listOf(
                    ThemeMode.System to S.themeSystem,
                    ThemeMode.Light to S.themeLight,
                    ThemeMode.Dark to S.themeDark,
                )
                items.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = items.size),
                    ) { Text(label) }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ---- language ----
            SectionHeader(S.languageSection)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val items = listOf(
                    AppLanguage.System to S.languageSystem,
                    AppLanguage.English to "English",
                    AppLanguage.Chinese to "中文",
                )
                items.forEachIndexed { i, (lang, label) ->
                    SegmentedButton(
                        selected = language == lang,
                        onClick = { onLanguage(lang) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = items.size),
                    ) { Text(label) }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ---- about ----
            SectionHeader(S.aboutSection)
            Text(
                "${S.appName} · pi2ws client",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "v${appVersion()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(48.dp))
        }
    }

    if (adding) {
        ServerEditDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = onSaveServer,
        )
    }
    editing?.let { server ->
        ServerEditDialog(
            initial = server,
            onDismiss = { editing = null },
            onSave = onSaveServer,
        )
    }
    deleting?.let { server ->
        ConfirmDialog(
            title = S.deleteServerTitle,
            body = S.deleteServerBody,
            onDismiss = { deleting = null },
            onConfirm = { onDeleteServer(server.id) },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun ServerRow(
    server: ServerProfile,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val gatewayLabel = gatewayAddressLabel(server.url)
    Surface(
        color = if (active) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onSelect)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(
                if (active) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    server.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (server.connectionMode == ServerConnectionMode.Ssh) {
                        "SSH · ${server.ssh.username}@${server.ssh.host}:${server.ssh.port} → $gatewayLabel"
                    } else {
                        gatewayLabel
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (active) {
                Text(
                    S.activeServerHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = S.editServer,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = S.delete,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
fun ServerEditDialog(
    initial: ServerProfile?,
    onDismiss: () -> Unit,
    onSave: (ServerProfile) -> Unit,
) {
    val editor = rememberServerEditor(initial)
    val strings = S

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) S.addServer else S.editServer) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ServerEditorFields(editor)
            }
        },
        confirmButton = {
            Button(onClick = {
                editor.build(strings)?.let { profile ->
                    onSave(profile)
                    onDismiss()
                }
            }) { Text(S.confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S.cancel) }
        },
    )
}
