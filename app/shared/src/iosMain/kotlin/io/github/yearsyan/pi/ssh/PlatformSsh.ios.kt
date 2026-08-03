@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yearsyan.pi.ssh

import cnames.structs.pi_ssh_tunnel
import io.github.yearsyan.pi.ssh.cinterop.pi_ssh_error
import io.github.yearsyan.pi.ssh.cinterop.pi_ssh_library_version
import io.github.yearsyan.pi.ssh.cinterop.pi_ssh_tunnel_config
import io.github.yearsyan.pi.ssh.cinterop.pi_ssh_tunnel_config_init
import io.github.yearsyan.pi.ssh.cinterop.pi_ssh_tunnel_copy_last_error
import io.github.yearsyan.pi.ssh.cinterop.pi_ssh_tunnel_free
import io.github.yearsyan.pi.ssh.cinterop.pi_ssh_tunnel_local_port
import io.github.yearsyan.pi.ssh.cinterop.pi_ssh_tunnel_start
import io.github.yearsyan.pi.ssh.cinterop.pi_ssh_tunnel_state
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.Foundation.NSLock

internal actual object PlatformSsh {
    actual fun start(config: SshTunnelConfig): SshTunnelHandle =
        memScoped {
            val nativeConfig = alloc<pi_ssh_tunnel_config>()
            val nativeError = alloc<pi_ssh_error>()
            pi_ssh_tunnel_config_init(nativeConfig.ptr)
            nativeConfig.ssh_host = cString(config.sshHost)
            nativeConfig.ssh_port = config.sshPort.toUShort()
            nativeConfig.username = cString(config.username)
            nativeConfig.auth_type = config.authType.nativeValue
            nativeConfig.password = cStringOrNull(config.password)
            nativeConfig.private_key = cStringOrNull(config.privateKey)
            nativeConfig.private_key_passphrase = cStringOrNull(config.privateKeyPassphrase)
            nativeConfig.expected_host_key_sha256 =
                cStringOrNull(config.expectedHostKeySha256?.takeIf { it.isNotBlank() })
            nativeConfig.remote_host = cString(config.remoteHost)
            nativeConfig.remote_port = config.remotePort.toUShort()
            nativeConfig.connect_timeout_ms = config.connectTimeoutMillis.toUInt()
            nativeConfig.keepalive_interval_seconds = config.keepaliveIntervalSeconds.toUInt()

            val tunnel = pi_ssh_tunnel_start(nativeConfig.ptr, nativeError.ptr)
            if (tunnel == null) {
                throw SshTunnelException(nativeError.toSshError())
            }
            IosSshTunnelHandle(
                initialPointer = tunnel,
                localPort = pi_ssh_tunnel_local_port(tunnel).toInt(),
            )
        }

    actual fun libraryVersion(): String =
        pi_ssh_library_version()?.toKString().orEmpty()
}

private class IosSshTunnelHandle(
    initialPointer: CPointer<pi_ssh_tunnel>,
    override val localPort: Int,
) : SshTunnelHandle {
    private val lock = NSLock()
    private var pointer: CPointer<pi_ssh_tunnel>? = initialPointer

    override val state: SshTunnelState
        get() =
            locked {
                pointer?.let { SshTunnelState.fromNative(pi_ssh_tunnel_state(it)) }
                    ?: SshTunnelState.Stopped
            }

    override val lastError: SshTunnelError
        get() =
            locked {
                val current = pointer
                    ?: return@locked SshTunnelError(SshTunnelErrorCode.None, "")
                memScoped {
                    val error = alloc<pi_ssh_error>()
                    pi_ssh_tunnel_copy_last_error(current, error.ptr)
                    error.toSshError()
                }
            }

    override fun close() {
        locked {
            val current = pointer ?: return@locked
            pointer = null
            pi_ssh_tunnel_free(current)
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

private fun MemScope.cString(value: String): CPointer<ByteVar> = value.cstr.ptr

private fun MemScope.cStringOrNull(value: String?): CPointer<ByteVar>? =
    value?.cstr?.ptr

private fun pi_ssh_error.toSshError(): SshTunnelError =
    SshTunnelError(
        code = SshTunnelErrorCode.fromNative(code),
        message = message.toKString(),
        hostKeySha256 = host_key_sha256.toKString().takeIf { it.isNotBlank() },
    )
