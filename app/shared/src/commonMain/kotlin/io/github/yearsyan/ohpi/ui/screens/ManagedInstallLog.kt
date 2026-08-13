package io.github.yearsyan.ohpi.ui.screens

import io.github.yearsyan.ohpi.ssh.SshCommandOutput
import io.github.yearsyan.ohpi.ssh.SshCommandStream

internal data class ManagedInstallLogLine(
    val stream: SshCommandStream,
    val text: String,
)

/** Converts arbitrary SSH byte chunks into a small, UI-safe rolling line log. */
internal class ManagedInstallLogBuffer(
    private val redactedValues: List<String> = emptyList(),
    private val maxLines: Int = 300,
    private val maxPendingBytes: Int = 64 * 1024,
) {
    private val lines = ArrayDeque<ManagedInstallLogLine>()
    private val pending = mutableMapOf<SshCommandStream, ByteArray>()

    init {
        require(maxLines > 0)
        require(maxPendingBytes > 0)
    }

    fun append(output: SshCommandOutput): List<ManagedInstallLogLine> {
        if (output.bytes.isEmpty()) return snapshot()
        val combined = (pending.remove(output.stream) ?: byteArrayOf()) + output.bytes
        var start = 0
        combined.forEachIndexed { index, byte ->
            if (byte == Newline) {
                appendLine(output.stream, combined.copyOfRange(start, index).withoutTrailingCarriageReturn())
                start = index + 1
            }
        }
        val remainder = combined.copyOfRange(start, combined.size)
        if (remainder.size > maxPendingBytes) {
            appendLine(output.stream, remainder)
        } else if (remainder.isNotEmpty()) {
            pending[output.stream] = remainder
        }
        return snapshot()
    }

    fun flush(): List<ManagedInstallLogLine> {
        pending.forEach { (stream, bytes) -> appendLine(stream, bytes.withoutTrailingCarriageReturn()) }
        pending.clear()
        return snapshot()
    }

    fun clear() {
        lines.clear()
        pending.clear()
    }

    private fun appendLine(stream: SshCommandStream, bytes: ByteArray) {
        var text = bytes.decodeToString().replace(AnsiEscape, "")
        text = text.filter { character -> character == '\t' || character >= ' ' }
        redactedValues
            .asSequence()
            .filter(String::isNotEmpty)
            .sortedByDescending(String::length)
            .forEach { secret -> text = text.replace(secret, Redaction) }
        if (text.length > MaxLineCharacters) {
            text = text.take(MaxLineCharacters) + "…"
        }
        lines.addLast(ManagedInstallLogLine(stream, text))
        while (lines.size > maxLines) lines.removeFirst()
    }

    private fun snapshot(): List<ManagedInstallLogLine> = lines.toList()

    private fun ByteArray.withoutTrailingCarriageReturn(): ByteArray =
        if (lastOrNull() == CarriageReturn) copyOf(size - 1) else this

    private companion object {
        const val MaxLineCharacters = 2_000
        const val Redaction = "••••"
        const val Newline: Byte = 10
        const val CarriageReturn: Byte = 13
        val AnsiEscape = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
    }
}
