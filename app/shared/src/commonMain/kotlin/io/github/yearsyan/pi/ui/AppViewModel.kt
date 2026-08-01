package io.github.yearsyan.pi.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yearsyan.pi.chat.ChatController
import io.github.yearsyan.pi.chat.Toast
import io.github.yearsyan.pi.data.AppLanguage
import io.github.yearsyan.pi.data.SavedSession
import io.github.yearsyan.pi.data.ServerProfile
import io.github.yearsyan.pi.data.SettingsStore
import io.github.yearsyan.pi.data.ThemeMode
import io.github.yearsyan.pi.i18n.Strings
import io.github.yearsyan.pi.net.FsListException
import io.github.yearsyan.pi.net.FsListResponse
import io.github.yearsyan.pi.net.listGatewayDirs
import io.github.yearsyan.pi.net.nowMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val toasts = mutableStateListOf<Toast>()

    private val controllers = HashMap<String, ChatController>()
    private var stringsProvider: () -> Strings = { io.github.yearsyan.pi.i18n.EnStrings }

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

    /** Selects a chat and starts or attaches its gateway session. */
    fun openChat(sessionId: String, isNew: Boolean = false, workDir: String = "") {
        activeChatId = sessionId
        val c = controllerFor(sessionId)
        if (isNew) c.connect("create", null, workDir) else if (!c.active) c.connect("attach", sessionId)
    }

    fun selectChatWide(sessionId: String?, isNew: Boolean = false, workDir: String = "") {
        activeChatId = sessionId
        sessionId ?: return
        val c = controllerFor(sessionId)
        if (isNew) c.connect("create", null, workDir) else if (!c.active) c.connect("attach", sessionId)
    }

    // ---- servers ----

    fun saveServer(profile: ServerProfile) {
        val idx = servers.indexOfFirst { it.id == profile.id }
        if (idx >= 0) servers[idx] = profile else servers.add(profile)
        store.saveServers(servers.toList())
        if (activeServerId.isBlank()) selectServer(profile.id)
    }

    fun deleteServer(id: String) {
        servers.removeAll { it.id == id }
        store.saveServers(servers.toList())
        store.saveSessions(id, emptyList())
        if (activeServerId == id) {
            disconnectAll()
            activeServerId = servers.firstOrNull()?.id ?: ""
            store.activeServerId = activeServerId
            loadSessionsForActive()
            activeChatId = null
        }
    }

    fun selectServer(id: String) {
        if (activeServerId == id) return
        disconnectAll()
        controllers.clear()
        activeServerId = id
        store.activeServerId = id
        activeChatId = null
        loadSessionsForActive()
    }

    private fun disconnectAll() {
        controllers.values.forEach { it.disconnect() }
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

    private fun loadSessionsForActive() {
        sessions.clear()
        activeServerId.takeIf { it.isNotBlank() }?.let {
            sessions.addAll(store.loadSessions(it).sortedByDescending { s -> s.lastActive })
        }
    }

    fun addOrTouchSession(id: String, name: String = "", workDir: String = "") {
        val idx = sessions.indexOfFirst { it.id == id }
        val now = nowMillis()
        if (idx >= 0) {
            val s = sessions[idx]
            sessions[idx] = s.copy(
                lastActive = now,
                name = name.ifBlank { s.name },
                workDir = workDir.ifBlank { s.workDir },
            )
            sessions.sortByDescending { it.lastActive }
        } else {
            sessions.add(0, SavedSession(id, name, now, now, workDir))
        }
        persistSessions()
    }

    fun renameSession(id: String, name: String) {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx >= 0) {
            sessions[idx] = sessions[idx].copy(name = name)
            persistSessions()
        }
        controllers[id]?.renameSession(name)
    }

    fun removeSession(id: String) {
        sessions.removeAll { it.id == id }
        persistSessions()
        controllers.remove(id)?.disconnect()
        if (activeChatId == id) {
            activeChatId = null
        }
    }

    private fun persistSessions() {
        activeServerId.takeIf { it.isNotBlank() }?.let { store.saveSessions(it, sessions.toList()) }
    }

    // ---- chat controllers ----

    fun controllerFor(sessionId: String): ChatController {
        return controllers.getOrPut(sessionId) {
            val server = activeServer ?: error("no active server")
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
                },
                onAutoName = { sid, title ->
                    val existing = sessions.firstOrNull { it.id == sid }
                    if (existing == null || existing.name.isBlank()) {
                        addOrTouchSession(sid, name = title)
                    }
                },
                strings = {
                    val s = stringsProvider()
                    ChatController.ChatStrings(
                        newSessionCreated = s.newSessionCreated,
                        sessionAttached = s.sessionAttached,
                        piCrashed = s.piCrashed,
                        connectionClosed = s.connectionClosed,
                        commandRejected = s.commandRejected,
                        abortSent = s.abortSent,
                        compacting = s.compacting,
                        compacted = s.compacted,
                        retryOk = s.retryOk,
                        retryFailed = s.retryFailed,
                        agentDone = s.agentDone,
                        turnStart = s.turnStart,
                        notify = s.appName,
                    )
                },
            )
        }
    }

    /** Creates a brand-new session entry point and returns its temporary route ID. */
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
        return listGatewayDirs(server.url, server.token, path)
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
        controllers.values.forEach { it.disconnect() }
        controllers.clear()
    }
}
