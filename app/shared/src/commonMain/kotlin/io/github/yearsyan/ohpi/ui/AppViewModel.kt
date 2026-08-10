package io.github.yearsyan.ohpi.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yearsyan.ohpi.chat.ChatController
import io.github.yearsyan.ohpi.chat.Toast
import io.github.yearsyan.ohpi.chat.clearEntryCache
import io.github.yearsyan.ohpi.data.AppLanguage
import io.github.yearsyan.ohpi.data.PortForward
import io.github.yearsyan.ohpi.data.SavedSession
import io.github.yearsyan.ohpi.data.ServerConnectionMode
import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.SettingsStore
import io.github.yearsyan.ohpi.data.SshAuthentication
import io.github.yearsyan.ohpi.data.SshPrivateKey
import io.github.yearsyan.ohpi.data.ThemeMode
import io.github.yearsyan.ohpi.data.WorkspaceSummary
import io.github.yearsyan.ohpi.i18n.Strings
import io.github.yearsyan.ohpi.isApplicationActive
import io.github.yearsyan.ohpi.net.FileApiException
import io.github.yearsyan.ohpi.net.FileListResponse
import io.github.yearsyan.ohpi.net.FileReadResponse
import io.github.yearsyan.ohpi.net.FsListException
import io.github.yearsyan.ohpi.net.FsListResponse
import io.github.yearsyan.ohpi.net.GatewayTransport
import io.github.yearsyan.ohpi.net.GatewayRuntimeConfig
import io.github.yearsyan.ohpi.net.GATEWAY_FEATURE_RUNTIME_CONFIG
import io.github.yearsyan.ohpi.net.GATEWAY_FEATURE_SESSION_PROCESS_STOP
import io.github.yearsyan.ohpi.net.GatewayProvider
import io.github.yearsyan.ohpi.net.GatewayProviderAuthMethod
import io.github.yearsyan.ohpi.net.PiClient
import io.github.yearsyan.ohpi.net.ProviderAuthEvent
import io.github.yearsyan.ohpi.net.PortForwardManager
import io.github.yearsyan.ohpi.net.PortForwardStatus
import io.github.yearsyan.ohpi.net.SshHostKeyPrompt
import io.github.yearsyan.ohpi.net.loopbackUrlTarget
import io.github.yearsyan.ohpi.net.rewriteLoopbackUrl
import io.github.yearsyan.ohpi.net.rewriteLoopbackUrlToGatewayHost
import io.github.yearsyan.ohpi.net.createGatewayDir
import io.github.yearsyan.ohpi.net.createGatewayWorkspace
import io.github.yearsyan.ohpi.net.gatewayTarget
import io.github.yearsyan.ohpi.net.isLoopbackHostName
import io.github.yearsyan.ohpi.net.deleteGatewaySession
import io.github.yearsyan.ohpi.net.downloadGatewayFile
import io.github.yearsyan.ohpi.net.fetchGatewayHealth
import io.github.yearsyan.ohpi.net.getGatewayRuntimeConfig
import io.github.yearsyan.ohpi.net.getGatewayCapabilities
import io.github.yearsyan.ohpi.net.listGatewayProviders
import io.github.yearsyan.ohpi.net.listGatewayDirs
import io.github.yearsyan.ohpi.net.listGatewayFiles
import io.github.yearsyan.ohpi.net.listGatewayWorkspaceSessions
import io.github.yearsyan.ohpi.net.listGatewayWorkspaces
import io.github.yearsyan.ohpi.net.logoutGatewayProvider
import io.github.yearsyan.ohpi.net.nowMillis
import io.github.yearsyan.ohpi.net.readGatewayFile
import io.github.yearsyan.ohpi.net.renameGatewaySession
import io.github.yearsyan.ohpi.net.stopGatewaySessionProcess
import io.github.yearsyan.ohpi.net.requestGatewayRuntimeRestart
import io.github.yearsyan.ohpi.net.updateGatewayRuntimeConfig
import io.github.yearsyan.ohpi.net.updateGatewayWorkspace
import io.github.yearsyan.ohpi.net.awaitManagedGatewayHealth
import io.github.yearsyan.ohpi.net.buildProviderAuthWsUrl
import io.github.yearsyan.ohpi.net.parseProviderAuthEvent
import io.github.yearsyan.ohpi.net.providerAuthCancelResponse
import io.github.yearsyan.ohpi.net.providerAuthInputResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** A loopback link tapped on an SSH-connected server with no covering port forward. */
class LoopbackLinkPrompt(
    val originalUrl: String,
    val remotePort: Int,
    val openUrl: (String) -> Unit,
)

data class ProviderAuthPromptState(
    val id: String,
    val kind: String,
    val message: String,
    val placeholder: String,
    val options: List<String>,
    val descriptions: List<String>,
)

data class ProviderAuthFlowState(
    val providerId: String,
    val providerName: String,
    val authType: String,
    val status: String = "",
    val prompt: ProviderAuthPromptState? = null,
    val authorizationUrl: String = "",
    val userCode: String = "",
    val links: List<Pair<String, String>> = emptyList(),
    val completed: Boolean = false,
    val error: String = "",
)

/** Operating system family reported by the connected gateway host. */
enum class GatewayHostOs {
    Unknown,
    MacOS,
    Windows,
    Linux,
}

/** Runtime information advertised by the currently connected gateway. */
data class GatewayServerInfo(
    val version: String,
    val protocol: Int,
    val os: String = "",
    val features: Set<String>,
) {
    val supportsSessionProcessStop: Boolean
        get() = GATEWAY_FEATURE_SESSION_PROCESS_STOP in features

    val supportsRuntimeConfig: Boolean
        get() = GATEWAY_FEATURE_RUNTIME_CONFIG in features

    val hostOs: GatewayHostOs
        get() =
            when (os.lowercase()) {
                "darwin", "macos" -> GatewayHostOs.MacOS
                "windows" -> GatewayHostOs.Windows
                "linux" -> GatewayHostOs.Linux
                else -> GatewayHostOs.Unknown
            }
}

class AppViewModel(
    private val store: SettingsStore = SettingsStore(),
) : ViewModel() {

    // ---- persisted state ----
    var servers = mutableStateListOf<ServerProfile>(); private set
    var sshKeys = mutableStateListOf<SshPrivateKey>(); private set
    var activeServerId by mutableStateOf(""); private set
    var themeMode by mutableStateOf(ThemeMode.System); private set
    var language by mutableStateOf(AppLanguage.System); private set
    var sidebarCollapsed by mutableStateOf(false); private set
    var workspaces = mutableStateListOf<WorkspaceSummary>(); private set
    var sessions = mutableStateListOf<SavedSession>(); private set
    var providers = mutableStateListOf<GatewayProvider>(); private set

    // ---- runtime state ----
    var activeGatewayInfo by mutableStateOf<GatewayServerInfo?>(null); private set
    var activeChatId by mutableStateOf<String?>(null); private set
    var sessionsLoading by mutableStateOf(false); private set
    var providersLoading by mutableStateOf(false); private set
    var providerLogoutId by mutableStateOf<String?>(null); private set
    var providerAuthFlow by mutableStateOf<ProviderAuthFlowState?>(null); private set
    var sshHostKeyPrompt by mutableStateOf<SshHostKeyPrompt?>(null); private set
    var loopbackLinkPrompt by mutableStateOf<LoopbackLinkPrompt?>(null); private set
    val toasts = mutableStateListOf<Toast>()

    private val controllers = HashMap<String, ChatController>()
    private var gatewayTransport: GatewayTransport? = null
    private var gatewayTransportServerId = ""
    private var forwardManager: PortForwardManager? = null
    private var forwardManagerServerId = ""
    private var sshHostKeyDecision: CompletableDeferred<Boolean>? = null
    private var sessionRefreshGeneration = 0L
    private var interruptedSessionRefreshGeneration: Long? = null
    private var providerRefreshGeneration = 0L
    private var providerAuthGeneration = 0L
    private var providerAuthClient: PiClient? = null
    private var stringsProvider: () -> Strings = { io.github.yearsyan.ohpi.i18n.EnStrings }

    private companion object {
        const val MIN_REFRESH_INDICATOR_MS = 600L
    }

    init {
        servers.addAll(store.loadServers())
        sshKeys.addAll(store.loadSshKeys())
        activeServerId = store.activeServerId
        themeMode = store.themeMode
        language = store.language
        sidebarCollapsed = store.sidebarCollapsed
        loadSessionsForActive()
        syncPortForwards()
    }

    fun setStringsProvider(provider: () -> Strings) {
        stringsProvider = provider
    }

    val activeServer: ServerProfile? get() = servers.firstOrNull { it.id == activeServerId }
    val hasServers: Boolean get() = servers.isNotEmpty()

    /** Selects a chat, preparing new chats locally and attaching saved sessions. */
    fun openChat(
        sessionId: String,
        isNew: Boolean = false,
        workspaceId: String = "",
        workspaceDirectory: String = "",
    ) {
        activeChatId = sessionId
        val c = controllerFor(sessionId)
        if (isNew) {
            c.prepareCreate(workspaceId, workspaceDirectory)
        } else if (!c.active) {
            seedSessionName(c, sessionId)
            c.connect("attach", sessionId)
        }
    }

    fun selectChatWide(
        sessionId: String?,
        isNew: Boolean = false,
        workspaceId: String = "",
        workspaceDirectory: String = "",
    ) {
        activeChatId = sessionId
        sessionId ?: return
        val c = controllerFor(sessionId)
        if (isNew) {
            c.prepareCreate(workspaceId, workspaceDirectory)
        } else if (!c.active) {
            seedSessionName(c, sessionId)
            c.connect("attach", sessionId)
        }
    }

    /** Shows the known list name while the attach fetches the authoritative one. */
    private fun seedSessionName(controller: ChatController, sessionId: String) {
        if (controller.sessionName.isNotBlank()) return
        val name = sessions.firstOrNull { it.id == sessionId }?.name.orEmpty()
        if (name.isNotBlank()) controller.setSessionNameLocally(name)
    }

    // ---- servers ----

    fun saveServer(profile: ServerProfile, newKey: SshPrivateKey? = null) {
        // A profile referencing a key that failed to persist would be unusable.
        if (newKey != null && !saveSshKey(newKey)) return
        val rollback = servers.toList()
        // Key material lives only in the managed key store; profiles keep the id.
        val stripped =
            profile.copy(
                ssh = profile.ssh.copy(privateKey = "", privateKeyPassphrase = "")
            )
        val idx = servers.indexOfFirst { it.id == stripped.id }
        val previous = servers.getOrNull(idx)
        // The server editor never manages port forwards; keep them across edits.
        val merged =
            if (previous != null) stripped.copy(portForwards = previous.portForwards) else stripped
        if (idx >= 0) servers[idx] = merged else servers.add(merged)
        if (!persistServers(rollback)) return
        if (activeServerId.isBlank()) {
            selectServer(stripped.id)
        } else if (stripped.id == activeServerId && previous != merged) {
            resetActiveConnections()
            activeChatId = null
            loadSessionsForActive()
            syncPortForwards()
        }
    }

    // ---- managed SSH keys ----

    /** Adds or replaces a managed key; returns false when secure storage rejects the write. */
    fun saveSshKey(key: SshPrivateKey): Boolean {
        val rollback = sshKeys.toList()
        val idx = sshKeys.indexOfFirst { it.id == key.id }
        if (idx >= 0) sshKeys[idx] = key else sshKeys.add(key)
        return persistSshKeys(rollback)
    }

    /** Removes a key and clears it from every server that referenced it. */
    fun deleteSshKey(id: String) {
        val rollback = sshKeys.toList()
        sshKeys.removeAll { it.id == id }
        if (!persistSshKeys(rollback)) return
        var changed = false
        servers.forEachIndexed { index, server ->
            if (server.ssh.privateKeyId == id) {
                servers[index] = server.copy(ssh = server.ssh.copy(privateKeyId = ""))
                changed = true
            }
        }
        if (changed) store.saveServers(servers.toList())
    }

    /** Persists the key list, restoring [rollback] and reporting when storage fails. */
    private fun persistSshKeys(rollback: List<SshPrivateKey>): Boolean {
        return try {
            store.saveSshKeys(sshKeys.toList())
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            sshKeys.clear()
            sshKeys.addAll(rollback)
            toast(stringsProvider().sshKeyStorageFailed, Toast.Kind.Error)
            false
        }
    }

    /** Persists server metadata and credentials, restoring UI state on a secure-store failure. */
    private fun persistServers(rollback: List<ServerProfile>): Boolean {
        return try {
            store.saveServers(servers.toList())
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            servers.clear()
            servers.addAll(rollback)
            toast(stringsProvider().serverCredentialsStorageFailed, Toast.Kind.Error)
            false
        }
    }

    /** Injects the referenced managed key's material for connecting; never persisted. */
    private fun ServerProfile.withResolvedSshKey(): ServerProfile {
        if (!connectionMode.usesSsh || ssh.authentication != SshAuthentication.PrivateKey) {
            return this
        }
        if (ssh.privateKey.isNotBlank()) return this
        val key = sshKeys.firstOrNull { it.id == ssh.privateKeyId } ?: return this
        return copy(
            ssh = ssh.copy(privateKey = key.privateKey, privateKeyPassphrase = key.passphrase)
        )
    }

    fun deleteServer(id: String) {
        val rollback = servers.toList()
        servers.removeAll { it.id == id }
        if (!persistServers(rollback)) return
        store.clearLegacySessions(id)
        if (activeServerId == id) {
            resetActiveConnections()
            activeServerId = servers.firstOrNull()?.id ?: ""
            store.activeServerId = activeServerId
            loadSessionsForActive()
            syncPortForwards()
            activeChatId = null
        }
    }

    fun selectServer(id: String) {
        if (activeServerId == id) return
        resetActiveConnections()
        activeServerId = id
        store.activeServerId = id
        activeChatId = null
        loadSessionsForActive()
        syncPortForwards()
    }

    fun stopManagedGateway() {
        val server = activeServer?.takeIf { it.connectionMode.isManaged } ?: return
        sessionRefreshGeneration++
        viewModelScope.launch {
            try {
                transportFor(server).stopManagedGateway()
                if (activeServerId != server.id) return@launch
                resetActiveConnections()
                activeChatId = null
                workspaces.clear()
                sessions.clear()
                sessionsLoading = false
                toast(stringsProvider().managedGatewayStopped, Toast.Kind.Success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (activeServerId == server.id) {
                    toast(
                        stringsProvider().managedGatewayStopFailed(
                            failure.message ?: "unknown error",
                        ),
                        Toast.Kind.Error,
                    )
                }
            }
        }
    }

    /** Loads restart-scoped settings from the active gateway. */
    suspend fun loadGatewayRuntimeConfig(): GatewayRuntimeConfig {
        val server = activeServer ?: error("no active server")
        val gateway = transportFor(server).resolveGateway()
        return getGatewayRuntimeConfig(gateway, server.token)
    }

    /** Persists restart-scoped settings on the active gateway. */
    suspend fun saveGatewayRuntimeConfig(
        titleModel: String,
        piEnvironmentFile: String,
        piEnvironmentShell: String,
    ): GatewayRuntimeConfig {
        val server = activeServer ?: error("no active server")
        val gateway = transportFor(server).resolveGateway()
        return updateGatewayRuntimeConfig(
            gateway = gateway,
            token = server.token,
            titleModel = titleModel,
            piEnvironmentFile = piEnvironmentFile,
            piEnvironmentShell = piEnvironmentShell,
        )
    }

    /** Requests a graceful restart, waits for the supervisor, then reconnects. */
    suspend fun restartGatewayRuntime(): GatewayRuntimeConfig {
        val server = activeServer ?: error("no active server")
        val gateway = transportFor(server).resolveGateway()
        requestGatewayRuntimeRestart(gateway, server.token)
        if (activeServerId != server.id) error("active server changed")

        resetActiveConnections()
        activeChatId = null
        delay(250)
        val restartedGateway = transportFor(server).resolveGateway()
        val health = awaitManagedGatewayHealth(restartedGateway)
        if (activeServerId != server.id) error("active server changed")
        activeGatewayInfo = GatewayServerInfo(
            version = health.version,
            protocol = health.protocol,
            os = health.os,
            features = health.features.toSet(),
        )
        loadSessionsForActive(clearExisting = false)
        return getGatewayRuntimeConfig(restartedGateway, server.token)
    }

    private fun disconnectAll() {
        controllers.values.forEach { it.disconnect() }
    }

    private fun resetActiveConnections() {
        disconnectAll()
        controllers.clear()
        gatewayTransport?.close()
        gatewayTransport = null
        gatewayTransportServerId = ""
        forwardManager?.close()
        forwardManager = null
        forwardManagerServerId = ""
        providerAuthGeneration++
        providerAuthClient?.disconnect()
        providerAuthClient = null
        providerAuthFlow = null
        providers.clear()
        providersLoading = false
        providerLogoutId = null
        activeGatewayInfo = null
        loopbackLinkPrompt = null
        rejectPendingHostKey()
    }

    // ---- preferences ----

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        store.themeMode = mode
    }

    fun updateLanguage(lang: AppLanguage) {
        language = lang
        store.language = lang
    }

    /** Collapses or expands the wide-layout session list sidebar. */
    fun updateSidebarCollapsed(collapsed: Boolean) {
        sidebarCollapsed = collapsed
        store.sidebarCollapsed = collapsed
    }

    // ---- built-in providers ----

    fun refreshProviders() {
        val server = activeServer ?: return
        val generation = ++providerRefreshGeneration
        providersLoading = true
        viewModelScope.launch {
            try {
                val gateway = transportFor(server).resolveGateway()
                val loaded = listGatewayProviders(gateway, server.token)
                if (generation != providerRefreshGeneration || activeServerId != server.id) return@launch
                providers.clear()
                providers.addAll(
                    loaded.sortedWith(
                        compareByDescending<GatewayProvider> { it.configured }
                            .thenBy { it.name.lowercase() }
                            .thenBy { it.id },
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (generation == providerRefreshGeneration && activeServerId == server.id) {
                    toast(
                        stringsProvider().providerLoadFailed(failure.message ?: "unknown error"),
                        Toast.Kind.Error,
                    )
                }
            } finally {
                if (generation == providerRefreshGeneration) providersLoading = false
            }
        }
    }

    fun startProviderLogin(provider: GatewayProvider, method: GatewayProviderAuthMethod) {
        val server = activeServer ?: return
        val generation = ++providerAuthGeneration
        providerAuthClient?.disconnect()
        providerAuthFlow =
            ProviderAuthFlowState(
                providerId = provider.id,
                providerName = provider.name,
                authType = method.type,
                status = stringsProvider().providerConnecting,
            )
        viewModelScope.launch {
            try {
                val gateway = transportFor(server).resolveGateway()
                if (generation != providerAuthGeneration || activeServerId != server.id) return@launch
                val client = PiClient(viewModelScope)
                providerAuthClient = client
                client.connect(
                    buildProviderAuthWsUrl(gateway, server.token, provider.id, method.type),
                    object : PiClient.Listener {
                        override fun onOpen() = Unit

                        override fun onMessage(text: String) {
                            if (generation != providerAuthGeneration) return
                            val event = parseProviderAuthEvent(text) ?: return
                            handleProviderAuthEvent(generation, event)
                        }

                        override fun onBinary(bytes: ByteArray) = Unit

                        override fun onClose(code: Short, reason: String) {
                            if (generation != providerAuthGeneration) return
                            val current = providerAuthFlow ?: return
                            if (!current.completed && current.error.isBlank()) {
                                providerAuthFlow =
                                    current.copy(
                                        prompt = null,
                                        error = reason.ifBlank { stringsProvider().providerConnectionClosed },
                                    )
                            }
                        }

                        override fun onFailure(message: String) {
                            if (generation != providerAuthGeneration) return
                            providerAuthFlow = providerAuthFlow?.copy(prompt = null, error = message)
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (generation == providerAuthGeneration) {
                    providerAuthFlow =
                        providerAuthFlow?.copy(
                            prompt = null,
                            error = failure.message ?: "unknown error",
                        )
                }
            }
        }
    }

    private fun handleProviderAuthEvent(generation: Long, event: ProviderAuthEvent) {
        if (generation != providerAuthGeneration) return
        val current = providerAuthFlow ?: return
        when (event.event) {
            "ready" -> providerAuthFlow = current.copy(status = stringsProvider().providerWaitingForLogin)
            "prompt" -> {
                providerAuthFlow =
                    current.copy(
                        status = event.message,
                        prompt =
                            ProviderAuthPromptState(
                                id = event.id,
                                kind = event.kind,
                                message = event.message,
                                placeholder = event.placeholder,
                                options = event.options,
                                descriptions = event.descriptions,
                            ),
                    )
            }
            "info" -> {
                providerAuthFlow =
                    current.copy(
                        status = event.message,
                        links = event.links.map { it.url to it.label },
                    )
            }
            "auth_url" -> {
                providerAuthFlow =
                    current.copy(
                        status = event.instructions.ifBlank { stringsProvider().providerOpenAuthorization },
                        authorizationUrl = event.url,
                    )
            }
            "device_code" -> {
                providerAuthFlow =
                    current.copy(
                        status = stringsProvider().providerDeviceCodeHint,
                        authorizationUrl = event.verificationUri,
                        userCode = event.userCode,
                    )
            }
            "validating" ->
                providerAuthFlow =
                    current.copy(
                        status = stringsProvider().providerValidatingKey,
                        prompt = null,
                    )
            "progress" -> providerAuthFlow = current.copy(status = event.message, prompt = null)
            "complete" -> {
                providerAuthFlow =
                    current.copy(
                        status = stringsProvider().providerLoginSucceeded(current.providerName),
                        prompt = null,
                        completed = true,
                        error = "",
                    )
                providerAuthClient?.disconnect()
                providerAuthClient = null
                refreshAfterProviderMutation()
                toast(stringsProvider().providerLoginSucceeded(current.providerName), Toast.Kind.Success)
            }
            "error" -> {
                providerAuthFlow = current.copy(prompt = null, error = event.message)
                providerAuthClient?.disconnect()
                providerAuthClient = null
            }
        }
    }

    fun respondProviderAuth(value: String) {
        val flow = providerAuthFlow ?: return
        val prompt = flow.prompt ?: return
        if (providerAuthClient?.send(providerAuthInputResponse(prompt.id, value)) != true) return
        providerAuthFlow = flow.copy(prompt = null, status = stringsProvider().providerAuthenticating)
    }

    fun cancelProviderAuth() {
        val prompt = providerAuthFlow?.prompt
        if (prompt != null) providerAuthClient?.send(providerAuthCancelResponse(prompt.id))
        providerAuthGeneration++
        providerAuthClient?.disconnect()
        providerAuthClient = null
        providerAuthFlow = null
    }

    fun dismissProviderAuth() {
        val flow = providerAuthFlow ?: return
        if (!flow.completed && flow.error.isBlank()) return
        providerAuthGeneration++
        providerAuthClient?.disconnect()
        providerAuthClient = null
        providerAuthFlow = null
    }

    fun logoutProvider(provider: GatewayProvider) {
        if (providerLogoutId != null || provider.storedAuthType.isBlank()) return
        val server = activeServer ?: return
        providerLogoutId = provider.id
        viewModelScope.launch {
            try {
                val gateway = transportFor(server).resolveGateway()
                logoutGatewayProvider(gateway, server.token, provider.id)
                if (activeServerId != server.id) return@launch
                refreshAfterProviderMutation()
                toast(stringsProvider().providerLogoutSucceeded(provider.name), Toast.Kind.Success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (activeServerId == server.id) {
                    toast(
                        stringsProvider().providerLogoutFailed(failure.message ?: "unknown error"),
                        Toast.Kind.Error,
                    )
                }
            } finally {
                if (providerLogoutId == provider.id) providerLogoutId = null
            }
        }
    }

    private fun refreshAfterProviderMutation() {
        refreshProviders()
        controllers.values.forEach {
            if (it.isDraft) it.reloadCapabilities() else it.refreshModels()
        }
    }

    // ---- sessions ----

    /** Refreshes server-owned sessions without blanking the current list. */
    fun refreshSessions() {
        // Keep the indicator visible briefly so a fast LAN response still
        // reads as a completed refresh instead of a no-op flicker.
        if (!sessionsLoading) {
            loadSessionsForActive(clearExisting = false, minIndicatorMs = MIN_REFRESH_INDICATOR_MS)
        }
    }

    /**
     * iOS makes the app inactive while showing its first local-network permission alert.
     * Remember the in-flight load so a failure caused by that alert is not presented as
     * a server error.
     */
    fun onAppInactive() {
        if (sessionsLoading) {
            interruptedSessionRefreshGeneration = sessionRefreshGeneration
        }
    }

    /**
     * Restarts work interrupted while the app was away: a session load cut off
     * by an iOS system alert, and any chat socket the system or gateway
     * aborted while the app was suspended.
     */
    fun onAppActive() {
        val interruptedGeneration = interruptedSessionRefreshGeneration
        interruptedSessionRefreshGeneration = null
        if (
            interruptedGeneration != null &&
            interruptedGeneration == sessionRefreshGeneration &&
            activeServer != null
        ) {
            loadSessionsForActive(clearExisting = false)
        }
        controllers.values.forEach { it.reconnectIfDisconnected() }
    }

    private fun loadSessionsForActive(clearExisting: Boolean = true, minIndicatorMs: Long = 0) {
        val generation = ++sessionRefreshGeneration
        val server = activeServer
        if (clearExisting) {
            workspaces.clear()
            sessions.clear()
        }
        if (server == null) {
            sessionsLoading = false
            return
        }
        sessionsLoading = true
        val startedAt = nowMillis()
        viewModelScope.launch {
            if (generation != sessionRefreshGeneration || activeServerId != server.id) return@launch
            try {
                val gateway = transportFor(server).resolveGateway()
                val health =
                    try {
                        fetchGatewayHealth(gateway)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        null
                    }
                if (generation != sessionRefreshGeneration || activeServerId != server.id) return@launch
                activeGatewayInfo = health?.let {
                    GatewayServerInfo(
                        version = it.version,
                        protocol = it.protocol,
                        os = it.os,
                        features = it.features.toSet(),
                    )
                }
                val loaded = listGatewayWorkspaces(gateway, server.token)

                if (generation != sessionRefreshGeneration || activeServerId != server.id) return@launch
                workspaces.clear()
                workspaces.addAll(
                    loaded.map { workspace ->
                        workspace.copy(sessions = workspace.sessions.map(::mergeControllerStatus))
                    },
                )
                syncSessionsFromWorkspaces()
                if (interruptedSessionRefreshGeneration == generation) {
                    interruptedSessionRefreshGeneration = null
                }
                store.clearLegacySessions(server.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (!isApplicationActive()) {
                    interruptedSessionRefreshGeneration = generation
                }
                val interruptedBySystemAlert =
                    interruptedSessionRefreshGeneration == generation
                if (
                    generation == sessionRefreshGeneration &&
                    activeServerId == server.id &&
                    !interruptedBySystemAlert
                ) {
                    toast(
                        "Could not load workspaces: ${failure.message ?: "unknown error"}",
                        Toast.Kind.Error,
                    )
                }
            } finally {
                if (generation == sessionRefreshGeneration) {
                    val elapsed = nowMillis() - startedAt
                    if (elapsed < minIndicatorMs) delay(minIndicatorMs - elapsed)
                    // A newer load may have started during the delay.
                    if (generation == sessionRefreshGeneration) sessionsLoading = false
                }
            }
        }
    }

    fun loadMoreWorkspaceSessions(workspaceId: String) {
        val index = workspaces.indexOfFirst { it.id == workspaceId }
        val snapshot = workspaces.getOrNull(index) ?: return
        if (snapshot.sessionsLoading || snapshot.nextCursor.isBlank()) return
        val server = activeServer ?: return
        workspaces[index] = snapshot.copy(sessionsLoading = true)
        viewModelScope.launch {
            try {
                val gateway = transportFor(server).resolveGateway()
                val page = listGatewayWorkspaceSessions(
                    gateway = gateway,
                    token = server.token,
                    workspace = snapshot,
                    cursor = snapshot.nextCursor,
                )
                if (activeServerId != server.id) return@launch
                val currentIndex = workspaces.indexOfFirst { it.id == workspaceId }
                val current = workspaces.getOrNull(currentIndex) ?: return@launch
                val merged =
                    (current.sessions + page.sessions.map(::mergeControllerStatus))
                        .distinctBy { it.id }
                        .sortedWith(sessionActivityComparator)
                workspaces[currentIndex] = current.copy(
                    sessions = merged,
                    nextCursor = page.nextCursor,
                    sessionsLoading = false,
                )
                syncSessionsFromWorkspaces()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (activeServerId == server.id) {
                    val currentIndex = workspaces.indexOfFirst { it.id == workspaceId }
                    if (currentIndex >= 0) {
                        workspaces[currentIndex] = workspaces[currentIndex].copy(sessionsLoading = false)
                    }
                    toast(
                        "Could not load more sessions: ${failure.message ?: "unknown error"}",
                        Toast.Kind.Error,
                    )
                }
            }
        }
    }

    suspend fun addWorkspace(directory: String): WorkspaceSummary {
        val server = activeServer ?: throw FsListException("no active server")
        val gateway = transportFor(server).resolveGateway()
        val created = createGatewayWorkspace(gateway, server.token, directory)
        if (activeServerId == server.id) {
            val index = workspaces.indexOfFirst { it.id == created.id }
            if (index >= 0) {
                val current = workspaces[index]
                workspaces[index] = created.copy(
                    sessions = current.sessions,
                    nextCursor = current.nextCursor,
                    sessionCount = current.sessionCount,
                )
            } else {
                workspaces.add(created)
                moveActiveWorkspacesFirst()
            }
            syncSessionsFromWorkspaces()
        }
        return created
    }

    fun saveWorkspaceMetadata(workspaceId: String, name: String, additionalSystemPrompt: String) {
        val server = activeServer ?: return
        viewModelScope.launch {
            try {
                val gateway = transportFor(server).resolveGateway()
                val updated = updateGatewayWorkspace(
                    gateway,
                    server.token,
                    workspaceId,
                    name,
                    additionalSystemPrompt,
                )
                if (activeServerId != server.id) return@launch
                val index = workspaces.indexOfFirst { it.id == workspaceId }
                val current = workspaces.getOrNull(index) ?: return@launch
                workspaces[index] = updated.copy(
                    sessions = current.sessions,
                    nextCursor = current.nextCursor,
                    sessionCount = current.sessionCount,
                )
                moveActiveWorkspacesFirst()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (activeServerId == server.id) {
                    toast(
                        "Could not update workspace: ${failure.message ?: "unknown error"}",
                        Toast.Kind.Error,
                    )
                }
            }
        }
    }

    private fun syncSessionsFromWorkspaces() {
        sessions.clear()
        sessions.addAll(workspaces.flatMap { it.sessions }.distinctBy { it.id })
    }

    private fun addOrTouchSession(
        id: String,
        name: String = "",
        workspaceId: String = "",
        workspaceDirectory: String = "",
    ) {
        val previous = sessions.firstOrNull { it.id == id }
        val controller = controllers[id]
        val now = nowMillis()
        val resolvedWorkspaceId =
            workspaceId.ifBlank { previous?.workspaceId ?: controller?.workspaceId.orEmpty() }
        val resolvedDirectory =
            workspaceDirectory.ifBlank {
                previous?.workspaceDirectory ?: controller?.workDir.orEmpty()
            }
        val updated =
            previous?.copy(
                lastActive = now,
                name = name.ifBlank { previous.name },
                workspaceId = resolvedWorkspaceId,
                workspaceDirectory = resolvedDirectory,
                running = true,
            ) ?: SavedSession(
                id = id,
                name = name,
                createdAt = now,
                lastActive = now,
                workspaceId = resolvedWorkspaceId,
                workspaceDirectory = resolvedDirectory,
                running = true,
            )
        upsertWorkspaceSession(updated)
    }

    private fun upsertWorkspaceSession(session: SavedSession) {
        var workspaceIndex = workspaces.indexOfFirst { it.id == session.workspaceId }
        if (workspaceIndex < 0 && session.workspaceId.isNotBlank()) {
            workspaces.add(
                WorkspaceSummary(
                    id = session.workspaceId,
                    directory = session.workspaceDirectory,
                ),
            )
            workspaceIndex = workspaces.lastIndex
        }
        if (workspaceIndex < 0) return

        val workspace = workspaces[workspaceIndex]
        val existed = workspace.sessions.any { it.id == session.id }
        val updatedSessions =
            (workspace.sessions.filterNot { it.id == session.id } + session)
                .sortedWith(sessionActivityComparator)
        workspaces[workspaceIndex] = workspace.copy(
            sessions = updatedSessions,
            sessionCount = if (existed) workspace.sessionCount else workspace.sessionCount + 1,
        )
        moveActiveWorkspacesFirst()
        syncSessionsFromWorkspaces()
    }

    private fun removeWorkspaceSession(id: String) {
        val workspaceIndex = workspaces.indexOfFirst { workspace ->
            workspace.sessions.any { it.id == id }
        }
        if (workspaceIndex < 0) return
        val workspace = workspaces[workspaceIndex]
        workspaces[workspaceIndex] = workspace.copy(
            sessions = workspace.sessions.filterNot { it.id == id },
            sessionCount = (workspace.sessionCount - 1).coerceAtLeast(0),
        )
        moveActiveWorkspacesFirst()
        syncSessionsFromWorkspaces()
    }

    private fun moveActiveWorkspacesFirst() {
        val ordered = workspaces.sortedWith(
            compareByDescending<WorkspaceSummary> { workspace ->
                workspace.sessions.any { it.running }
            }.thenByDescending { workspace ->
                maxOf(
                    workspace.updatedAt,
                    workspace.sessions.maxOfOrNull { it.lastActive } ?: 0L,
                )
            }.thenBy { it.directory },
        )
        workspaces.clear()
        workspaces.addAll(ordered)
    }

    private val sessionActivityComparator =
        compareByDescending<SavedSession> { it.running }
            .thenByDescending { it.outputting }
            .thenByDescending { it.lastActive }
            .thenByDescending { it.createdAt }

    private fun mergeControllerStatus(session: SavedSession): SavedSession {
        val controller = controllers[session.id] ?: return session
        if (!controller.active) return session
        return session.copy(
            running = true,
            outputting = controller.isStreaming,
        )
    }

    private fun updateSessionStreaming(id: String, streaming: Boolean) {
        val session = sessions.firstOrNull { it.id == id } ?: return
        upsertWorkspaceSession(session.copy(running = true, outputting = streaming))
    }

    fun renameSession(id: String, name: String) {
        controllers[id]?.takeIf { it.isDraft }?.let {
            it.setSessionNameLocally(name)
            return
        }
        val previous = sessions.firstOrNull { it.id == id } ?: return
        upsertWorkspaceSession(previous.copy(name = name))
        controllers[id]?.setSessionNameLocally(name)
        val server = activeServer ?: return
        viewModelScope.launch {
            try {
                val gateway = transportFor(server).resolveGateway()
                val updated = renameGatewaySession(
                    gateway,
                    server.token,
                    previous.workspaceId,
                    previous.workspaceDirectory,
                    id,
                    name,
                )
                if (activeServerId != server.id) return@launch
                upsertWorkspaceSession(updated)
                controllers[id]?.setSessionNameLocally(updated.name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (activeServerId == server.id) {
                    val current = sessions.firstOrNull { it.id == id }
                    if (current?.name == name) {
                        upsertWorkspaceSession(previous)
                        controllers[id]?.setSessionNameLocally(previous.name)
                    }
                    toast(
                        "Could not rename session: ${failure.message ?: "unknown error"}",
                        Toast.Kind.Error,
                    )
                }
            }
        }
    }

    /** Stops only the live pi runtime; the saved chat remains attachable. */
    fun stopSessionProcess(id: String) {
        val session = sessions.firstOrNull { it.id == id } ?: return
        if (!session.running) return
        if (session.outputting || controllers[id]?.isStreaming == true) {
            toast(stringsProvider().stopPiProcessOutputtingHint)
            return
        }
        if (activeGatewayInfo?.supportsSessionProcessStop != true) {
            toast(stringsProvider().stopPiProcessUnsupported, Toast.Kind.Error)
            return
        }
        val server = activeServer ?: return
        viewModelScope.launch {
            try {
                val gateway = transportFor(server).resolveGateway()
                stopGatewaySessionProcess(gateway, server.token, session.workspaceId, id)
                if (activeServerId != server.id) return@launch
                controllers[id]?.disconnect()
                upsertWorkspaceSession(session.copy(running = false, outputting = false))
                toast(stringsProvider().piProcessStopped, Toast.Kind.Success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (activeServerId == server.id) {
                    toast(
                        stringsProvider().piProcessStopFailed(
                            failure.message ?: "unknown error",
                        ),
                        Toast.Kind.Error,
                    )
                    loadSessionsForActive(clearExisting = false)
                }
            }
        }
    }

    fun removeSession(id: String) {
        controllers[id]?.takeIf { it.isDraft }?.let { draft ->
            controllers.remove(id)
            draft.disconnect()
            if (activeChatId == id) activeChatId = null
            return
        }
        val removed = sessions.firstOrNull { it.id == id } ?: return
        removeWorkspaceSession(id)
        controllers.remove(id)?.disconnect()
        if (activeChatId == id) {
            activeChatId = null
        }
        val server = activeServer ?: return
        viewModelScope.launch {
            try {
                val gateway = transportFor(server).resolveGateway()
                deleteGatewaySession(gateway, server.token, removed.workspaceId, id)
                withContext(Dispatchers.Default) { clearEntryCache(server.id, id) }
                if (activeServerId == server.id) loadSessionsForActive(clearExisting = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (activeServerId == server.id) {
                    if (sessions.none { it.id == id }) {
                        upsertWorkspaceSession(removed)
                    }
                    toast(
                        "Could not delete session: ${failure.message ?: "unknown error"}",
                        Toast.Kind.Error,
                    )
                }
            }
        }
    }

    // ---- chat controllers ----

    fun controllerFor(sessionId: String): ChatController {
        return controllers.getOrPut(sessionId) {
            val server = activeServer ?: error("no active server")
            val transport = transportFor(server)
            ChatController(
                scope = viewModelScope,
                gateway = server.url,
                token = server.token,
                onToast = ::toast,
                onSessionReady = { sid, isNew, workspaceId, workspaceDirectory ->
                    addOrTouchSession(
                        sid,
                        workspaceId = workspaceId,
                        workspaceDirectory = workspaceDirectory,
                    )
                    if (activeChatId != sid && sessionId == activeChatId) {
                        // gateway assigned a fresh id for a create action
                        controllers.remove(sessionId)?.let { old ->
                            controllers[sid] = old
                        }
                        activeChatId = sid
                    }
                    loadSessionsForActive(clearExisting = false)
                },
                onSessionNameChanged = { sid, title ->
                    addOrTouchSession(sid, name = title)
                },
                onStreamingChanged = ::updateSessionStreaming,
                strings = {
                    val s = stringsProvider()
                    ChatController.ChatStrings(
                        commandRejected = s.commandRejected,
                        abortSent = s.abortSent,
                        compacting = s.compacting,
                        compacted = s.compacted,
                        retryOk = s.retryOk,
                        retryFailed = s.retryFailed,
                        agentDone = s.agentDone,
                        retrying = s.retrying,
                        notify = s.appName,
                        modelOptionsFailed = s.modelOptionsFailed,
                    )
                },
                loadCapabilities = { workspaceId ->
                    getGatewayCapabilities(transport.resolveGateway(), server.token, workspaceId)
                },
                resolveGateway = transport::resolveGateway,
                cacheNamespace = server.id,
            )
        }
    }

    /** Creates a local-only draft and returns its temporary route ID. */
    fun startNewChat(workspaceId: String): String {
        val workspace = workspaces.firstOrNull { it.id == workspaceId }
            ?: error("unknown workspace")
        activeServerId.takeIf { it.isNotBlank() }
            ?.let { store.saveLastWorkspaceId(it, workspace.id) }
        val tempId = "new-" + Random.nextLong().toString(16)
        openChat(
            tempId,
            isNew = true,
            workspaceId = workspace.id,
            workspaceDirectory = workspace.directory,
        )
        return tempId
    }

    /** Server-owned workspace selected for the previous new chat. */
    val lastWorkspaceId: String
        get() = activeServerId.takeIf { it.isNotBlank() }
            ?.let { store.lastWorkspaceId(it) }
            .orEmpty()

    /** Lists subdirectories of [path] on the active gateway for the workspace browser. */
    suspend fun listDirs(path: String): FsListResponse {
        val server = activeServer ?: throw FsListException("no active server")
        val gateway = transportFor(server).resolveGateway()
        return listGatewayDirs(gateway, server.token, path)
    }

    /** Creates [name] below [parent] on the active gateway for the workspace browser. */
    suspend fun createDir(parent: String, name: String) {
        val server = activeServer ?: throw FsListException("no active server")
        val gateway = transportFor(server).resolveGateway()
        createGatewayDir(gateway, server.token, parent, name)
    }

    // ---- file browser ----

    /** Lists files and subdirectories of [path] on the active gateway. */
    suspend fun listFiles(path: String): FileListResponse {
        val server = activeServer ?: throw FileApiException("no active server")
        val gateway = transportFor(server).resolveGateway()
        return listGatewayFiles(gateway, server.token, path)
    }

    /** Reads a text file on the active gateway for the preview sheet. */
    suspend fun readFile(path: String): FileReadResponse {
        val server = activeServer ?: throw FileApiException("no active server")
        val gateway = transportFor(server).resolveGateway()
        return readGatewayFile(gateway, server.token, path)
    }

    /** Downloads a file from the active gateway, e.g. an APK to install. */
    suspend fun downloadFile(path: String): ByteArray {
        val server = activeServer ?: throw FileApiException("no active server")
        val gateway = transportFor(server).resolveGateway()
        return downloadGatewayFile(gateway, server.token, path)
    }

    // ---- gateway transport / SSH host trust ----

    private fun transportFor(server: ServerProfile): GatewayTransport {
        gatewayTransport?.takeIf { gatewayTransportServerId == server.id }?.let { return it }
        gatewayTransport?.close()
        return GatewayTransport(
            profile = server.withResolvedSshKey(),
            confirmHostKey = ::confirmSshHostKey,
            onHostKeyTrusted = { fingerprint -> rememberTrustedHostKey(server.id, fingerprint) },
        ).also {
            gatewayTransport = it
            gatewayTransportServerId = server.id
        }
    }

    private suspend fun confirmSshHostKey(prompt: SshHostKeyPrompt): Boolean {
        val current = sshHostKeyDecision
        if (current != null) return current.await()

        val decision = CompletableDeferred<Boolean>()
        sshHostKeyDecision = decision
        sshHostKeyPrompt = prompt
        return try {
            decision.await()
        } finally {
            if (sshHostKeyDecision === decision) {
                sshHostKeyDecision = null
                sshHostKeyPrompt = null
            }
        }
    }

    fun answerSshHostKeyPrompt(trust: Boolean) {
        sshHostKeyPrompt = null
        sshHostKeyDecision?.complete(trust)
    }

    private fun rejectPendingHostKey() {
        sshHostKeyPrompt = null
        sshHostKeyDecision?.complete(false)
        sshHostKeyDecision = null
    }

    private fun rememberTrustedHostKey(serverId: String, fingerprint: String) {
        val index = servers.indexOfFirst { it.id == serverId }
        if (index < 0) return
        val server = servers[index]
        if (server.ssh.hostKeySha256 == fingerprint) return
        servers[index] = server.copy(ssh = server.ssh.copy(hostKeySha256 = fingerprint))
        store.saveServers(servers.toList())
    }

    // ---- port forwards ----

    val portForwardStatuses: Map<String, PortForwardStatus>
        get() = forwardManager?.statuses ?: emptyMap()

    private fun forwardManagerFor(server: ServerProfile): PortForwardManager {
        forwardManager?.takeIf { forwardManagerServerId == server.id }?.let { return it }
        forwardManager?.close()
        return PortForwardManager(
            profile = server.withResolvedSshKey(),
            forwardsProvider = {
                servers.firstOrNull { it.id == server.id }?.portForwards.orEmpty()
            },
            confirmHostKey = ::confirmSshHostKey,
            onHostKeyTrusted = { fingerprint -> rememberTrustedHostKey(server.id, fingerprint) },
        ).also {
            forwardManager = it
            forwardManagerServerId = server.id
        }
    }

    private fun sshActiveServer(): ServerProfile? =
        activeServer?.takeIf { it.connectionMode.usesSsh }

    private fun syncPortForwards() {
        val server = sshActiveServer() ?: return
        viewModelScope.launch { forwardManagerFor(server).sync() }
    }

    private fun updatePortForwards(server: ServerProfile, forwards: List<PortForward>) {
        val idx = servers.indexOfFirst { it.id == server.id }
        if (idx < 0) return
        servers[idx] = servers[idx].copy(portForwards = forwards)
        store.saveServers(servers.toList())
    }

    fun addPortForward(remoteHost: String, remotePort: Int) {
        val server = sshActiveServer() ?: return
        if (remotePort !in 1..65535) return
        val forward =
            PortForward(
                id = "pf-" + Random.nextLong().toString(16),
                remoteHost = remoteHost.trim().ifBlank { "127.0.0.1" },
                remotePort = remotePort,
            )
        updatePortForwards(server, server.portForwards + forward)
        viewModelScope.launch { forwardManagerFor(server).sync() }
    }

    fun updatePortForward(id: String, remoteHost: String, remotePort: Int) {
        val server = sshActiveServer() ?: return
        if (remotePort !in 1..65535) return
        updatePortForwards(
            server,
            server.portForwards.map {
                if (it.id == id) {
                    it.copy(
                        remoteHost = remoteHost.trim().ifBlank { "127.0.0.1" },
                        remotePort = remotePort,
                    )
                } else {
                    it
                }
            },
        )
        viewModelScope.launch { forwardManagerFor(server).sync() }
    }

    fun setPortForwardEnabled(id: String, enabled: Boolean) {
        val server = sshActiveServer() ?: return
        updatePortForwards(
            server,
            server.portForwards.map { if (it.id == id) it.copy(enabled = enabled) else it },
        )
        viewModelScope.launch { forwardManagerFor(server).sync() }
    }

    fun removePortForward(id: String) {
        val server = sshActiveServer() ?: return
        updatePortForwards(server, server.portForwards.filterNot { it.id == id })
        viewModelScope.launch { forwardManagerFor(server).sync() }
    }

    fun restartPortForward(id: String) {
        val server = sshActiveServer() ?: return
        viewModelScope.launch { forwardManagerFor(server).restart(id) }
    }

    /** Moves dead tunnels from running to failed; polled by the forwards screen. */
    fun refreshPortForwardStates() {
        forwardManager?.refreshStates()
    }

    /**
     * Intercepts loopback http(s) links from chat content. SSH servers reuse a
     * running forward or prompt to create one; direct servers open the link
     * against the gateway host instead of the device itself.
     * Returns true when the link was consumed.
     */
    fun handleLoopbackLink(uri: String, openUrl: (String) -> Unit): Boolean {
        val target = loopbackUrlTarget(uri) ?: return false
        val server = activeServer ?: return false
        return when (server.connectionMode) {
            ServerConnectionMode.Ssh,
            ServerConnectionMode.ManagedSsh,
            -> {
                val localPort = forwardManagerFor(server).runningLocalPortFor(target.second)
                if (localPort != null) {
                    openUrl(rewriteLoopbackUrl(uri, localPort))
                } else {
                    loopbackLinkPrompt = LoopbackLinkPrompt(uri, target.second, openUrl)
                }
                true
            }
            ServerConnectionMode.Direct -> {
                val gatewayHost =
                    runCatching { gatewayTarget(server.url, requirePlaintext = false).remoteHost }
                        .getOrNull()
                        ?: return false
                openUrl(rewriteLoopbackUrlToGatewayHost(uri, gatewayHost))
                true
            }
        }
    }

    fun answerLoopbackLinkPrompt(mapAndOpen: Boolean) {
        val prompt = loopbackLinkPrompt ?: return
        loopbackLinkPrompt = null
        if (!mapAndOpen) {
            prompt.openUrl(prompt.originalUrl)
            return
        }
        val server = sshActiveServer() ?: return
        viewModelScope.launch {
            val forward = ensureLoopbackForward(server, prompt.remotePort)
            val manager = forwardManagerFor(server)
            val localPort = manager.ensureRunning(forward.id)
            if (localPort != null) {
                prompt.openUrl(rewriteLoopbackUrl(prompt.originalUrl, localPort))
            } else {
                toast(
                    manager.statuses[forward.id]?.error
                        ?: stringsProvider().portForwardFailed,
                    Toast.Kind.Error,
                )
            }
        }
    }

    fun dismissLoopbackLinkPrompt() {
        loopbackLinkPrompt = null
    }

    private fun ensureLoopbackForward(server: ServerProfile, remotePort: Int): PortForward {
        val existing = server.portForwards.firstOrNull {
            it.remotePort == remotePort && isLoopbackHostName(it.remoteHost)
        }
        if (existing != null) {
            if (!existing.enabled) {
                updatePortForwards(
                    server,
                    server.portForwards.map {
                        if (it.id == existing.id) it.copy(enabled = true) else it
                    },
                )
            }
            return existing.copy(enabled = true)
        }
        val forward =
            PortForward(
                id = "pf-" + Random.nextLong().toString(16),
                remoteHost = "127.0.0.1",
                remotePort = remotePort,
            )
        updatePortForwards(server, server.portForwards + forward)
        return forward
    }

    // ---- toasts ----

    fun toast(text: String, kind: Toast.Kind = Toast.Kind.Info) {
        if (text.isBlank()) return
        val t = Toast(Random.nextLong(), text, kind)
        toasts.add(t)
        viewModelScope.launch {
            delay(4000)
            toasts.remove(t)
        }
    }

    override fun onCleared() {
        resetActiveConnections()
    }
}
