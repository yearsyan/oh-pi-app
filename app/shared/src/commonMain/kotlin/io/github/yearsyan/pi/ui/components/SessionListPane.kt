package io.github.yearsyan.pi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yearsyan.pi.data.SavedSession
import io.github.yearsyan.pi.data.ServerProfile
import io.github.yearsyan.pi.i18n.S
import io.github.yearsyan.pi.net.nowMillis
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings

@Composable
fun SessionListPane(
    sessions: List<SavedSession>,
    servers: List<ServerProfile>,
    activeServer: ServerProfile?,
    activeChatId: String?,
    wide: Boolean,
    onNewChat: () -> Unit,
    onSelectSession: (SavedSession) -> Unit,
    onSelectServer: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onRenameSession: (SavedSession) -> Unit,
    onDeleteSession: (SavedSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                S.appName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = S.settingsTitle)
            }
        }

        // server picker
        if (activeServer != null) {
            ServerChip(servers, activeServer, onSelectServer)
        }

        // new chat
        Button(
            onClick = onNewChat,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(S.newChat)
        }

        Spacer(Modifier.height(4.dp))

        if (sessions.isEmpty()) {
            EmptySessions()
        } else {
            val groups = sessions
                .groupBy { it.workDir }
                .map { (dir, list) -> WorkspaceGroup(dir, list.sortedByDescending { it.lastActive }) }
                .sortedByDescending { g -> g.sessions.maxOf { it.lastActive } }
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { group ->
                    item(key = "ws-${group.workDir}") {
                        WorkspaceHeader(group.workDir)
                    }
                    items(group.sessions, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            active = wide && session.id == activeChatId,
                            onClick = { onSelectSession(session) },
                            onRename = { onRenameSession(session) },
                            onDelete = { onDeleteSession(session) },
                        )
                    }
                }
            }
        }
    }
}

private data class WorkspaceGroup(val workDir: String, val sessions: List<SavedSession>)

@Composable
private fun WorkspaceHeader(workDir: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (workDir.isBlank()) S.defaultWorkspace
            else workDir.substringAfterLast('/').ifBlank { workDir },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (workDir.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                workDir,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ServerChip(
    servers: List<ServerProfile>,
    activeServer: ServerProfile,
    onSelectServer: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = servers.size > 1) { open = true }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Cloud,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                activeServer.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (servers.size > 1) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            servers.forEach { server ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(server.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    server.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (server.id == activeServer.id) {
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
                        onSelectServer(server.id)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptySessions() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Chat,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            S.noSessions,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            S.noSessionsHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun SessionRow(
    session: SavedSession,
    active: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val bg = if (active) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                session.name.ifBlank { S.untitledSession },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${session.id.take(8)} · ${relativeTime(session.lastActive)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(S.rename) },
                    leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)) },
                    onClick = { onRename(); menuOpen = false },
                )
                DropdownMenuItem(
                    text = { Text(S.delete) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)) },
                    onClick = { onDelete(); menuOpen = false },
                )
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 0.5.dp,
    )
}

@Composable
fun relativeTime(ts: Long): String {
    if (ts <= 0) return ""
    val diff = nowMillis() - ts
    val m = diff / 60_000
    return when {
        m < 1 -> S.justNow
        m < 60 -> S.minutesAgo(m.toInt())
        (m / 60) < 24 -> S.hoursAgo((m / 60).toInt())
        else -> S.daysAgo((m / 60 / 24).toInt())
    }
}
