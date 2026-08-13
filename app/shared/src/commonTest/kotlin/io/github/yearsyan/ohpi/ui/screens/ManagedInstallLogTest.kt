package io.github.yearsyan.ohpi.ui.screens

import io.github.yearsyan.ohpi.ssh.SshCommandOutput
import io.github.yearsyan.ohpi.ssh.SshCommandStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManagedInstallLogTest {
    @Test
    fun reconstructsUtf8LinesSplitAcrossChunks() {
        val buffer = ManagedInstallLogBuffer()
        val encoded = "正在下载\n完成\n".encodeToByteArray()

        assertTrue(buffer.append(stdout(encoded.copyOfRange(0, 2))).isEmpty())
        val lines = buffer.append(stdout(encoded.copyOfRange(2, encoded.size)))

        assertEquals(listOf("正在下载", "完成"), lines.map(ManagedInstallLogLine::text))
        assertTrue(lines.all { it.stream == SshCommandStream.Stdout })
    }

    @Test
    fun sanitizesAnsiAndRedactsSecrets() {
        val buffer = ManagedInstallLogBuffer(redactedValues = listOf("secret-token"))

        val lines =
            buffer.append(
                SshCommandOutput(
                    SshCommandStream.Stderr,
                    "\u001B[31mfailed secret-token\u001B[0m\r\n".encodeToByteArray(),
                ),
            )

        assertEquals(listOf("failed ••••"), lines.map(ManagedInstallLogLine::text))
        assertEquals(SshCommandStream.Stderr, lines.single().stream)
    }

    @Test
    fun flushesPartialLineAndKeepsRollingLimit() {
        val buffer = ManagedInstallLogBuffer(maxLines = 2)
        buffer.append(stdout("one\ntwo\n".encodeToByteArray()))
        buffer.append(stdout("three".encodeToByteArray()))

        val lines = buffer.flush()

        assertEquals(listOf("two", "three"), lines.map(ManagedInstallLogLine::text))
    }

    private fun stdout(bytes: ByteArray): SshCommandOutput =
        SshCommandOutput(SshCommandStream.Stdout, bytes)
}
