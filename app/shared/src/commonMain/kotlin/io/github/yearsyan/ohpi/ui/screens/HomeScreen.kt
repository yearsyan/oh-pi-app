package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.yearsyan.ohpi.filebrowser.FileBrowserController
import io.github.yearsyan.ohpi.filebrowser.FileBrowserScreen
import io.github.yearsyan.ohpi.filebrowser.rememberApkOpener
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.ui.AppViewModel
import io.github.yearsyan.ohpi.ui.components.RenameDialog
import io.github.yearsyan.ohpi.ui.components.SessionListPane
import io.github.yearsyan.ohpi.ui.components.WorkspaceDialog
import kotlinx.serialization.Serializable

private val WideBreakpoint = 840.dp
private val ListPaneWidth = 300.dp

// Navigation resolves serializers from KType at runtime. These route classes
// must not be private because the JVM serializer needs to access object fields.
@Serializable
internal data object SessionListRoute

@Serializable
internal data class ChatRoute(val sessionId: String)

@Serializable
internal data object SettingsRoute

@Serializable
internal data object LicensesRoute

@Serializable
internal data object PortForwardsRoute

@Serializable
internal data class FilesRoute(val path: String)

/** Adaptive home: single-pane navigation on phones, list+detail on tablets/desktop. */
@Composable
fun HomeScreen(vm: AppViewModel) {
    var renaming by remember { mutableStateOf<SavedSession?>(null) }
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

    fun openCompactChat(sessionId: String) {
        vm.openChat(sessionId)
        navController.navigate(ChatRoute(sessionId))
    }

    fun openFileBrowser(path: String) {
        navController.navigate(FilesRoute(path))
    }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
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
                    onRequestNewChat = { newChatWide = it },
                    onBrowseFiles = ::openFileBrowser,
                    onOpenPortForwards = { navController.navigate(PortForwardsRoute) },
                )
            }

            composable<ChatRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ChatRoute>()
                LaunchedEffect(route.sessionId) {
                    if (vm.activeChatId == null) vm.openChat(route.sessionId)
                }
                MainDestination(
                    vm = vm,
                    wide = wide,
                    compactChatId = vm.activeChatId ?: route.sessionId,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                    onSelectServer = ::selectServer,
                    onOpenCompactChat = ::openCompactChat,
                    onDeleteSession = ::deleteSession,
                    onRenameSession = { renaming = it },
                    onRequestNewChat = { newChatWide = it },
                    onBrowseFiles = ::openFileBrowser,
                    onOpenPortForwards = { navController.navigate(PortForwardsRoute) },
                )
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
                FileBrowserScreen(
                    controller = browserController,
                    onBack = { navController.popBackStack() },
                    onOpenApk = rememberApkOpener(
                        downloadFile = vm::downloadFile,
                        onToast = { vm.toast(it) },
                    ),
                )
            }

            composable<SettingsRoute> {
                SettingsScreen(
                    servers = vm.servers,
                    activeServerId = vm.activeServerId,
                    themeMode = vm.themeMode,
                    language = vm.language,
                    onBack = { navController.popBackStack() },
                    onSelectServer = ::selectServer,
                    onSaveServer = vm::saveServer,
                    onDeleteServer = vm::deleteServer,
                    onThemeMode = vm::updateThemeMode,
                    onLanguage = vm::updateLanguage,
                    onOpenLicenses = { navController.navigate(LicensesRoute) },
                )
            }

            composable<LicensesRoute> {
                LicensesScreen(onBack = { navController.popBackStack() })
            }

            composable<PortForwardsRoute> {
                PortForwardsScreen(vm = vm, onBack = { navController.popBackStack() })
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

    newChatWide?.let { wide ->
        WorkspaceDialog(
            initial = vm.lastWorkspace,
            workspaces = vm.sessions.map { it.workDir }.distinct(),
            fetchDirs = { path -> vm.listDirs(path) },
            createDir = { parent, name -> vm.createDir(parent, name) },
            onDismiss = { newChatWide = null },
            onConfirm = { workDir ->
                val sessionId = vm.startNewChat(workDir)
                if (!wide) navController.navigate(ChatRoute(sessionId))
            },
        )
    }
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
    onRequestNewChat: (Boolean) -> Unit,
    onBrowseFiles: (String) -> Unit,
    onOpenPortForwards: () -> Unit,
) {
    when {
        wide -> WideHome(
            vm = vm,
            onRenameSession = onRenameSession,
            onRequestNewChat = { onRequestNewChat(true) },
            onSelectServer = onSelectServer,
            onOpenSettings = onOpenSettings,
            onDeleteSession = onDeleteSession,
            onBrowseFiles = onBrowseFiles,
            onOpenPortForwards = onOpenPortForwards,
        )

        compactChatId != null -> ChatScreen(
            controller = vm.controllerFor(compactChatId),
            showBack = true,
            onBack = onNavigateBack,
            onRename = { name -> vm.renameSession(compactChatId, name) },
            onDelete = { onDeleteSession(compactChatId) },
            onBrowseFiles = onBrowseFiles,
        )

        else -> SessionListPane(
            sessions = vm.sessions,
            servers = vm.servers,
            activeServer = vm.activeServer,
            activeChatId = vm.activeChatId,
            wide = false,
            sessionsLoading = vm.sessionsLoading,
            onRefresh = vm::refreshSessions,
            onNewChat = { onRequestNewChat(false) },
            onSelectSession = { onOpenCompactChat(it.id) },
            onSelectServer = onSelectServer,
            onOpenSettings = onOpenSettings,
            onRenameSession = onRenameSession,
            onDeleteSession = { onDeleteSession(it.id) },
            onBrowseFiles = { onBrowseFiles("") },
            onOpenPortForwards = onOpenPortForwards,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WideHome(
    vm: AppViewModel,
    onRenameSession: (SavedSession) -> Unit,
    onRequestNewChat: () -> Unit,
    onSelectServer: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onBrowseFiles: (String) -> Unit,
    onOpenPortForwards: () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        SessionListPane(
            sessions = vm.sessions,
            servers = vm.servers,
            activeServer = vm.activeServer,
            activeChatId = vm.activeChatId,
            wide = true,
            sessionsLoading = vm.sessionsLoading,
            onRefresh = vm::refreshSessions,
            onNewChat = onRequestNewChat,
            onSelectSession = { vm.selectChatWide(it.id) },
            onSelectServer = onSelectServer,
            onOpenSettings = onOpenSettings,
            onRenameSession = onRenameSession,
            onDeleteSession = { onDeleteSession(it.id) },
            onBrowseFiles = { onBrowseFiles("") },
            onOpenPortForwards = onOpenPortForwards,
            modifier = Modifier.width(ListPaneWidth),
        )
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f)) {
            val chatId = vm.activeChatId
            if (chatId != null) {
                ChatScreen(
                    controller = vm.controllerFor(chatId),
                    showBack = false,
                    onBack = {},
                    onRename = { name -> vm.renameSession(chatId, name) },
                    onDelete = { onDeleteSession(chatId) },
                    onBrowseFiles = onBrowseFiles,
                )
            } else {
                WideEmptyState(onNewChat = onRequestNewChat)
            }
        }
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
