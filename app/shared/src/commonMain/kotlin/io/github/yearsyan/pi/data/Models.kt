package io.github.yearsyan.pi.data

import kotlinx.serialization.Serializable

/** A configured pi2ws gateway the app can connect to. */
@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val url: String,
    val token: String,
) {
    val displayName: String get() = name.ifBlank { url.substringAfter("://").substringBefore("/") }
}

/** A session remembered locally for a given server (the gateway has no list API). */
@Serializable
data class SavedSession(
    val id: String,
    val name: String = "",
    val createdAt: Long = 0L,
    val lastActive: Long = 0L,
    /** Workspace (pi working directory) reported by the gateway; blank = unknown/default. */
    val workDir: String = "",
)

enum class ThemeMode { System, Light, Dark }

enum class AppLanguage { System, English, Chinese }

enum class ConnState { Disconnected, Connecting, Ready, Error }
