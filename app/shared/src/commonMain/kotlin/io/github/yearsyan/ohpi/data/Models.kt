package io.github.yearsyan.ohpi.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class ServerConnectionMode {
    Direct,
    Ssh,
    /** The app installs and supervises the gateway through the SSH account. */
    ManagedSsh,
    ;

    val usesSsh: Boolean
        get() = this != Direct

    val isManaged: Boolean
        get() = this == ManagedSsh
}

@Serializable
enum class SshAuthentication { Password, PrivateKey }

/** SSH endpoint and credentials used to reach a gateway on the remote host. */
@Serializable
data class SshServerProfile(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authentication: SshAuthentication = SshAuthentication.Password,
    val password: String = "",
    /** Id of a centrally managed private key; one key can serve many machines. */
    val privateKeyId: String = "",
    /** Explicitly trusted OpenSSH SHA-256 fingerprint, for example SHA256:abc. */
    val hostKeySha256: String = "",
    /** Key material resolved from the managed key store at connect time; never persisted here. */
    @Transient val privateKey: String = "",
    @Transient val privateKeyPassphrase: String = "",
)

/** A private key managed by the app and shareable across multiple servers. */
@Serializable
data class SshPrivateKey(
    val id: String,
    val name: String,
    /** Private key contents in OpenSSH or PEM form; no filesystem path is used. */
    val privateKey: String,
    val passphrase: String = "",
)

/** A remote TCP port exposed on the device's loopback through the SSH connection. */
@Serializable
data class PortForward(
    val id: String,
    /** Host resolved by the SSH server for the forward target; usually 127.0.0.1. */
    val remoteHost: String = "127.0.0.1",
    val remotePort: Int,
    val enabled: Boolean = true,
)

/** A configured ohpi gateway the app can connect to. */
@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val url: String,
    val token: String,
    val connectionMode: ServerConnectionMode = ServerConnectionMode.Direct,
    val ssh: SshServerProfile = SshServerProfile(),
    val portForwards: List<PortForward> = emptyList(),
) {
    val displayName: String get() = name.ifBlank { url.substringAfter("://").substringBefore("/") }
}

/** A server-owned session summary displayed by the app. */
@Serializable
data class SavedSession(
    val id: String,
    val name: String = "",
    val createdAt: Long = 0L,
    val lastActive: Long = 0L,
    /** Workspace (pi working directory) reported by the gateway; blank = unknown/default. */
    val workDir: String = "",
    /** Whether the gateway currently owns a live pi process for this session. */
    val running: Boolean = false,
    /** Whether that process is between agent_start and agent_settled. */
    val outputting: Boolean = false,
)

enum class ThemeMode { System, Light, Dark }

enum class AppLanguage { System, English, Chinese }

enum class ConnState { Disconnected, Connecting, Ready, Error }

/** Session restoration work performed after the transport connects but before it becomes live. */
enum class SessionSyncPhase { Idle, RestoringHistory, CatchingUp }
