package io.github.yearsyan.pi.data

import com.russhwolf.settings.Settings
import kotlinx.serialization.json.Json

expect fun createSettings(): Settings

private val storeJson = Json { ignoreUnknownKeys = true }

/** Persists server profiles, UI preferences and per-server session lists. */
class SettingsStore(private val settings: Settings = createSettings()) {

    fun loadServers(): List<ServerProfile> =
        decodeList(settings.getString(KEY_SERVERS, ""))

    fun saveServers(servers: List<ServerProfile>) {
        settings.putString(KEY_SERVERS, encode(servers))
    }

    var activeServerId: String
        get() = settings.getString(KEY_ACTIVE_SERVER, "")
        set(value) = settings.putString(KEY_ACTIVE_SERVER, value)

    var themeMode: ThemeMode
        get() = settings.getString(KEY_THEME, ThemeMode.System.name)
            .let { v -> ThemeMode.entries.firstOrNull { it.name == v } ?: ThemeMode.System }
        set(value) = settings.putString(KEY_THEME, value.name)

    var language: AppLanguage
        get() = settings.getString(KEY_LANGUAGE, AppLanguage.System.name)
            .let { v -> AppLanguage.entries.firstOrNull { it.name == v } ?: AppLanguage.System }
        set(value) = settings.putString(KEY_LANGUAGE, value.name)

    var lastSessionId: String
        get() = settings.getString(KEY_LAST_SESSION, "")
        set(value) = settings.putString(KEY_LAST_SESSION, value)

    fun loadSessions(serverId: String): List<SavedSession> =
        decodeList(settings.getString("$KEY_SESSIONS$serverId", ""))

    fun saveSessions(serverId: String, sessions: List<SavedSession>) {
        settings.putString("$KEY_SESSIONS$serverId", encode(sessions))
    }

    /** Last workspace the user picked for new chats on this server (blank = gateway default). */
    fun lastWorkspace(serverId: String): String =
        settings.getString("$KEY_LAST_WORKSPACE$serverId", "")

    fun saveLastWorkspace(serverId: String, workDir: String) {
        settings.putString("$KEY_LAST_WORKSPACE$serverId", workDir)
    }

    private inline fun <reified T> decodeList(raw: String): List<T> {
        if (raw.isBlank()) return emptyList()
        return runCatching { storeJson.decodeFromString<List<T>>(raw) }.getOrDefault(emptyList())
    }

    private inline fun <reified T> encode(value: T): String = storeJson.encodeToString(value)

    companion object {
        private const val KEY_SERVERS = "servers"
        private const val KEY_ACTIVE_SERVER = "active_server"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_LAST_SESSION = "last_session"
        private const val KEY_SESSIONS = "sessions_"
        private const val KEY_LAST_WORKSPACE = "last_workspace_"
    }
}
