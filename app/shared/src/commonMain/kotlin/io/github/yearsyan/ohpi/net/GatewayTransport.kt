package io.github.yearsyan.ohpi.net

import io.github.yearsyan.ohpi.data.ServerConnectionMode
import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.SshAuthentication
import io.github.yearsyan.ohpi.ssh.PlatformSsh
import io.github.yearsyan.ohpi.ssh.SshAuthType
import io.github.yearsyan.ohpi.ssh.SshTunnelConfig
import io.github.yearsyan.ohpi.ssh.SshTunnelErrorCode
import io.github.yearsyan.ohpi.ssh.SshTunnelException
import io.github.yearsyan.ohpi.ssh.SshTunnelHandle
import io.github.yearsyan.ohpi.ssh.SshTunnelState
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Information shown before a first-use or changed SSH host key is trusted. */
data class SshHostKeyPrompt(
    val serverId: String,
    val serverName: String,
    val sshHost: String,
    val sshPort: Int,
    val observedFingerprint: String,
    val expectedFingerprint: String? = null,
) {
    val isChanged: Boolean get() = !expectedFingerprint.isNullOrBlank()
}

/** A setup failure that tells reconnect policy whether retrying can help. */
class GatewayConnectionException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)

internal data class GatewayTarget(
    val normalizedUrl: String,
    val remoteHost: String,
    val remotePort: Int,
)

internal fun gatewayTarget(raw: String, requirePlaintext: Boolean): GatewayTarget {
    val normalized = normalizeGatewayUrl(raw)
    val url =
        runCatching { Url(normalized) }
            .getOrElse {
                throw GatewayConnectionException(
                    "Invalid gateway address",
                    retryable = false,
                    cause = it,
                )
            }
    val scheme = url.protocol.name.lowercase()
    if (scheme != "ws" && scheme != "wss") {
        throw GatewayConnectionException("Invalid gateway address", retryable = false)
    }
    if (requirePlaintext && scheme == "wss") {
        throw GatewayConnectionException(
            "SSH mode requires a ws:// or http:// gateway address; the SSH tunnel already encrypts the connection",
            retryable = false,
        )
    }
    if (url.host.isBlank() || url.port !in 1..65535) {
        throw GatewayConnectionException("Invalid gateway address", retryable = false)
    }
    return GatewayTarget(
        normalizedUrl = normalized.trimEnd('/'),
        remoteHost = url.host.removePrefix("[").removeSuffix("]"),
        remotePort = url.port,
    )
}

internal fun loopbackGatewayUrl(target: GatewayTarget, localPort: Int): String {
    val builder = URLBuilder(Url(target.normalizedUrl))
    builder.host = "127.0.0.1"
    builder.port = localPort
    return builder.buildString().trimEnd('/')
}

/**
 * Owns the one SSH tunnel shared by WebSocket and HTTP traffic for a server.
 * A failed native worker is discarded and recreated on the next resolution.
 */
internal class GatewayTransport(
    private val profile: ServerProfile,
    private val confirmHostKey: suspend (SshHostKeyPrompt) -> Boolean,
    private val onHostKeyTrusted: (String) -> Unit,
) {
    private val mutex = Mutex()

    @Volatile
    private var closed = false

    @Volatile
    private var tunnel: SshTunnelHandle? = null

    private var trustedHostKey = profile.ssh.hostKeySha256.trim()

    suspend fun resolveGateway(): String {
        if (profile.connectionMode == ServerConnectionMode.Direct) {
            if (closed) throw GatewayConnectionException("Connection transport is closed", false)
            return profile.url
        }
        return mutex.withLock {
            if (closed) throw GatewayConnectionException("Connection transport is closed", false)

            val target = gatewayTarget(profile.url, requirePlaintext = true)
            tunnel?.let { current ->
                if (current.state == SshTunnelState.Running) {
                    return@withLock loopbackGatewayUrl(target, current.localPort)
                }
                tunnel = null
                current.close()
            }

            while (true) {
                val started =
                    try {
                        withContext(Dispatchers.Default) {
                            PlatformSsh.start(profile.toNativeConfig(target, trustedHostKey))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: SshTunnelException) {
                        val error = failure.error
                        if (
                            error.code == SshTunnelErrorCode.HostKeyUnknown ||
                            error.code == SshTunnelErrorCode.HostKeyMismatch
                        ) {
                            val observed = error.hostKeySha256.orEmpty()
                            if (observed.isBlank()) {
                                throw failure.toGatewayFailure()
                            }
                            val accepted =
                                confirmHostKey(
                                    SshHostKeyPrompt(
                                        serverId = profile.id,
                                        serverName = profile.displayName,
                                        sshHost = profile.ssh.host,
                                        sshPort = profile.ssh.port,
                                        observedFingerprint = observed,
                                        expectedFingerprint = trustedHostKey.takeIf { it.isNotBlank() },
                                    ),
                                )
                            if (!accepted) {
                                throw GatewayConnectionException(
                                    "SSH host key was not trusted",
                                    retryable = false,
                                )
                            }
                            trustedHostKey = observed
                            onHostKeyTrusted(observed)
                            continue
                        }
                        throw failure.toGatewayFailure()
                    }

                tunnel = started
                if (closed) {
                    tunnel = null
                    started.close()
                    throw GatewayConnectionException("Connection transport is closed", false)
                }
                return@withLock loopbackGatewayUrl(target, started.localPort)
            }
            error("unreachable")
        }
    }

    fun close() {
        closed = true
        val current = tunnel
        tunnel = null
        current?.close()
    }
}

private fun ServerProfile.toNativeConfig(target: GatewayTarget, hostKey: String): SshTunnelConfig =
    SshTunnelConfig(
        sshHost = ssh.host.trim(),
        sshPort = ssh.port,
        username = ssh.username.trim(),
        authType =
            when (ssh.authentication) {
                SshAuthentication.Password -> SshAuthType.Password
                SshAuthentication.PrivateKey -> SshAuthType.PrivateKey
            },
        password = ssh.password.takeIf { ssh.authentication == SshAuthentication.Password },
        privateKey = ssh.privateKey.takeIf { ssh.authentication == SshAuthentication.PrivateKey },
        privateKeyPassphrase =
            ssh.privateKeyPassphrase
                .takeIf { ssh.authentication == SshAuthentication.PrivateKey && it.isNotEmpty() },
        expectedHostKeySha256 = hostKey.takeIf { it.isNotBlank() },
        remoteHost = target.remoteHost,
        remotePort = target.remotePort,
    )

private fun SshTunnelException.toGatewayFailure(): GatewayConnectionException {
    val canRetry =
        error.code in
            setOf(
                SshTunnelErrorCode.Connect,
                SshTunnelErrorCode.LocalListener,
                SshTunnelErrorCode.Thread,
                SshTunnelErrorCode.Disconnected,
                SshTunnelErrorCode.RemoteForward,
            )
    return GatewayConnectionException(
        message = error.message.ifBlank { "SSH connection failed (${error.code.name})" },
        retryable = canRetry,
        cause = this,
    )
}
