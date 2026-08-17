package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.yearsyan.ohpi.data.SavedSession
import io.github.yearsyan.ohpi.data.WorkspaceSummary
import io.github.yearsyan.ohpi.filebrowser.FileBrowserController
import io.github.yearsyan.ohpi.filebrowser.FileBrowserScreen
import io.github.yearsyan.ohpi.filebrowser.rememberApkOpener
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.ui.AppViewModel
import io.github.yearsyan.ohpi.ui.GatewayHostOs
import io.github.yearsyan.ohpi.ui.components.LocalGlassChromeAlpha
import io.github.yearsyan.ohpi.ui.components.RenameDialog
import io.github.yearsyan.ohpi.ui.components.SessionListPane
import io.github.yearsyan.ohpi.ui.components.SyncGlassWithBackGesture
import io.github.yearsyan.ohpi.ui.components.WorkspaceDialog
import io.github.yearsyan.ohpi.ui.components.WorkspaceMetadataDialog
import io.github.yearsyan.ohpi.ui.components.rememberGlassExitController
import kotlinx.serialization.Serializable

private val WideBreakpoint = 840.dp
private val ListPaneWidth = 300.dp
private val ListRailWidth = 64.dp

// Navigation resolves serializers from KType at runtime. These route classes
// must not be private because the JVM serializer needs to access object fields.
@Serializable
internal data object SessionListRoute

@Serializable
internal data class ChatRoute(val sessionId: String)

@Serializable
internal data object SettingsRoute

@Serializable
internal data class SettingsSectionRoute(val section: String)

@Serializable
internal data object AddServerRoute

@Serializable
internal data object AddProviderRoute

@Serializable
internal data object PortForwardsRoute

@Serializable
internal data class FilesRoute(val path: String)

@Serializable
internal data object ScheduledTasksRoute

@Serializable
internal data class ScheduledTaskEditorRoute(val taskId: String = "")

@Serializable
internal data class ScheduledTaskSessionsRoute(val taskId: String, val taskName: String)

/** Adaptive home: single-pane navigation on phones, list+detail on tablets/desktop. */
@Composable
fun HomeScreen(vm: AppViewModel) {
    var renaming by remember { mutableStateOf<SavedSession?>(null) }
    var editingWorkspace by remember { mutableStateOf<WorkspaceSummary?>(null) }
    var newChatWide by remember { mutableStateOf<Boolean?>(null) }
    val navController = rememberNavController()
    val horizontalDirection = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1

    fun returnToSessionList() {
        navController.popBackStack(route = SessionListRoute, inclusive = false)
    }

    fun selectServer(id: String) {
        if (id == vm.activeServerId) return
        vm.selectServer(id)
        returnToSessionList()
    }

    fun deleteSession(id: String) {
        val wasActive = vm.activeChatId == id
        vm.removeSession(id)
        if (wasActive) returnToSessionList()
    }

    fun archiveWorkspace(workspace: WorkspaceSummary) {
        if (vm.archiveWorkspace(workspace.id)) returnToSessionList()
    }

    fun deleteWorkspace(workspace: WorkspaceSummary) {
        if (vm.deleteWorkspace(workspace.id)) returnToSessionList()
    }

    fun openCompactChat(sessionId: String) {
        vm.openChat(sessionId)
        navController.navigate(ChatRoute(sessionId))
    }

    fun openFileBrowser(path: String) {
        navController.navigate(FilesRoute(path))
    }

    fun openProviders() {
        navController.navigate(SettingsSectionRoute(SettingsSection.Providers.name))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WideBreakpoint

        NavHost(
            navController = navController,
            startDestination = SessionListRoute,
            modifier = Modifier.fillMaxSize(),
            popEnterTransition = {
                fadeIn(
                    animationSpec = tween(
                        durationMillis = 240,
                        easing = LinearOutSlowInEasing,
                    ),
                    initialAlpha = 0.72f,
                ) + slideInHorizontally(
                    animationSpec = tween(
                        durationMillis = 280,
                        easing = FastOutSlowInEasing,
                    ),
                ) { fullWidth -> -horizontalDirection * fullWidth / 16 }
            },
            popExitTransition = {
                fadeOut(
                    animationSpec = tween(
                        durationMillis = 220,
                        easing = FastOutLinearInEasing,
                    ),
                ) + slideOutHorizontally(
                    animationSpec = tween(
                        durationMillis = 260,
                        easing = FastOutSlowInEasing,
                    ),
                ) { fullWidth -> horizontalDirection * fullWidth / 9 }
            },
        ) {
            composable<SessionListRoute> {
                SafeDrawingHost {
                    MainDestination(
                        vm = vm,
                        wide = wide,
                        compactChatId = null,
                        onNavigateBack = { navController.popBackStack() },
                        onOpenSettings = { navController.navigate(SettingsRoute) },
                        onSelectServer = ::selectServer,
                        onOpenCompactChat = ::openCompactChat,
                        onDeleteSession = ::deleteSession,
                        onRenameSession = { renaming = it },
                        onEditWorkspace = { editingWorkspace = it },
                        onArchiveWorkspace = ::archiveWorkspace,
                        onDeleteWorkspace = ::deleteWorkspace,
                        onRequestNewChat = { newChatWide = it },
                        onBrowseFiles = ::openFileBrowser,
                        onOpenProviders = ::openProviders,
                        onOpenPortForwards = { navController.navigate(PortForwardsRoute) },
                        onOpenScheduledTasks = { navController.navigate(ScheduledTasksRoute) },
                    )
                }
            }

            composable<ChatRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ChatRoute>()
                LaunchedEffect(route.sessionId) {
                    if (vm.activeChatId == null) vm.openChat(route.sessionId)
                }
                SafeDrawingHost {
                    // Native glass chrome cannot follow the pop transition, so
                    // fade it out first and only then pop the destination.
                    val glassExit = rememberGlassExitController()
                    CompositionLocalProvider(LocalGlassChromeAlpha provides glassExit.glassAlpha) {
                        // Edge-swipe back bypasses onNavigateBack, so the glass
                        // chrome tracks the gesture recognizer directly.
                        SyncGlassWithBackGesture(
                            controller = glassExit,
                            isActive = { navController.currentBackStackEntry == backStackEntry },
                        )
                        MainDestination(
                            vm = vm,
                            wide = wide,
                            compactChatId = vm.activeChatId ?: route.sessionId,
                            onNavigateBack = {
                                glassExit.fadeOutThen { navController.popBackStack() }
                            },
                            onOpenSettings = { navController.navigate(SettingsRoute) },
                            onSelectServer = ::selectServer,
                            onOpenCompactChat = ::openCompactChat,
                            onDeleteSession = { id -> glassExit.fadeOutThen { deleteSession(id) } },
                            onRenameSession = { renaming = it },
                            onEditWorkspace = { editingWorkspace = it },
                            onArchiveWorkspace = ::archiveWorkspace,
                            onDeleteWorkspace = ::deleteWorkspace,
                            onRequestNewChat = { newChatWide = it },
                            onBrowseFiles = ::openFileBrowser,
                            onOpenProviders = ::openProviders,
                            onOpenPortForwards = { navController.navigate(PortForwardsRoute) },
                            onOpenScheduledTasks = { navController.navigate(ScheduledTasksRoute) },
                        )
                    }
                }
            }

            composable<FilesRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<FilesRoute>()
                val scope = rememberCoroutineScope()
                val browserController = remember(route.path, vm.activeServerId) {
                    FileBrowserController(
                        scope = scope,
                        initialPath = route.path,
                        listFiles = vm::listFiles,
                        readFile = vm::readFile,
                        downloadFile = vm::downloadFile,
                    )
                }
                SafeDrawingHost {
                    FileBrowserScreen(
                        controller = browserController,
                        onBack = { navController.popBackStack() },
                        onOpenApk = rememberApkOpener(
                            downloadFile = vm::downloadFile,
                            onToast = { vm.toast(it) },
                        ),
                    )
                }
            }

            composable<SettingsRoute> {
                SafeDrawingHost {
                    SettingsScreen(
                        vm = vm,
                        servers = vm.servers,
                        sshKeys = vm.sshKeys,
                        activeServerId = vm.activeServerId,
                        themeMode = vm.themeMode,
                        language = vm.language,
                        wide = wide,
                        section = null,
                        onBack = { navController.popBackStack() },
                        onOpenSection = { navController.navigate(SettingsSectionRoute(it.name)) },
                        onSelectServer = vm::selectServer,
                        onSaveServer = vm::saveServer,
                        onDeleteServer = vm::deleteServer,
                        onSaveSshKey = vm::saveSshKey,
                        onDeleteSshKey = { vm.deleteSshKey(it.id) },
                        onStopManagedGateway = vm::stopManagedGateway,
                        onThemeMode = vm::updateThemeMode,
                        onLanguage = vm::updateLanguage,
                        onAddServer = { navController.navigate(AddServerRoute) },
                        onAddProvider = { navController.navigate(AddProviderRoute) },
                    )
                }
            }

            composable<SettingsSectionRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SettingsSectionRoute>()
                SafeDrawingHost {
                    SettingsScreen(
                        vm = vm,
                        servers = vm.servers,
                        sshKeys = vm.sshKeys,
                        activeServerId = vm.activeServerId,
                        themeMode = vm.themeMode,
                        language = vm.language,
                        wide = wide,
                        section = settingsSectionFor(route.section),
                        onBack = { navController.popBackStack() },
                        onOpenSection = { navController.navigate(SettingsSectionRoute(it.name)) },
                        onSelectServer = vm::selectServer,
                        onSaveServer = vm::saveServer,
                        onDeleteServer = vm::deleteServer,
                        onSaveSshKey = vm::saveSshKey,
                        onDeleteSshKey = { vm.deleteSshKey(it.id) },
                        onStopManagedGateway = vm::stopManagedGateway,
                        onThemeMode = vm::updateThemeMode,
                        onLanguage = vm::updateLanguage,
                        onAddServer = { navController.navigate(AddServerRoute) },
                        onAddProvider = { navController.navigate(AddProviderRoute) },
                    )
                }
            }

            composable<AddServerRoute> {
                SafeDrawingHost {
                    AddServerScreen(
                        keys = vm.sshKeys,
                        onSave = { profile, newKey ->
                            vm.saveServer(profile, newKey)
                            navController.popBackStack()
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            composable<AddProviderRoute> {
                SafeDrawingHost {
                    AddProviderScreen(vm = vm, onBack = { navController.popBackStack() })
                }
            }

            composable<PortForwardsRoute> {
                SafeDrawingHost {
                    PortForwardsScreen(vm = vm, onBack = { navController.popBackStack() })
                }
            }

            composable<ScheduledTasksRoute> {
                SafeDrawingHost {
                    ScheduledTasksScreen(
                        vm = vm,
                        onBack = { navController.popBackStack() },
                        onAdd = { navController.navigate(ScheduledTaskEditorRoute()) },
                        onEdit = { taskId -> navController.navigate(ScheduledTaskEditorRoute(taskId)) },
                        onSessions = { task ->
                            navController.navigate(ScheduledTaskSessionsRoute(task.id, task.name))
                        },
                    )
                }
            }

            composable<ScheduledTaskSessionsRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ScheduledTaskSessionsRoute>()
                SafeDrawingHost {
                    ScheduledTaskSessionsScreen(
                        vm = vm,
                        taskId = route.taskId,
                        taskName = route.taskName,
                        onBack = { navController.popBackStack() },
                        onOpenSession = { session ->
                            vm.openSavedSession(session)
                            navController.navigate(ChatRoute(session.id))
                        },
                    )
                }
            }

            composable<ScheduledTaskEditorRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ScheduledTaskEditorRoute>()
                ScheduledTaskEditorScreen(
                    vm = vm,
                    taskId = route.taskId.ifBlank { null },
                    onBack = { navController.popBackStack() },
                    onSaved = {
                        navController.popBackStack(route = ScheduledTasksRoute, inclusive = true)
                        navController.navigate(ScheduledTasksRoute)
                    },
                )
            }
        }
    }

    renaming?.let { session ->
        RenameDialog(
            initial = session.name,
            onDismiss = { renaming = null },
            onConfirm = { name -> vm.renameSession(session.id, name) },
        )
    }

    editingWorkspace?.let { workspace ->
        WorkspaceMetadataDialog(
            workspace = workspace,
            supportsResourceConfiguration = vm.activeGatewayInfo?.supportsWorkspaceResources == true,
            onDismiss = { editingWorkspace = null },
            onConfirm = { name, prompt, resources ->
                vm.saveWorkspaceMetadata(workspace.id, name, prompt, resources)
            },
        )
    }

    newChatWide?.let { wide ->
        WorkspaceDialog(
            initialWorkspaceId = vm.lastWorkspaceId,
            workspaces = vm.homeWorkspaces,
            fetchDirs = { path -> vm.listDirs(path) },
            createDir = { parent, name -> vm.createDir(parent, name) },
            addWorkspace = vm::addWorkspace,
            onDismiss = { newChatWide = null },
            onConfirm = { workspace ->
                val sessionId = vm.startNewChat(workspace.id)
                if (!wide) navController.navigate(ChatRoute(sessionId))
            },
        )
    }
}

/**
 * Hosts destinations that keep the legacy safe-drawing insets. Full-bleed
 * screens (the scheduled task editor) skip this wrapper and pad their own
 * content so their background extends behind the system bars.
 */
@Composable
private fun SafeDrawingHost(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() }
}

@Composable
private fun MainDestination(
    vm: AppViewModel,
    wide: Boolean,
    compactChatId: String?,
    onNavigateBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSelectServer: (String) -> Unit,
    onOpenCompactChat: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (SavedSession) -> Unit,
    onEditWorkspace: (WorkspaceSummary) -> Unit,
    onArchiveWorkspace: (WorkspaceSummary) -> Unit,
    onDeleteWorkspace: (WorkspaceSummary) -> Unit,
    onRequestNewChat: (Boolean) -> Unit,
    onBrowseFiles: (String) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenPortForwards: () -> Unit,
    onOpenScheduledTasks: () -> Unit,
) {
    when {
        wide -> WideHome(
            vm = vm,
            onRenameSession = onRenameSession,
            onEditWorkspace = onEditWorkspace,
            onArchiveWorkspace = onArchiveWorkspace,
            onDeleteWorkspace = onDeleteWorkspace,
            onRequestNewChat = { onRequestNewChat(true) },
            onSelectServer = onSelectServer,
            onOpenSettings = onOpenSettings,
            onDeleteSession = onDeleteSession,
            onBrowseFiles = onBrowseFiles,
            onOpenProviders = onOpenProviders,
            onOpenPortForwards = onOpenPortForwards,
            onOpenScheduledTasks = onOpenScheduledTasks,
        )

        compactChatId != null -> ChatScreen(
            controller = vm.controllerFor(compactChatId),
            serverId = vm.activeServerId,
            showBack = true,
            onBack = onNavigateBack,
            onRename = { name -> vm.renameSession(compactChatId, name) },
            onDelete = { onDeleteSession(compactChatId) },
            onStopProcess =
                if (vm.activeGatewayInfo?.supportsSessionProcessStop == true) {
                    { vm.stopSessionProcess(compactChatId) }
                } else {
                    null
                },
            onBrowseFiles = onBrowseFiles,
            onOpenProviders = onOpenProviders,
            onLoadToolImage = vm::downloadFile,
        )

        else -> SessionListPane(
            workspaces = vm.homeWorkspaces,
            servers = vm.servers,
            activeServer = vm.activeServer,
            activeChatId = vm.activeChatId,
            wide = false,
            sessionsLoading = vm.sessionsLoading,
            sessionsRefreshing = vm.sessionsRefreshing,
            onRefresh = vm::refreshSessions,
            onNewChat = { onRequestNewChat(false) },
            onSelectSession = { onOpenCompactChat(it.id) },
            onSelectServer = onSelectServer,
            onOpenSettings = onOpenSettings,
            onRenameSession = onRenameSession,
            onDeleteSession = { onDeleteSession(it.id) },
            onStopSessionProcess = { vm.stopSessionProcess(it.id) },
            onLoadMoreSessions = { vm.loadMoreWorkspaceSessions(it.id) },
            onEditWorkspace = onEditWorkspace,
            onArchiveWorkspace = onArchiveWorkspace,
            onDeleteWorkspace = onDeleteWorkspace,
            sessionProcessStopSupported =
                vm.activeGatewayInfo?.supportsSessionProcessStop == true,
            hostOs = vm.activeGatewayInfo?.hostOs ?: GatewayHostOs.Unknown,
            onBrowseFiles = { onBrowseFiles("") },
            onOpenPortForwards = onOpenPortForwards,
            scheduledTasksSupported = vm.activeGatewayInfo?.supportsScheduledTasks == true,
            onOpenScheduledTasks = onOpenScheduledTasks,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WideHome(
    vm: AppViewModel,
    onRenameSession: (SavedSession) -> Unit,
    onEditWorkspace: (WorkspaceSummary) -> Unit,
    onArchiveWorkspace: (WorkspaceSummary) -> Unit,
    onDeleteWorkspace: (WorkspaceSummary) -> Unit,
    onRequestNewChat: () -> Unit,
    onSelectServer: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onBrowseFiles: (String) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenPortForwards: () -> Unit,
    onOpenScheduledTasks: () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        // Sidebar animates between the full list pane and a slim rail.
        val collapsed = vm.sidebarCollapsed
        val sidebarWidth by animateDpAsState(
            targetValue = if (collapsed) ListRailWidth else ListPaneWidth,
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
            label = "sidebarWidth",
        )
        Box(Modifier.width(sidebarWidth).fillMaxHeight().clipToBounds()) {
            if (collapsed) {
                SessionListRail(
                    onExpand = { vm.updateSidebarCollapsed(false) },
                    onNewChat = onRequestNewChat,
                    onOpenSettings = onOpenSettings,
                    scheduledTasksSupported = vm.activeGatewayInfo?.supportsScheduledTasks == true,
                    onOpenScheduledTasks = onOpenScheduledTasks,
                    modifier = Modifier.width(ListRailWidth),
                )
            } else {
                SessionListPane(
                    workspaces = vm.homeWorkspaces,
                    servers = vm.servers,
                    activeServer = vm.activeServer,
                    activeChatId = vm.activeChatId,
                    wide = true,
                    sessionsLoading = vm.sessionsLoading,
                    sessionsRefreshing = vm.sessionsRefreshing,
                    onRefresh = vm::refreshSessions,
                    onNewChat = onRequestNewChat,
                    onSelectSession = { vm.selectChatWide(it.id) },
                    onSelectServer = onSelectServer,
                    onOpenSettings = onOpenSettings,
                    onRenameSession = onRenameSession,
                    onDeleteSession = { onDeleteSession(it.id) },
                    onStopSessionProcess = { vm.stopSessionProcess(it.id) },
                    onLoadMoreSessions = { vm.loadMoreWorkspaceSessions(it.id) },
                    onEditWorkspace = onEditWorkspace,
                    onArchiveWorkspace = onArchiveWorkspace,
                    onDeleteWorkspace = onDeleteWorkspace,
                    sessionProcessStopSupported =
                        vm.activeGatewayInfo?.supportsSessionProcessStop == true,
                    hostOs = vm.activeGatewayInfo?.hostOs ?: GatewayHostOs.Unknown,
                    onBrowseFiles = { onBrowseFiles("") },
                    onOpenPortForwards = onOpenPortForwards,
                    scheduledTasksSupported = vm.activeGatewayInfo?.supportsScheduledTasks == true,
                    onOpenScheduledTasks = onOpenScheduledTasks,
                    modifier = Modifier.width(ListPaneWidth),
                    onCollapse = { vm.updateSidebarCollapsed(true) },
                )
            }
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f)) {
            val chatId = vm.activeChatId
            if (chatId != null) {
                ChatScreen(
                    controller = vm.controllerFor(chatId),
                    serverId = vm.activeServerId,
                    showBack = false,
                    onBack = {},
                    onRename = { name -> vm.renameSession(chatId, name) },
                    onDelete = { onDeleteSession(chatId) },
                    onStopProcess =
                        if (vm.activeGatewayInfo?.supportsSessionProcessStop == true) {
                            { vm.stopSessionProcess(chatId) }
                        } else {
                            null
                        },
                    onBrowseFiles = onBrowseFiles,
                    onOpenProviders = onOpenProviders,
                    onLoadToolImage = vm::downloadFile,
                )
            } else {
                WideEmptyState(onNewChat = onRequestNewChat)
            }
        }
    }
}

/** Slim rail shown when the wide-layout session list sidebar is collapsed. */
@Composable
private fun SessionListRail(
    onExpand: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    scheduledTasksSupported: Boolean,
    onOpenScheduledTasks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        IconButton(onClick = onExpand) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = S.expandSidebar,
            )
        }
        Spacer(Modifier.height(8.dp))
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
        Spacer(Modifier.weight(1f))
        if (scheduledTasksSupported) {
            IconButton(onClick = onOpenScheduledTasks) {
                Icon(Icons.Filled.Schedule, contentDescription = S.scheduledTasksTitle)
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = S.settingsTitle)
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun WideEmptyState(onNewChat: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "π",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            S.emptyChatTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            S.noSessionsHint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onNewChat) { Text(S.newChat) }
    }
}
