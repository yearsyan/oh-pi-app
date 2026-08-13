package io.github.yearsyan.ohpi.net

import io.github.yearsyan.ohpi.ssh.SshCommandOutput
import io.github.yearsyan.ohpi.ssh.SshCommandStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ManagedInstallProgressTest {
    @Test
    fun decodesSplitProgressEventsAndForwardsOrdinaryOutput() {
        val steps = mutableListOf<ManagedInstallStep>()
        val output = mutableListOf<SshCommandOutput>()
        val decoder = ManagedInstallProgressDecoder(steps::add, output::add)

        decoder.append(stdout("before\n@@OHPI_PROG"))
        decoder.append(
            stdout(
                "RESS:downloading_gateway@@\nafter\n" +
                    "@@OHPI_PROGRESS:installing_gateway@@\n",
            ),
        )
        decoder.append(stderr("warning\n"))
        decoder.flush()

        assertEquals(
            listOf(ManagedInstallStep.DownloadingGateway, ManagedInstallStep.InstallingGateway),
            steps,
        )
        assertEquals(listOf("before\n", "after\n", "warning\n"), output.map { it.bytes.decodeToString() })
        assertEquals(SshCommandStream.Stderr, output.last().stream)
    }

    @Test
    fun preservesUnknownAndIncompleteControlLinesAsLogs() {
        val steps = mutableListOf<ManagedInstallStep>()
        val output = mutableListOf<SshCommandOutput>()
        val decoder = ManagedInstallProgressDecoder(steps::add, output::add)

        decoder.append(stdout("@@OHPI_PROGRESS:future_stage@@\npartial"))
        decoder.flush()

        assertEquals(emptyList(), steps)
        assertEquals(listOf("@@OHPI_PROGRESS:future_stage@@\n", "partial"), output.map { it.bytes.decodeToString() })
    }

    private fun stdout(text: String) =
        SshCommandOutput(SshCommandStream.Stdout, text.encodeToByteArray())

    private fun stderr(text: String) =
        SshCommandOutput(SshCommandStream.Stderr, text.encodeToByteArray())
}
