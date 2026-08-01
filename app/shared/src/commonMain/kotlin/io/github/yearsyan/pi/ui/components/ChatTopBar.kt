package io.github.yearsyan.pi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yearsyan.pi.chat.ChatController
import io.github.yearsyan.pi.chat.ModelInfo
import io.github.yearsyan.pi.data.ConnState
import io.github.yearsyan.pi.i18n.S
import io.github.yearsyan.pi.theme.piExtras
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh

/** Chat top bar: back (compact), title, model & thinking selectors, status, overflow. */
@Composable
fun ChatTopBar(
    controller: ChatController,
    showBack: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S.back)
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    controller.sessionName.ifBlank { S.untitledSession },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(2.dp))
                ConnectionBadge(controller.conn)
            }
            if (controller.isStreaming) {
                Text(
                    S.agentWorking,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            OverflowMenu(onRename = onRename, onDelete = onDelete, onReconnect = { controller.reconnect() })
        }
        if (controller.models.isNotEmpty() || controller.thinkingLevels.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (controller.models.isNotEmpty()) {
                    ModelSelector(controller)
                }
                if (controller.thinkingLevels.isNotEmpty()) {
                    ThinkingSelector(controller)
                }
            }
            Spacer(Modifier.size(8.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ConnectionBadge(conn: ConnState) {
    val (color, label) = when (conn) {
        ConnState.Ready -> piExtras.success to S.connected
        ConnState.Connecting -> piExtras.warning to S.connecting
        ConnState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant to S.disconnected
        ConnState.Error -> MaterialTheme.colorScheme.error to S.connectionError
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun SelectorChip(
    icon: ImageVector?,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelSelector(controller: ChatController) {
    var open by remember { mutableStateOf(false) }
    Box {
        SelectorChip(
            icon = null,
            label = controller.currentModel?.label ?: controller.model.ifBlank { S.noModel },
            enabled = controller.conn == ConnState.Ready,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                S.selectModel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            controller.models.forEach { m: ModelInfo ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.label, style = MaterialTheme.typography.bodyMedium)
                                if (m.provider.isNotBlank()) {
                                    Text(
                                        m.provider,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (m.id == controller.currentModel?.id) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    onClick = {
                        controller.setModel(m)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ThinkingSelector(controller: ChatController) {
    var open by remember { mutableStateOf(false) }
    Box {
        SelectorChip(
            icon = null,
            label = "${S.thinkingLevel}: ${controller.thinkingLevel.ifBlank { "—" }}",
            enabled = controller.conn == ConnState.Ready,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                S.selectThinkingLevel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            controller.thinkingLevels.forEach { level ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(level, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (level == controller.thinkingLevel) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    onClick = {
                        controller.selectThinkingLevel(level)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun OverflowMenu(onRename: () -> Unit, onDelete: () -> Unit, onReconnect: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(S.reconnect) },
                leadingIcon = { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)) },
                onClick = { onReconnect(); open = false },
            )
            DropdownMenuItem(
                text = { Text(S.rename) },
                leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)) },
                onClick = { onRename(); open = false },
            )
            DropdownMenuItem(
                text = { Text(S.delete) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)) },
                onClick = { onDelete(); open = false },
            )
        }
    }
}
