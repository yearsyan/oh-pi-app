package io.github.yearsyan.ohpi.net

import io.github.yearsyan.ohpi.data.ServerConnectionMode
import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.SshAuthentication
import io.github.yearsyan.ohpi.data.SshServerProfile
import io.github.yearsyan.ohpi.ssh.SshCommandResult
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class ManagedGatewayLiveTest {
    @Test
    fun provisionsAndSupervisesConfiguredSshHost() {
        val host = System.getenv("OHPI_LIVE_SSH_HOST") ?: return
        val user = requireEnvironment("OHPI_LIVE_SSH_USER")
        val keyFile = File(requireEnvironment("OHPI_LIVE_SSH_KEY"))
        val knownHosts = File(requireEnvironment("OHPI_LIVE_SSH_KNOWN_HOSTS"))
        val gatewayBinary = File(requireEnvironment("OHPI_LIVE_GATEWAY_BINARY"))
        val port = System.getenv("OHPI_LIVE_SSH_PORT")?.toInt() ?: 22
        val token = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val profile =
            ServerProfile(
                id = "live-managed-install",
                name = "Live managed install",
                url = "ws://127.0.0.1:18080",
                token = token,
                connectionMode = ServerConnectionMode.ManagedSsh,
                ssh =
                    SshServerProfile(
                        host = host,
                        port = port,
                        username = user,
                        authentication = SshAuthentication.PrivateKey,
                        privateKey = keyFile.readText(),
                    ),
            )
        val executor: suspend (String, ByteArray, Int) -> SshCommandResult = { command, stdin, timeout ->
            executeOpenSsh(host, port, user, keyFile, knownHosts, command, stdin, timeout)
        }
        val provisioner =
            ManagedGatewayProvisioner(
                profile = profile,
                execute = executor,
                artifactSource = ManagedGatewayArtifactSource { os, architecture ->
                    assertEquals(ManagedHostOs.Linux, os)
                    assertEquals("amd64", architecture)
                    ManagedGatewayArtifact("1.10.5", gatewayBinary.name, gatewayBinary.readBytes())
                },
            )

        runBlocking {
            provisioner.ensureRunning()
            provisioner.ensureRunning()
            assertHealthy(executor)

            val firstPid = serviceMainPid(executor)
            assertNotEquals("0", firstPid)

            provisioner.stop()
            val stopped = executor("systemctl --user is-active ohpi-gateway.service", byteArrayOf(), 30_000)
            assertNotEquals(0, stopped.exitStatus)

            provisioner.ensureRunning()
            assertHealthy(executor)
            val restartedPid = serviceMainPid(executor)
            assertNotEquals("0", restartedPid)
            assertNotEquals(firstPid, restartedPid)

            // All command connections are closed during this delay. This
            // catches hosts that tear the user manager down immediately.
            delay(12_000)
            assertHealthy(executor)
        }
    }

    private suspend fun assertHealthy(
        executor: suspend (String, ByteArray, Int) -> SshCommandResult,
    ) {
        var last = SshCommandResult(-1, byteArrayOf(), byteArrayOf())
        repeat(20) {
            last =
                executor(
                    "curl -fsS http://127.0.0.1:18080/healthz",
                    byteArrayOf(),
                    30_000,
                )
            if (last.exitStatus == 0 && last.stdoutText.contains("\"service\":\"ohpi-gateway\"")) {
                return
            }
            delay(500)
        }
        assertTrue(false, "gateway was not healthy: ${last.stderrText}")
    }

    private suspend fun serviceMainPid(
        executor: suspend (String, ByteArray, Int) -> SshCommandResult,
    ): String {
        val result =
            executor(
                "systemctl --user show ohpi-gateway.service -p MainPID --value",
                byteArrayOf(),
                30_000,
            )
        assertEquals(0, result.exitStatus, result.stderrText)
        return result.stdoutText.trim()
    }
}

private fun requireEnvironment(name: String): String =
    requireNotNull(System.getenv(name)) { "$name is required when OHPI_LIVE_SSH_HOST is set" }

private suspend fun executeOpenSsh(
    host: String,
    port: Int,
    user: String,
    keyFile: File,
    knownHosts: File,
    command: String,
    stdin: ByteArray,
    timeoutMillis: Int,
): SshCommandResult =
    withContext(Dispatchers.IO) {
        val process =
            ProcessBuilder(
                "ssh",
                "-i",
                keyFile.absolutePath,
                "-p",
                port.toString(),
                "-o",
                "IdentitiesOnly=yes",
                "-o",
                "BatchMode=yes",
                "-o",
                "StrictHostKeyChecking=yes",
                "-o",
                "UserKnownHostsFile=${knownHosts.absolutePath}",
                "$user@$host",
                command,
            ).start()
        coroutineScope {
            val stdout = async(Dispatchers.IO) { process.inputStream.readBytes() }
            val stderr = async(Dispatchers.IO) { process.errorStream.readBytes() }
            process.outputStream.use { it.write(stdin) }
            if (!process.waitFor(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor()
                error("SSH command timed out: $command")
            }
            SshCommandResult(process.exitValue(), stdout.await(), stderr.await())
        }
    }
