package io.github.yearsyan.ohpi.data

import com.russhwolf.settings.Settings
import kotlinx.serialization.json.Json

expect fun createSettings(): Settings

/** Storage backend for the managed SSH key list; iOS keeps it in the system Keychain. */
internal interface SshKeyStorage {
    fun read(): String

    /** Persists the blob; implementations may throw on unrecoverable write failures. */
    fun write(value: String)
}

internal expect fun createSshKeyStorage(settings: Settings): SshKeyStorage

/** Plain settings-backed storage shared by platforms without a secure enclave store. */
internal class SettingsSshKeyStorage(private val settings: Settings) : SshKeyStorage {
    override fun read(): String = settings.getString(KEY_SSH_KEYS, "")

    override fun write(value: String) {
        settings.putString(KEY_SSH_KEYS, value)
    }
}

private const val KEY_SSH_KEYS = "ssh_keys"

private val storeJson = Json { ignoreUnknownKeys = true }

/** Persists server profiles, UI preferences and legacy session-list migration data. */
class SettingsStore(private val settings: Settings = createSettings()) {
    private val sshKeyStorage: SshKeyStorage = createSshKeyStorage(settings)

    fun loadServers(): List<ServerProfile> =
        decodeList(settings.getString(KEY_SERVERS, ""))

    fun saveServers(servers: List<ServerProfile>) {
        settings.putString(KEY_SERVERS, encode(servers))
    }

    /** Centrally managed SSH private keys; one key can be referenced by many servers. */
    fun loadSshKeys(): List<SshPrivateKey> =
        decodeList(sshKeyStorage.read())

    fun saveSshKeys(keys: List<SshPrivateKey>) {
        sshKeyStorage.write(encode(keys))
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

    /** Wide-layout session list collapsed to a slim rail. */
    var sidebarCollapsed: Boolean
        get() = settings.getBoolean(KEY_SIDEBAR_COLLAPSED, false)
        set(value) = settings.putBoolean(KEY_SIDEBAR_COLLAPSED, value)

    var lastSessionId: String
        get() = settings.getString(KEY_LAST_SESSION, "")
        set(value) = settings.putString(KEY_LAST_SESSION, value)

    fun loadLegacySessions(serverId: String): List<SavedSession> =
        decodeList(settings.getString("$KEY_SESSIONS$serverId", ""))

    fun clearLegacySessions(serverId: String) {
        settings.remove("$KEY_SESSIONS$serverId")
    }

    /** Last server-owned workspace selected for a new chat. */
    fun lastWorkspaceId(serverId: String): String =
        settings.getString("$KEY_LAST_WORKSPACE_ID$serverId", "")

    fun saveLastWorkspaceId(serverId: String, workspaceId: String) {
        settings.putString("$KEY_LAST_WORKSPACE_ID$serverId", workspaceId)
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
        private const val KEY_SIDEBAR_COLLAPSED = "sidebar_collapsed"
        private const val KEY_LAST_SESSION = "last_session"
        private const val KEY_SESSIONS = "sessions_" // pre-server-list releases
        private const val KEY_LAST_WORKSPACE_ID = "last_workspace_id_"
    }
}
