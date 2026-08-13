package io.github.yearsyan.ohpi.net

import io.github.yearsyan.ohpi.data.ServerConnectionMode
import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.SshAuthentication
import io.github.yearsyan.ohpi.data.SshServerProfile
import io.github.yearsyan.ohpi.ssh.SshCommandResult
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
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
    fun embeddedInstallerChecksumMatchesCanonicalScript() {
        val workingDirectory = File(System.getProperty("user.dir"))
        val installer =
            listOf(
                workingDirectory.resolve("../../scripts/install.sh"),
                workingDirectory.resolve("../scripts/install.sh"),
                workingDirectory.resolve("scripts/install.sh"),
            ).firstOrNull(File::isFile)
                ?: error("could not locate scripts/install.sh from $workingDirectory")
        assertEquals(UnixManagedInstallerSha256, sha256(installer.readBytes()))
    }

    @Test
    fun unixInstallerBootstrapVerifiesDownloadAndProtectsTokenStdin() {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val root = Files.createTempDirectory("ohpi-installer-bootstrap-").toFile()
        try {
            val home = root.resolve("home with spaces").apply(File::mkdirs)
            val bin = root.resolve("bin").apply(File::mkdirs)
            val downloadedInstaller = root.resolve("installer.sh")
            downloadedInstaller.writeText(
                """
                #!/bin/sh
                set -eu
                [ "§OHPI_TOKEN_STDIN" = 1 ]
                [ -z "§{OHPI_TOKEN-}" ]
                IFS= read -r token
                printf '%s\n%s\n%s\n' "§token" "§OHPI_LISTEN" "§OHPI_PI_COMMAND" > "§HOME/result"
                """.trimIndent().replace('§', '$') + "\n",
            )
            val curl = bin.resolve("curl")
            curl.writeText(
                """
                #!/bin/sh
                set -eu
                if IFS= read -r unexpected; then exit 88; fi
                [ -z "§{FAKE_CURL_FAIL-}" ] || exit 22
                out=
                url=
                while [ "§#" -gt 0 ]; do
                  case §1 in -o) out=§2; shift 2 ;; -*) shift ;; *) url=§1; shift ;; esac
                done
                if [ -n "§{FAKE_PRIMARY_BAD-}" ] && [ "§url" = 'https://example.invalid/tag/install.sh' ]; then
                  printf 'wrong installer' > "§out"
                else
                  cp "§FAKE_INSTALLER_SOURCE" "§out"
                fi
                """.trimIndent().replace('§', '$') + "\n",
            )
            curl.setExecutable(true)
            val environment =
                ManagedHostEnvironment(
                    os = ManagedHostOs.Linux,
                    architecture = "amd64",
                    home = home.absolutePath,
                    workDir = home.absolutePath,
                    piPath = "/tmp/pi's/bin/pi",
                    piEnvironmentPath = "/tmp/pi's/bin:/usr/bin:/bin",
                    manager = "systemd",
                    installedVersion = "",
                    managed = false,
                    running = false,
                    tokenSha256 = "",
                )
            val command =
                unixManagedInstallCommand(
                    environment = environment,
                    installerUrl = "https://example.invalid/tag/install.sh",
                    fallbackInstallerUrl = "https://example.invalid/main/install.sh",
                    installerSha256 = sha256(downloadedInstaller.readBytes()),
                )

            fun execute(
                token: String,
                failDownload: Boolean = false,
                badPrimary: Boolean = false,
            ) {
                val process =
                    ProcessBuilder("sh", "-c", command)
                        .redirectErrorStream(true)
                        .apply {
                            environment()["HOME"] = home.absolutePath
                            environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
                            environment()["FAKE_INSTALLER_SOURCE"] = downloadedInstaller.absolutePath
                            if (failDownload) environment()["FAKE_CURL_FAIL"] = "1"
                            if (badPrimary) environment()["FAKE_PRIMARY_BAD"] = "1"
                        }.start()
                process.outputStream.use { it.write("$token\n".encodeToByteArray()) }
                val output = process.inputStream.bufferedReader().readText()
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "bootstrap timed out")
                assertEquals(0, process.exitValue(), output)
                assertTrue(!command.contains(token))
            }

            execute("first-secret", badPrimary = true)
            assertEquals(
                listOf("first-secret", "127.0.0.1:18080", "/tmp/pi's/bin/pi"),
                home.resolve("result").readLines(),
            )
            execute("cached-secret", failDownload = true)
            assertEquals("cached-secret", home.resolve("result").readLines().first())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun provisionsAndSupervisesConfiguredSshHost() {
        val host = System.getenv("OHPI_LIVE_SSH_HOST") ?: return
        val user = requireEnvironment("OHPI_LIVE_SSH_USER")
        val keyFile = File(requireEnvironment("OHPI_LIVE_SSH_KEY"))
        val knownHosts = File(requireEnvironment("OHPI_LIVE_SSH_KNOWN_HOSTS"))
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
        val provisioner = ManagedGatewayProvisioner(profile = profile, execute = executor)

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

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

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
