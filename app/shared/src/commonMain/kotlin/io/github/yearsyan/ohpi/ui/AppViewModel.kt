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
import io.github.yearsyan.ohpi.data.ThemeMode
import io.github.yearsyan.ohpi.i18n.Strings
import io.github.yearsyan.ohpi.net.FileApiException
import io.github.yearsyan.ohpi.net.FileListResponse
import io.github.yearsyan.ohpi.net.FileReadResponse
import io.github.yearsyan.ohpi.net.FsListException
import io.github.yearsyan.ohpi.net.FsListResponse
import io.github.yearsyan.ohpi.net.GatewayTransport
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
import io.github.yearsyan.ohpi.net.gatewayTarget
import io.github.yearsyan.ohpi.net.isLoopbackHostName
import io.github.yearsyan.ohpi.net.deleteGatewaySession
import io.github.yearsyan.ohpi.net.downloadGatewayFile
import io.github.yearsyan.ohpi.net.getGatewayCapabilities
import io.github.yearsyan.ohpi.net.listGatewayProviders
import io.github.yearsyan.ohpi.net.listGatewayDirs
import io.github.yearsyan.ohpi.net.listGatewayFiles
import io.github.yearsyan.ohpi.net.listGatewaySessions
import io.github.yearsyan.ohpi.net.logoutGatewayProvider
import io.github.yearsyan.ohpi.net.nowMillis
import io.github.yearsyan.ohpi.net.readGatewayFile
import io.github.yearsyan.ohpi.net.renameGatewaySession
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

class AppViewModel(
    private val store: SettingsStore = SettingsStore(),
) : ViewModel() {

    // ---- persisted state ----
    var servers = mutableStateListOf<ServerProfile>(); private set
    var activeServerId by mutableStateOf(""); private set
    var themeMode by mutableStateOf(ThemeMode.System); private set
    var language by mutableStateOf(AppLanguage.System); private set
    var sessions = mutableStateListOf<SavedSession>(); private set
    var providers = mutableStateListOf<GatewayProvider>(); private set

    // ---- runtime state ----
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
    private var providerRefreshGeneration = 0L
    private var providerAuthGeneration = 0L
    private var providerAuthClient: PiClient? = null
    private var stringsProvider: () -> Strings = { io.github.yearsyan.ohpi.i18n.EnStrings }

    private companion object {
        const val MIN_REFRESH_INDICATOR_MS = 600L
    }

    init {
        servers.addAll(store.loadServers())
        activeServerId = store.activeServerId
        themeMode = store.themeMode
        language = store.language
        loadSessionsForActive()
        syncPortForwards()
    }

    fun setStringsProvider(provider: () -> Strings) {
        stringsProvider = provider
    }

    val activeServer: ServerProfile? get() = servers.firstOrNull { it.id == activeServerId }
    val hasServers: Boolean get() = servers.isNotEmpty()

    /** Selects a chat, preparing new chats locally and attaching saved sessions. */
    fun openChat(sessionId: String, isNew: Boolean = false, workDir: String = "") {
        activeChatId = sessionId
        val c = controllerFor(sessionId)
        if (isNew) {
            c.prepareCreate(workDir)
        } else if (!c.active) {
            seedSessionName(c, sessionId)
            c.connect("attach", sessionId)
        }
    }

    fun selectChatWide(sessionId: String?, isNew: Boolean = false, workDir: String = "") {
        activeChatId = sessionId
        sessionId ?: return
        val c = controllerFor(sessionId)
        if (isNew) {
            c.prepareCreate(workDir)
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

    fun saveServer(profile: ServerProfile) {
        val idx = servers.indexOfFirst { it.id == profile.id }
        val previous = servers.getOrNull(idx)
        // The server editor never manages port forwards; keep them across edits.
        val merged =
            if (previous != null) profile.copy(portForwards = previous.portForwards) else profile
        if (idx >= 0) servers[idx] = merged else servers.add(merged)
        store.saveServers(servers.toList())
        if (activeServerId.isBlank()) {
            selectServer(profile.id)
        } else if (profile.id == activeServerId && previous != merged) {
            resetActiveConnections()
            activeChatId = null
            loadSessionsForActive()
            syncPortForwards()
        }
    }

    fun deleteServer(id: String) {
        servers.removeAll { it.id == id }
        store.saveServers(servers.toList())
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
        controllers.values.filter { it.isDraft }.forEach { it.reloadCapabilities() }
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

    private fun loadSessionsForActive(clearExisting: Boolean = true, minIndicatorMs: Long = 0) {
        val generation = ++sessionRefreshGeneration
        val server = activeServer
        if (clearExisting) sessions.clear()
        if (server == null) {
            sessionsLoading = false
            return
        }
        sessionsLoading = true
        val startedAt = nowMillis()
        val legacySessions = store.loadLegacySessions(server.id)
        viewModelScope.launch {
            if (generation != sessionRefreshGeneration || activeServerId != server.id) return@launch
            try {
                val gateway = transportFor(server).resolveGateway()
                var loaded = listGatewaySessions(gateway, server.token)

                // Releases before the server list API kept titles locally. Migrate
                // matching titles once, then discard the obsolete local list.
                var migrationComplete = true
                if (legacySessions.isNotEmpty()) {
                    val legacyById = legacySessions.associateBy { it.id }
                    loaded = loaded.map { remote ->
                        val legacyName = legacyById[remote.id]?.name.orEmpty()
                        if (remote.name.isBlank() && legacyName.isNotBlank()) {
                            try {
                                renameGatewaySession(gateway, server.token, remote.id, legacyName)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                migrationComplete = false
                                remote
                            }
                        } else {
                            remote
                        }
                    }
                }

                if (generation != sessionRefreshGeneration || activeServerId != server.id) return@launch
                sessions.clear()
                sessions.addAll(
                    loaded
                        .map(::mergeControllerStatus)
                        .sortedByDescending { it.createdAt },
                )
                if (migrationComplete) store.clearLegacySessions(server.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (generation == sessionRefreshGeneration && activeServerId == server.id) {
                    toast(
                        "Could not load sessions: ${failure.message ?: "unknown error"}",
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

    private fun addOrTouchSession(id: String, name: String = "", workDir: String = "") {
        val idx = sessions.indexOfFirst { it.id == id }
        val now = nowMillis()
        if (idx >= 0) {
            val s = sessions[idx]
            sessions[idx] = s.copy(
                lastActive = now,
                name = name.ifBlank { s.name },
                workDir = workDir.ifBlank { s.workDir },
                running = true,
            )
            sessions.sortByDescending { it.createdAt }
        } else {
            sessions.add(
                SavedSession(
                    id = id,
                    name = name,
                    createdAt = now,
                    lastActive = now,
                    workDir = workDir,
                    running = true,
                ),
            )
            sessions.sortByDescending { it.createdAt }
        }
    }

    private fun mergeControllerStatus(session: SavedSession): SavedSession {
        val controller = controllers[session.id] ?: return session
        if (!controller.active) return session
        return session.copy(
            running = true,
            outputting = controller.isStreaming,
        )
    }

    private fun updateSessionStreaming(id: String, streaming: Boolean) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) return
        sessions[index] = sessions[index].copy(running = true, outputting = streaming)
    }

    fun renameSession(id: String, name: String) {
        controllers[id]?.takeIf { it.isDraft }?.let {
            it.setSessionNameLocally(name)
            return
        }
        val idx = sessions.indexOfFirst { it.id == id }
        val previous = sessions.getOrNull(idx)
        if (idx >= 0) {
            sessions[idx] = sessions[idx].copy(name = name)
        }
        controllers[id]?.setSessionNameLocally(name)
        val server = activeServer ?: return
        viewModelScope.launch {
            try {
                val gateway = transportFor(server).resolveGateway()
                val updated = renameGatewaySession(gateway, server.token, id, name)
                if (activeServerId != server.id) return@launch
                val current = sessions.indexOfFirst { it.id == id }
                if (current >= 0) sessions[current] = updated
                controllers[id]?.setSessionNameLocally(updated.name)
                sessions.sortByDescending { it.createdAt }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (activeServerId == server.id) {
                    val current = sessions.indexOfFirst { it.id == id }
                    if (current >= 0 && sessions[current].name == name && previous != null) {
                        sessions[current] = previous
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

    fun removeSession(id: String) {
        controllers[id]?.takeIf { it.isDraft }?.let { draft ->
            controllers.remove(id)
            draft.disconnect()
            if (activeChatId == id) activeChatId = null
            return
        }
        val removed = sessions.firstOrNull { it.id == id }
        sessions.removeAll { it.id == id }
        controllers.remove(id)?.disconnect()
        if (activeChatId == id) {
            activeChatId = null
        }
        val server = activeServer ?: return
        viewModelScope.launch {
            try {
                val gateway = transportFor(server).resolveGateway()
                deleteGatewaySession(gateway, server.token, id)
                withContext(Dispatchers.Default) { clearEntryCache(server.id, id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (activeServerId == server.id) {
                    if (removed != null && sessions.none { it.id == id }) {
                        sessions.add(removed)
                        sessions.sortByDescending { it.createdAt }
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
                onSessionReady = { sid, isNew, workDir ->
                    addOrTouchSession(sid, workDir = workDir)
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
                loadCapabilities = { workDir ->
                    getGatewayCapabilities(transport.resolveGateway(), server.token, workDir)
                },
                resolveGateway = transport::resolveGateway,
                cacheNamespace = server.id,
            )
        }
    }

    /** Creates a local-only draft and returns its temporary route ID. */
    fun startNewChat(workDir: String = ""): String {
        activeServerId.takeIf { it.isNotBlank() }?.let { store.saveLastWorkspace(it, workDir) }
        val tempId = "new-" + Random.nextLong().toString(16)
        openChat(tempId, isNew = true, workDir = workDir)
        return tempId
    }

    /** Workspace used for the previous new chat on the active server (blank = gateway start dir). */
    val lastWorkspace: String
        get() = activeServerId.takeIf { it.isNotBlank() }?.let { store.lastWorkspace(it) } ?: ""

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
            profile = server,
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
            profile = server,
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
        activeServer?.takeIf { it.connectionMode == ServerConnectionMode.Ssh }

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
            ServerConnectionMode.Ssh -> {
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
