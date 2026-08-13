package io.github.yearsyan.ohpi.net

import io.github.yearsyan.ohpi.ssh.SshCommandOutput
import io.github.yearsyan.ohpi.ssh.SshCommandStream

private const val ProgressPrefix = "@@OHPI_PROGRESS:"
private const val ProgressSuffix = "@@"
private const val MaxPendingBytes = 64 * 1024
private const val Newline: Byte = 10
private const val CarriageReturn: Byte = 13

/**
 * Removes machine-readable installer progress lines from stdout and turns them
 * into structured callbacks while forwarding every ordinary stdout/stderr line.
 */
internal class ManagedInstallProgressDecoder(
    private val onProgress: (ManagedInstallStep) -> Unit,
    private val onOutput: (SshCommandOutput) -> Unit,
) {
    private var stdoutPending = byteArrayOf()

    fun append(output: SshCommandOutput) {
        if (output.bytes.isEmpty()) return
        if (output.stream != SshCommandStream.Stdout) {
            onOutput(output)
            return
        }

        val combined = stdoutPending + output.bytes
        stdoutPending = byteArrayOf()
        var start = 0
        combined.forEachIndexed { index, byte ->
            if (byte == Newline) {
                processStdoutLine(combined.copyOfRange(start, index + 1))
                start = index + 1
            }
        }
        val remainder = combined.copyOfRange(start, combined.size)
        if (remainder.size > MaxPendingBytes) {
            onOutput(SshCommandOutput(SshCommandStream.Stdout, remainder))
        } else {
            stdoutPending = remainder
        }
    }

    fun flush() {
        if (stdoutPending.isNotEmpty()) {
            processStdoutLine(stdoutPending)
            stdoutPending = byteArrayOf()
        }
    }

    private fun processStdoutLine(bytes: ByteArray) {
        var contentEnd = bytes.size
        if (contentEnd > 0 && bytes[contentEnd - 1] == Newline) contentEnd--
        if (contentEnd > 0 && bytes[contentEnd - 1] == CarriageReturn) contentEnd--
        val text = bytes.copyOfRange(0, contentEnd).decodeToString()
        parseManagedInstallProgress(text)?.let(onProgress)
            ?: onOutput(SshCommandOutput(SshCommandStream.Stdout, bytes))
    }
}

internal fun parseManagedInstallProgress(line: String): ManagedInstallStep? {
    if (!line.startsWith(ProgressPrefix) || !line.endsWith(ProgressSuffix)) return null
    return when (line.removePrefix(ProgressPrefix).removeSuffix(ProgressSuffix)) {
        "installing_pi" -> ManagedInstallStep.InstallingPi
        "downloading_gateway" -> ManagedInstallStep.DownloadingGateway
        "installing_gateway" -> ManagedInstallStep.InstallingGateway
        "starting_gateway" -> ManagedInstallStep.StartingGateway
        else -> null
    }
}
