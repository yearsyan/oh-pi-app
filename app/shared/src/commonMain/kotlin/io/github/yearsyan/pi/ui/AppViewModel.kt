package io.github.yearsyan.pi.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yearsyan.pi.chat.ChatController
import io.github.yearsyan.pi.chat.Toast
import io.github.yearsyan.pi.chat.clearEntryCache
import io.github.yearsyan.pi.data.AppLanguage
import io.github.yearsyan.pi.data.SavedSession
import io.github.yearsyan.pi.data.ServerProfile
import io.github.yearsyan.pi.data.SettingsStore
import io.github.yearsyan.pi.data.ThemeMode
import io.github.yearsyan.pi.i18n.Strings
import io.github.yearsyan.pi.net.FileApiException
import io.github.yearsyan.pi.net.FileListResponse
import io.github.yearsyan.pi.net.FileReadResponse
import io.github.yearsyan.pi.net.FsListException
import io.github.yearsyan.pi.net.FsListResponse
import io.github.yearsyan.pi.net.GatewayTransport
import io.github.yearsyan.pi.net.SshHostKeyPrompt
import io.github.yearsyan.pi.net.deleteGatewaySession
import io.github.yearsyan.pi.net.downloadGatewayFile
import io.github.yearsyan.pi.net.getGatewayCapabilities
import io.github.yearsyan.pi.net.listGatewayDirs
import io.github.yearsyan.pi.net.listGatewayFiles
import io.github.yearsyan.pi.net.listGatewaySessions
import io.github.yearsyan.pi.net.nowMillis
import io.github.yearsyan.pi.net.readGatewayFile
import io.github.yearsyan.pi.net.renameGatewaySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class AppViewModel(
    private val store: SettingsStore = SettingsStore(),
) : ViewModel() {

    // ---- persisted state ----
    var servers = mutableStateListOf<ServerProfile>(); private set
    var activeServerId by mutableStateOf(""); private set
    var themeMode by mutableStateOf(ThemeMode.System); private set
    var language by mutableStateOf(AppLanguage.System); private set
    var sessions = mutableStateListOf<SavedSession>(); private set

    // ---- runtime state ----
    var activeChatId by mutableStateOf<String?>(null); private set
    var sessionsLoading by mutableStateOf(false); private set
    var sshHostKeyPrompt by mutableStateOf<SshHostKeyPrompt?>(null); private set
    val toasts = mutableStateListOf<Toast>()

    private val controllers = HashMap<String, ChatController>()
    private var gatewayTransport: GatewayTransport? = null
    private var gatewayTransportServerId = ""
    private var sshHostKeyDecision: CompletableDeferred<Boolean>? = null
    private var sessionRefreshGeneration = 0L
    private var stringsProvider: () -> Strings = { io.github.yearsyan.pi.i18n.EnStrings }

    private companion object {
        const val MIN_REFRESH_INDICATOR_MS = 600L
    }

    init {
        servers.addAll(store.loadServers())
        activeServerId = store.activeServerId
        themeMode = store.themeMode
        language = store.language
        loadSessionsForActive()
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
        if (idx >= 0) servers[idx] = profile else servers.add(profile)
        store.saveServers(servers.toList())
        if (activeServerId.isBlank()) {
            selectServer(profile.id)
        } else if (profile.id == activeServerId && previous != profile) {
            resetActiveConnections()
            activeChatId = null
            loadSessionsForActive()
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
                onAutoName = { sid, title ->
                    val existing = sessions.firstOrNull { it.id == sid }
                    if (existing == null || existing.name.isBlank()) {
                        addOrTouchSession(sid, name = title)
                    }
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
                        turnStart = s.turnStart,
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
