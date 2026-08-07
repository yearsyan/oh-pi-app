package io.github.yearsyan.ohpi.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yearsyan.ohpi.data.SavedSession
import io.github.yearsyan.ohpi.data.ServerConnectionMode
import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.WorkspaceSummary
import io.github.yearsyan.ohpi.getPlatform
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.net.gatewayAddressLabel
import io.github.yearsyan.ohpi.net.nowMillis
import io.github.yearsyan.ohpi.theme.piExtras
import io.github.yearsyan.ohpi.ui.GatewayHostOs
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz

@Composable
fun SessionListPane(
    workspaces: List<WorkspaceSummary>,
    servers: List<ServerProfile>,
    activeServer: ServerProfile?,
    activeChatId: String?,
    wide: Boolean,
    sessionsLoading: Boolean,
    onRefresh: () -> Unit,
    onNewChat: () -> Unit,
    onSelectSession: (SavedSession) -> Unit,
    onSelectServer: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onRenameSession: (SavedSession) -> Unit,
    onDeleteSession: (SavedSession) -> Unit,
    onStopSessionProcess: (SavedSession) -> Unit,
    onLoadMoreSessions: (WorkspaceSummary) -> Unit,
    onEditWorkspace: (WorkspaceSummary) -> Unit,
    sessionProcessStopSupported: Boolean,
    hostOs: GatewayHostOs,
    onBrowseFiles: () -> Unit,
    onOpenPortForwards: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteCandidate by remember { mutableStateOf<SavedSession?>(null) }
    var stopCandidate by remember { mutableStateOf<SavedSession?>(null) }
    var collapsedWorkspaces by rememberSaveable { mutableStateOf(setOf<String>()) }
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

        // server picker + quick actions
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (activeServer != null) {
                ServerChip(servers, activeServer, hostOs, onSelectServer, Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(10.dp))
            // file browser entry
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = onBrowseFiles),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = S.browseFiles,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (activeServer?.connectionMode?.usesSsh == true) {
                Spacer(Modifier.width(10.dp))
                // port forwarding entry (SSH servers only)
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = onOpenPortForwards),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = S.portForwardsTitle,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            // new chat
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onNewChat),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = S.newChat,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        PlatformPullToRefreshBox(
            isRefreshing = sessionsLoading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            if (workspaces.isEmpty()) {
                if (sessionsLoading) SessionsLoading() else EmptySessions()
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    workspaces.forEach { workspace ->
                        val collapsed = workspace.id in collapsedWorkspaces
                        item(key = "ws-${workspace.id}") {
                            WorkspaceHeader(
                                workspace = workspace,
                                collapsed = collapsed,
                                onToggle = {
                                    collapsedWorkspaces =
                                        if (collapsed) collapsedWorkspaces - workspace.id
                                        else collapsedWorkspaces + workspace.id
                                },
                                onEdit = { onEditWorkspace(workspace) },
                            )
                        }
                        if (!collapsed) {
                            items(workspace.sessions, key = { it.id }) { session ->
                                SessionRow(
                                    session = session,
                                    active = wide && session.id == activeChatId,
                                    onClick = { onSelectSession(session) },
                                    onRename = { onRenameSession(session) },
                                    onDelete = { deleteCandidate = session },
                                    onStopProcess = { stopCandidate = session },
                                    processStopSupported = sessionProcessStopSupported,
                                )
                            }
                            if (workspace.nextCursor.isNotBlank()) {
                                item(key = "more-${workspace.id}") {
                                    WorkspaceListToggle(
                                        text = S.showMoreSessions(
                                            (workspace.sessionCount - workspace.sessions.size)
                                                .coerceAtLeast(1),
                                        ),
                                        loading = workspace.sessionsLoading,
                                        onClick = { onLoadMoreSessions(workspace) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    deleteCandidate?.let { session ->
        ConfirmDialog(
            title = S.deleteSessionTitle,
            body = S.deleteSessionBody,
            confirmLabel = S.delete,
            onDismiss = { deleteCandidate = null },
            onConfirm = { onDeleteSession(session) },
        )
    }
    stopCandidate?.let { session ->
        ConfirmDialog(
            title = S.stopPiProcessTitle,
            body = S.stopPiProcessBody,
            confirmLabel = S.stopPiProcess,
            onDismiss = { stopCandidate = null },
            onConfirm = { onStopSessionProcess(session) },
        )
    }
}

@Composable
private fun WorkspaceHeader(
    workspace: WorkspaceSummary,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onToggle,
                onLongClick = {
                    longPressHaptic()
                    onEdit()
                },
            )
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TechnologyIcon(workspace.technology)
        Spacer(Modifier.width(6.dp))
        Text(
            workspace.displayName,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (workspace.directory.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                workspace.directory,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            "${workspace.sessionCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (collapsed) S.expandWorkspace else S.collapseWorkspace,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorkspaceListToggle(
    text: String,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        } else {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 0.5.dp,
    )
}

internal data class TechnologyVisual(
    val label: String,
    val color: Color,
    val icon: ImageVector? = null,
)

internal fun technologyVisual(technology: String): TechnologyVisual =
    when (technology.lowercase()) {
        "nextjs" -> TechnologyVisual("N", Color(0xFF111111), TechBrandNextJs)
        "nuxt" -> TechnologyVisual("N", Color(0xFF00A86B), TechBrandNuxt)
        "svelte" -> TechnologyVisual("S", Color(0xFFFF3E00), TechBrandSvelte)
        "angular" -> TechnologyVisual("A", Color(0xFFDD0031), TechBrandAngular)
        "vite" -> TechnologyVisual("V", Color(0xFF646CFF), TechBrandVite)
        "vue" -> TechnologyVisual("V", Color(0xFF42B883), TechBrandVue)
        "react" -> TechnologyVisual("⚛", Color(0xFF087EA4), TechBrandReact)
        "flutter" -> TechnologyVisual("F", Color(0xFF02569B), TechBrandFlutter)
        "rust" -> TechnologyVisual("Rs", Color(0xFFB7410E), TechBrandRust)
        "go" -> TechnologyVisual("Go", Color(0xFF00ADD8), TechBrandGo)
        "kotlin" -> TechnologyVisual("Kt", Color(0xFF7F52FF), TechBrandKotlin)
        "swift" -> TechnologyVisual("Sw", Color(0xFFF05138), TechBrandSwift)
        "dotnet" -> TechnologyVisual(".N", Color(0xFF512BD4), TechBrandDotNet)
        "python" -> TechnologyVisual("Py", Color(0xFF3776AB), TechBrandPython)
        "typescript" -> TechnologyVisual("TS", Color(0xFF3178C6), TechBrandTypescript)
        "javascript" -> TechnologyVisual("JS", Color(0xFFB59F00), TechBrandJavascript)
        "php" -> TechnologyVisual("PHP", Color(0xFF777BB4), TechBrandPhp)
        "ruby" -> TechnologyVisual("Rb", Color(0xFFCC342D), TechBrandRuby)
        "elixir" -> TechnologyVisual("Ex", Color(0xFF6E4A7E), TechBrandElixir)
        "dart" -> TechnologyVisual("Dt", Color(0xFF0175C2), TechBrandDart)
        "cpp" -> TechnologyVisual("C++", Color(0xFF00599C), TechBrandCpp)
        "java" -> TechnologyVisual("Jv", Color(0xFFB07219), TechBrandOpenJdk)
        else -> TechnologyVisual("<>", Color(0xFF607D8B))
    }

@Composable
private fun TechnologyIcon(technology: String) {
    val visual = technologyVisual(technology)
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(visual.color),
        contentAlignment = Alignment.Center,
    ) {
        if (visual.icon != null) {
            Icon(
                visual.icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = Color.White,
            )
        } else {
            val fontSize = if (visual.label.length > 2) 6.sp else 8.sp
            Text(
                visual.label,
                color = Color.White,
                fontSize = fontSize,
                lineHeight = fontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ServerChip(
    servers: List<ServerProfile>,
    activeServer: ServerProfile,
    hostOs: GatewayHostOs,
    onSelectServer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(enabled = servers.size > 1) { open = true }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (hostOs) {
                    GatewayHostOs.MacOS -> OsBrandApple
                    GatewayHostOs.Windows -> OsBrandWindows
                    GatewayHostOs.Linux -> OsBrandLinux
                    GatewayHostOs.Unknown -> Icons.Filled.Cloud
                },
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
                                    gatewayAddressLabel(server.url),
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
private fun SessionsLoading() {
    val transition = rememberInfiniteTransition(label = "sessionsLoading")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sessionsLoadingPulse",
    )
    val barColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.fillMaxSize()) {
        repeat(6) { index ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth(if (index % 2 == 0) 0.52f else 0.4f)
                        .height(13.dp)
                        .alpha(pulse)
                        .background(barColor, RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.height(7.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.3f)
                        .height(9.dp)
                        .alpha(pulse * 0.75f)
                        .background(barColor, RoundedCornerShape(4.dp)),
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
            )
        }
    }
}

@Composable
private fun EmptySessions() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        contentPadding = PaddingValues(32.dp),
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionRow(
    session: SavedSession,
    active: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onStopProcess: () -> Unit,
    processStopSupported: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var actionSheetOpen by remember { mutableStateOf(false) }
    val ios = getPlatform().isIos
    val bg = if (active) MaterialTheme.colorScheme.surfaceContainerHigh
    else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (ios) {
                    {
                        longPressHaptic()
                        actionSheetOpen = true
                    }
                } else null,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (session.running) {
                    SessionStatus(outputting = session.outputting)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    session.name.ifBlank { S.untitledSession },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${session.id.take(8)} · ${relativeTime(session.lastActive)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!ios) {
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
                    if (session.running && processStopSupported) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(S.stopPiProcess)
                                    if (session.outputting) {
                                        Text(
                                            S.stopPiProcessOutputtingHint,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                        )
                                    }
                                }
                            },
                            leadingIcon = {
                                Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(18.dp))
                            },
                            onClick = { onStopProcess(); menuOpen = false },
                            enabled = !session.outputting,
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(S.delete) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)) },
                        onClick = { onDelete(); menuOpen = false },
                    )
                }
            }
        }
    }
    if (ios && actionSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { actionSheetOpen = false },
            sheetState = sheetState,
            dragHandle = null,
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Text(
                    session.name.ifBlank { S.untitledSession },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                SheetAction(
                    icon = { tint -> Icon(Icons.Filled.Edit, null, Modifier.size(19.dp), tint = tint) },
                    label = S.rename,
                ) {
                    actionSheetOpen = false
                    onRename()
                }
                if (session.running && processStopSupported) {
                    SheetAction(
                        icon = { tint ->
                            Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(19.dp), tint = tint)
                        },
                        label = S.stopPiProcess,
                        supportingText =
                            S.stopPiProcessOutputtingHint.takeIf { session.outputting },
                        destructive = true,
                        enabled = !session.outputting,
                    ) {
                        actionSheetOpen = false
                        onStopProcess()
                    }
                }
                SheetAction(
                    icon = { tint -> Icon(Icons.Filled.Delete, null, Modifier.size(19.dp), tint = tint) },
                    label = S.delete,
                    destructive = true,
                ) {
                    actionSheetOpen = false
                    onDelete()
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actionSheetOpen = false }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        S.cancel,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
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
private fun SheetAction(
    icon: @Composable (Color) -> Unit,
    label: String,
    supportingText: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon(tint) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
            )
            supportingText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun SessionStatus(outputting: Boolean) {
    // Streaming keeps the teal tertiary; a merely alive process gets success green.
    // Neither uses primary so the chips never read as grey.
    val color = if (outputting) MaterialTheme.colorScheme.tertiary else piExtras.success
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (outputting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(9.dp),
                    color = color,
                    strokeWidth = 1.5.dp,
                )
            } else {
                Box(Modifier.size(6.dp).background(color, CircleShape))
            }
            Spacer(Modifier.width(4.dp))
            Text(
                if (outputting) S.sessionOutputting else S.sessionRunning,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = color,
                maxLines = 1,
            )
        }
    }
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
