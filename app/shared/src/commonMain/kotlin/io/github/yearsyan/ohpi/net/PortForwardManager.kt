package io.github.yearsyan.ohpi.net

import androidx.compose.runtime.mutableStateMapOf
import io.github.yearsyan.ohpi.data.PortForward
import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.ssh.PlatformSsh
import io.github.yearsyan.ohpi.ssh.SshTunnelErrorCode
import io.github.yearsyan.ohpi.ssh.SshTunnelException
import io.github.yearsyan.ohpi.ssh.SshTunnelHandle
import io.github.yearsyan.ohpi.ssh.SshTunnelState
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runtime view of one configured [PortForward]. */
data class PortForwardStatus(
    val forward: PortForward,
    val starting: Boolean = false,
    val running: Boolean = false,
    /** Loopback port on this device once [running]; 0 otherwise. */
    val localPort: Int = 0,
    val error: String? = null,
)

/**
 * Owns one SSH tunnel per enabled port forward of a server. Each tunnel binds
 * an ephemeral loopback port on the device and forwards it to the configured
 * remote endpoint, so remote ports become reachable at 127.0.0.1 locally.
 *
 * All public suspend functions must be called from the main-thread scope that
 * owns [statuses]; blocking native calls are dispatched internally.
 */
class PortForwardManager(
    private val profile: ServerProfile,
    private val forwardsProvider: () -> List<PortForward>,
    private val confirmHostKey: suspend (SshHostKeyPrompt) -> Boolean,
    private val onHostKeyTrusted: (String) -> Unit,
) {
    private data class ActiveForward(val forward: PortForward, val handle: SshTunnelHandle)

    private val mutex = Mutex()
    private val active = LinkedHashMap<String, ActiveForward>()
    val statuses = mutableStateMapOf<String, PortForwardStatus>()
    private var trustedHostKey = profile.ssh.hostKeySha256.trim()

    @Volatile
    private var closed = false

    /** Starts enabled forwards and stops removed, disabled, or reconfigured ones. */
    suspend fun sync() = mutex.withLock {
        if (closed) return@withLock
        val wanted = forwardsProvider().associateBy { it.id }

        val iterator = active.entries.iterator()
        while (iterator.hasNext()) {
            val (id, current) = iterator.next()
            val forward = wanted[id]
            if (forward == null || !forward.enabled || forward != current.forward) {
                iterator.remove()
                closeQuietly(current.handle)
                statuses[id] = PortForwardStatus(forward ?: current.forward)
            }
        }

        for (forward in wanted.values) {
            if (forward.enabled && forward.id !in active) {
                startLocked(forward)
            }
        }
    }

    /**
     * Starts [id] if needed and returns its local loopback port, or null when
     * the forward is unavailable (statuses carry the failure).
     */
    suspend fun ensureRunning(id: String): Int? = mutex.withLock {
        if (closed) return@withLock null
        val forward = forwardsProvider().firstOrNull { it.id == id && it.enabled }
            ?: return@withLock null
        active[id]?.let { current ->
            if (current.handle.state == SshTunnelState.Running) {
                return@withLock current.handle.localPort
            }
            active.remove(id)
            closeQuietly(current.handle)
        }
        startLocked(forward)
        return@withLock active[id]
            ?.takeIf { it.handle.state == SshTunnelState.Running }
            ?.handle
            ?.localPort
    }

    /** Restarts a single forward, typically after a failure. */
    suspend fun restart(id: String) = mutex.withLock {
        if (closed) return@withLock
        active.remove(id)?.let { closeQuietly(it.handle) }
        val forward = forwardsProvider().firstOrNull { it.id == id && it.enabled }
            ?: return@withLock
        startLocked(forward)
    }

    /** Local loopback port forwarding [remotePort] right now, if one is running. */
    fun runningLocalPortFor(remotePort: Int): Int? =
        active.values
            .firstOrNull {
                it.forward.remotePort == remotePort &&
                    isLoopbackHostName(it.forward.remoteHost) &&
                    it.handle.state == SshTunnelState.Running
            }
            ?.handle
            ?.localPort

    /** Detects dead tunnels and moves their status from running to failed. */
    fun refreshStates() {
        val iterator = active.entries.iterator()
        while (iterator.hasNext()) {
            val (id, current) = iterator.next()
            when (current.handle.state) {
                SshTunnelState.Starting,
                SshTunnelState.Running,
                -> Unit
                else -> {
                    val error = current.handle.lastError
                    iterator.remove()
                    closeQuietly(current.handle)
                    statuses[id] = PortForwardStatus(
                        current.forward,
                        error = error.message.ifBlank { "tunnel stopped" },
                    )
                }
            }
        }
    }

    fun close() {
        closed = true
        val handles = active.values.map { it.handle }
        active.clear()
        handles.forEach(::closeQuietly)
        for (id in statuses.keys.toList()) {
            statuses[id]?.let {
                statuses[id] = it.copy(starting = false, running = false, localPort = 0)
            }
        }
    }

    private suspend fun startLocked(forward: PortForward) {
        statuses[forward.id] = PortForwardStatus(forward, starting = true)
        try {
            val handle = startTunnel(forward)
            if (closed) {
                closeQuietly(handle)
                return
            }
            active[forward.id] = ActiveForward(forward, handle)
            statuses[forward.id] =
                PortForwardStatus(forward, running = true, localPort = handle.localPort)
        } catch (cancelled: CancellationException) {
            statuses[forward.id] = PortForwardStatus(forward)
            throw cancelled
        } catch (failure: Throwable) {
            statuses[forward.id] = PortForwardStatus(
                forward,
                error = failure.message?.ifBlank { null } ?: "port forward failed",
            )
        }
    }

    private suspend fun startTunnel(forward: PortForward): SshTunnelHandle {
        while (true) {
            try {
                return withContext(Dispatchers.Default) {
                    PlatformSsh.start(
                        profile.toSshTunnelConfig(
                            remoteHost = forward.remoteHost,
                            remotePort = forward.remotePort,
                            hostKey = trustedHostKey,
                        ),
                    )
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
                    if (observed.isBlank()) throw failure
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
                throw failure
            }
        }
        error("unreachable")
    }

    private fun closeQuietly(handle: SshTunnelHandle) {
        runCatching { handle.close() }
    }
}
