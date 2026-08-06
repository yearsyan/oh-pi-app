package io.github.yearsyan.ohpi.ssh

enum class SshAuthType(val nativeValue: Int) {
    Password(1),
    PrivateKey(2),
}

enum class SshTunnelState(val nativeValue: Int) {
    Starting(1),
    Running(2),
    Stopping(3),
    Stopped(4),
    Failed(5),
    ;

    companion object {
        fun fromNative(value: Int): SshTunnelState =
            entries.firstOrNull { it.nativeValue == value } ?: Failed
    }
}

enum class SshTunnelErrorCode(val nativeValue: Int) {
    None(0),
    InvalidArgument(1),
    OutOfMemory(2),
    Connect(3),
    HostKeyUnavailable(4),
    HostKeyUnknown(5),
    HostKeyMismatch(6),
    PrivateKey(7),
    Authentication(8),
    LocalListener(9),
    Thread(10),
    Disconnected(11),
    RemoteForward(12),
    Internal(13),
    RemoteCommand(14),
    CommandTimeout(15),
    OutputLimit(16),
    ;

    companion object {
        fun fromNative(value: Int): SshTunnelErrorCode =
            entries.firstOrNull { it.nativeValue == value } ?: Internal
    }
}

data class SshTunnelConfig(
    val sshHost: String,
    val sshPort: Int = 22,
    val username: String,
    val authType: SshAuthType,
    val password: String? = null,
    val privateKey: String? = null,
    val privateKeyPassphrase: String? = null,
    val expectedHostKeySha256: String? = null,
    val remoteHost: String,
    val remotePort: Int,
    val connectTimeoutMillis: Int = 15_000,
    val keepaliveIntervalSeconds: Int = 30,
)

data class SshCommandConfig(
    val sshHost: String,
    val sshPort: Int = 22,
    val username: String,
    val authType: SshAuthType,
    val password: String? = null,
    val privateKey: String? = null,
    val privateKeyPassphrase: String? = null,
    val expectedHostKeySha256: String? = null,
    val command: String,
    val stdin: ByteArray = byteArrayOf(),
    val connectTimeoutMillis: Int = 15_000,
    val commandTimeoutMillis: Int = 120_000,
    val maxOutputBytes: Int = 1024 * 1024,
)

data class SshCommandResult(
    val exitStatus: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
) {
    val stdoutText: String
        get() = stdout.decodeToString()

    val stderrText: String
        get() = stderr.decodeToString()
}

data class SshTunnelError(
    val code: SshTunnelErrorCode,
    val message: String,
    val hostKeySha256: String? = null,
)

class SshTunnelException(
    val error: SshTunnelError,
) : Exception(error.message)

internal interface SshTunnelHandle {
    val localPort: Int
    val state: SshTunnelState
    val lastError: SshTunnelError

    fun close()
}

internal expect object PlatformSsh {
    fun start(config: SshTunnelConfig): SshTunnelHandle

    fun execute(config: SshCommandConfig): SshCommandResult

    fun libraryVersion(): String
}
