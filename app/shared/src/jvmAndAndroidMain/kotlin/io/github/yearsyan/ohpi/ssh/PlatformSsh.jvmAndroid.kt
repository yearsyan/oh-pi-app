package io.github.yearsyan.ohpi.ssh

import java.io.File
import kotlin.jvm.JvmStatic

internal actual object PlatformSsh {
    actual fun start(config: SshTunnelConfig): SshTunnelHandle {
        NativeSshBridge.ensureLoaded()
        val errorCode = IntArray(1)
        val errorStrings = arrayOfNulls<String>(2)
        val handle =
            NativeSshBridge.nativeStart(
                sshHost = config.sshHost.encodeToByteArray(),
                sshPort = config.sshPort,
                username = config.username.encodeToByteArray(),
                authType = config.authType.nativeValue,
                password = config.password?.encodeToByteArray(),
                privateKey = config.privateKey?.encodeToByteArray(),
                privateKeyPassphrase = config.privateKeyPassphrase?.encodeToByteArray(),
                expectedHostKeySha256 =
                    config.expectedHostKeySha256
                        ?.takeIf { it.isNotBlank() }
                        ?.encodeToByteArray(),
                remoteHost = config.remoteHost.encodeToByteArray(),
                remotePort = config.remotePort,
                connectTimeoutMillis = config.connectTimeoutMillis,
                keepaliveIntervalSeconds = config.keepaliveIntervalSeconds,
                errorCode = errorCode,
                errorStrings = errorStrings,
            )
        if (handle == 0L) {
            throw SshTunnelException(errorCode.toSshError(errorStrings))
        }
        return JvmSshTunnelHandle(
            initialHandle = handle,
            localPort = NativeSshBridge.nativeLocalPort(handle),
        )
    }

    actual fun execute(config: SshCommandConfig): SshCommandResult {
        NativeSshBridge.ensureLoaded()
        val exitStatus = IntArray(1)
        val errorCode = IntArray(1)
        val errorStrings = arrayOfNulls<String>(2)
        val output =
            NativeSshBridge.nativeExecute(
                sshHost = config.sshHost.encodeToByteArray(),
                sshPort = config.sshPort,
                username = config.username.encodeToByteArray(),
                authType = config.authType.nativeValue,
                password = config.password?.encodeToByteArray(),
                privateKey = config.privateKey?.encodeToByteArray(),
                privateKeyPassphrase = config.privateKeyPassphrase?.encodeToByteArray(),
                expectedHostKeySha256 =
                    config.expectedHostKeySha256
                        ?.takeIf { it.isNotBlank() }
                        ?.encodeToByteArray(),
                command = config.command.encodeToByteArray(),
                stdin = config.stdin,
                connectTimeoutMillis = config.connectTimeoutMillis,
                commandTimeoutMillis = config.commandTimeoutMillis,
                maxOutputBytes = config.maxOutputBytes,
                outputListener = config.onOutput?.let(::NativeSshOutputListener),
                exitStatus = exitStatus,
                errorCode = errorCode,
                errorStrings = errorStrings,
            ) ?: throw SshTunnelException(errorCode.toSshError(errorStrings))
        return SshCommandResult(
            exitStatus = exitStatus[0],
            stdout = output.getOrNull(0) ?: byteArrayOf(),
            stderr = output.getOrNull(1) ?: byteArrayOf(),
        )
    }

    actual fun generateEd25519KeyPair(passphrase: String?): GeneratedSshKeyPair {
        NativeSshBridge.ensureLoaded()
        val errorCode = IntArray(1)
        val errorStrings = arrayOfNulls<String>(2)
        val material =
            NativeSshBridge.nativeGenerateEd25519KeyPair(
                passphrase = passphrase?.takeIf { it.isNotEmpty() }?.encodeToByteArray(),
                errorCode = errorCode,
                errorStrings = errorStrings,
            ) ?: throw SshTunnelException(errorCode.toSshError(errorStrings))
        return GeneratedSshKeyPair(
            privateKey = material.getOrNull(0)?.decodeToString().orEmpty(),
            publicKey = material.getOrNull(1)?.decodeToString().orEmpty(),
        )
    }

    actual fun libraryVersion(): String {
        NativeSshBridge.ensureLoaded()
        return NativeSshBridge.nativeVersion()
    }
}

internal class NativeSshOutputListener(
    private val callback: (SshCommandOutput) -> Unit,
) {
    @Suppress("unused") // Called by native/pi_ssh/src/pi_ssh_jni.c.
    fun onOutput(stream: Int, bytes: ByteArray) {
        callback(SshCommandOutput(SshCommandStream.fromNative(stream), bytes))
    }
}

private class JvmSshTunnelHandle(
    initialHandle: Long,
    override val localPort: Int,
) : SshTunnelHandle {
    private var handle = initialHandle

    override val state: SshTunnelState
        @Synchronized get() =
            if (handle == 0L) {
                SshTunnelState.Stopped
            } else {
                SshTunnelState.fromNative(NativeSshBridge.nativeState(handle))
            }

    override val lastError: SshTunnelError
        @Synchronized get() {
            if (handle == 0L) {
                return SshTunnelError(SshTunnelErrorCode.None, "")
            }
            val errorCode = IntArray(1)
            val errorStrings = arrayOfNulls<String>(2)
            NativeSshBridge.nativeLastError(handle, errorCode, errorStrings)
            return errorCode.toSshError(errorStrings)
        }

    @Synchronized
    override fun close() {
        val current = handle
        if (current == 0L) return
        handle = 0L
        NativeSshBridge.nativeFree(current)
    }
}

private fun IntArray.toSshError(strings: Array<String?>): SshTunnelError =
    SshTunnelError(
        code = SshTunnelErrorCode.fromNative(firstOrNull() ?: 0),
        message = strings.getOrNull(0).orEmpty(),
        hostKeySha256 = strings.getOrNull(1)?.takeIf { it.isNotBlank() },
    )

/** Applies the native Windows frame to an AWT window without crossing the Win32 message loop. */
fun applyWindowsNativeWindowFrame(windowHandle: Long): Boolean {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return false
    NativeSshBridge.ensureLoaded()
    return NativeSshBridge.nativeApplyWindowsWindowFrame(windowHandle)
}

internal object NativeSshBridge {
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        NativeSshLibraryLoader.load()
        loaded = true
    }

    @JvmStatic
    external fun nativeStart(
        sshHost: ByteArray,
        sshPort: Int,
        username: ByteArray,
        authType: Int,
        password: ByteArray?,
        privateKey: ByteArray?,
        privateKeyPassphrase: ByteArray?,
        expectedHostKeySha256: ByteArray?,
        remoteHost: ByteArray,
        remotePort: Int,
        connectTimeoutMillis: Int,
        keepaliveIntervalSeconds: Int,
        errorCode: IntArray,
        errorStrings: Array<String?>,
    ): Long

    @JvmStatic
    external fun nativeExecute(
        sshHost: ByteArray,
        sshPort: Int,
        username: ByteArray,
        authType: Int,
        password: ByteArray?,
        privateKey: ByteArray?,
        privateKeyPassphrase: ByteArray?,
        expectedHostKeySha256: ByteArray?,
        command: ByteArray,
        stdin: ByteArray,
        connectTimeoutMillis: Int,
        commandTimeoutMillis: Int,
        maxOutputBytes: Int,
        outputListener: NativeSshOutputListener?,
        exitStatus: IntArray,
        errorCode: IntArray,
        errorStrings: Array<String?>,
    ): Array<ByteArray?>?

    @JvmStatic
    external fun nativeGenerateEd25519KeyPair(
        passphrase: ByteArray?,
        errorCode: IntArray,
        errorStrings: Array<String?>,
    ): Array<ByteArray?>?

    @JvmStatic
    external fun nativeLocalPort(handle: Long): Int

    @JvmStatic
    external fun nativeState(handle: Long): Int

    @JvmStatic
    external fun nativeLastError(
        handle: Long,
        errorCode: IntArray,
        errorStrings: Array<String?>,
    )

    @JvmStatic
    external fun nativeFree(handle: Long)

    @JvmStatic
    external fun nativeVersion(): String

    @JvmStatic
    external fun nativeApplyWindowsWindowFrame(windowHandle: Long): Boolean
}

internal object NativeSshLibraryLoader {
    fun load() {
        val isAndroid =
            System.getProperty("java.runtime.name")?.contains("Android", ignoreCase = true) == true ||
                System.getProperty("java.vm.name")?.contains("Dalvik", ignoreCase = true) == true
        if (isAndroid) {
            System.loadLibrary("pi_ssh")
            return
        }

        val os = System.getProperty("os.name").orEmpty()
        val architecture = System.getProperty("os.arch").orEmpty()
        val classifier = nativeSshClassifier(os, architecture)
        val libraryName = System.mapLibraryName("pi_ssh")
        val resourcePath = "/native/$classifier/$libraryName"
        val input =
            NativeSshLibraryLoader::class.java.getResourceAsStream(resourcePath)
                ?: error("Missing SSH native library resource $resourcePath")
        val suffix = libraryName.substringAfterLast('.', missingDelimiterValue = ".tmp").let { ".$it" }
        val library = File.createTempFile("pi-ssh-", suffix)
        input.use { source -> library.outputStream().use(source::copyTo) }
        library.deleteOnExit()
        System.load(library.absolutePath)
    }
}

internal fun nativeSshClassifier(
    osName: String,
    architectureName: String,
): String {
    val os = osName.lowercase()
    val architecture = architectureName.lowercase()
    return when {
        os.contains("mac") && architecture in setOf("aarch64", "arm64") -> "macos-aarch64"
        os.contains("mac") && architecture in setOf("x86_64", "amd64") -> "macos-x86_64"
        os.contains("linux") && architecture in setOf("x86_64", "amd64") -> "linux-x86_64"
        os.contains("windows") && architecture in setOf("x86_64", "amd64") -> "windows-x86_64"
        else -> error("SSH native library is not packaged for $os/$architecture")
    }
}
