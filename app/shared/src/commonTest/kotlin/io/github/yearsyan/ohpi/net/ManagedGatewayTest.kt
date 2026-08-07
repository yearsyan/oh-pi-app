package io.github.yearsyan.ohpi.net

import io.github.yearsyan.ohpi.data.ServerConnectionMode
import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.SshServerProfile
import io.github.yearsyan.ohpi.ssh.SshCommandResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okio.ByteString.Companion.toByteString

class ManagedGatewayTest {
    @Test
    fun decodesGatewayVersionAndProcessStopFeature() {
        val health =
            PiJson.decodeFromString<ManagedGatewayHealth>(
                """{"status":"ok","service":"ohpi-gateway","version":"1.11.0","protocol":1,"os":"darwin","features":["session_process_stop"]}""",
            )

        assertEquals("1.11.0", health.version)
        assertEquals(1, health.protocol)
        assertEquals("darwin", health.os)
        assertEquals(listOf(GATEWAY_FEATURE_SESSION_PROCESS_STOP), health.features)

        val legacy =
            PiJson.decodeFromString<ManagedGatewayHealth>(
                """{"status":"ok","service":"ohpi-gateway","version":"1.10.5","protocol":1}""",
            )
        assertEquals("", legacy.os)
        assertTrue(legacy.features.isEmpty())
    }

    @Test
    fun parsesSupportedHostProbe() {
        val environment =
            parseManagedHostEnvironment(
                """
                os=Linux
                arch=aarch64
                home=/home/pi
                work=/srv/work
                pi=/home/pi/.local/bin/pi
                pi_path=/home/pi/.local/bin:/usr/bin:/bin
                manager=systemd
                installed=ohpi-gateway 1.10.4
                managed=true
                running=false
                token_sha256=abc123
                """.trimIndent(),
            )
        assertNotNull(environment)
        assertEquals(ManagedHostOs.Linux, environment.os)
        assertEquals("arm64", environment.architecture)
        assertEquals("/srv/work", environment.workDir)
        assertEquals("/home/pi/.local/bin:/usr/bin:/bin", environment.piEnvironmentPath)
        assertTrue(environment.installed)
        assertTrue(environment.managed)
        assertEquals(false, environment.running)
    }

    @Test
    fun resolvesReleaseFileAndChecksum() {
        val name = managedGatewayArtifactName("1.10.4", ManagedHostOs.Windows, "amd64")
        assertEquals("ohpi-gateway-1.10.4-windows-amd64.exe", name)
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            parseManagedGatewayChecksum(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  $name\n",
                name,
            ),
        )
    }

    @Test
    fun windowsTaskUsesSshPasswordWithoutEmbeddingItAsPlainText() {
        val script = windowsInstallScript("p@ss'word\u5bc6\u7801")

        assertTrue(script.contains("-LogonType Password"))
        assertTrue(script.contains("-Password \$taskPassword"))
        assertTrue(!script.contains("p@ss'word\u5bc6\u7801"))
    }

    @Test
    fun windowsKeyAuthenticationFallsBackToInteractiveTask() {
        val script = windowsInstallScript(null)

        assertTrue(script.contains("-LogonType Interactive"))
        assertTrue(!script.contains("-Password \$taskPassword"))
    }

    @Test
    fun piBootstrapsStayInsideUserDirectories() {
        assertTrue(unixPiInstallScript.contains("nodejs.org/dist/latest-v22.x"))
        assertTrue(unixPiInstallScript.contains("Node.js checksum verification failed"))
        assertTrue(unixPiInstallScript.contains("--ignore-scripts"))
        assertTrue(!unixPiInstallScript.contains("sudo"))

        val windows = windowsPiInstallScript()
        assertTrue(windows.contains("nodejs.org/dist/latest-v22.x"))
        assertTrue(windows.contains("Get-FileHash -Algorithm SHA256"))
        assertTrue(windows.contains("--ignore-scripts"))
        assertTrue(!windows.contains("RunLevel Highest"))
    }

    @Test
    fun installsMissingPiBeforeProvisioningGateway() = kotlinx.coroutines.test.runTest {
        val token = "managed-bootstrap-token"
        val tokenHash = (token + "\n").encodeToByteArray().toByteString().sha256().hex()
        var piInstalled = false
        var gatewayInstalled = false
        var piInstallRuns = 0
        val profile =
            ServerProfile(
                id = "bootstrap",
                name = "Bootstrap",
                url = "ws://127.0.0.1:18080",
                token = token,
                connectionMode = ServerConnectionMode.ManagedSsh,
                ssh = SshServerProfile(host = "host", username = "user", password = "password"),
            )
        val executor: suspend (String, ByteArray, Int) -> SshCommandResult = { command, stdin, _ ->
            val text = stdin.decodeToString()
            when {
                command == "sh -s" && text.contains("token_sha256") -> {
                    val pi = if (piInstalled) "/home/user/.local/bin/pi" else ""
                    val version = if (gatewayInstalled) "ohpi-gateway 1.10.5" else ""
                    val output =
                        "os=Linux\narch=x86_64\nhome=/home/user\nwork=/home/user\n" +
                            "pi=$pi\npi_path=/home/user/.local/bin:/usr/bin:/bin\nmanager=systemd\n" +
                            "installed=$version\nmanaged=$gatewayInstalled\nrunning=$gatewayInstalled\n" +
                            "token_sha256=${if (gatewayInstalled) tokenHash else ""}\n"
                    SshCommandResult(0, output.encodeToByteArray(), byteArrayOf())
                }
                command == "sh -s" && text.contains("nodejs.org/dist/latest-v22.x") -> {
                    piInstallRuns++
                    piInstalled = true
                    SshCommandResult(0, byteArrayOf(), byteArrayOf())
                }
                command.startsWith("sh -c") ->
                    SshCommandResult(0, byteArrayOf(), byteArrayOf())
                command == "sh -s" && text.contains("enable --now") -> {
                    gatewayInstalled = true
                    SshCommandResult(0, byteArrayOf(), byteArrayOf())
                }
                else -> SshCommandResult(99, byteArrayOf(), "unexpected command".encodeToByteArray())
            }
        }
        val provisioner =
            ManagedGatewayProvisioner(
                profile = profile,
                execute = executor,
                artifactSource = ManagedGatewayArtifactSource { _, _ ->
                    ManagedGatewayArtifact("1.10.5", "gateway", byteArrayOf(1, 2, 3))
                },
            )

        provisioner.ensureRunning()

        assertEquals(1, piInstallRuns)
        assertTrue(gatewayInstalled)
    }

    @Test
    fun repairsManagedDefinitionWhenPlainStartFails() = kotlinx.coroutines.test.runTest {
        val token = "managed-test-token"
        val tokenHash = (token + "\n").encodeToByteArray().toByteString().sha256().hex()
        var running = false
        var installRuns = 0
        val profile =
            ServerProfile(
                id = "repair",
                name = "Repair",
                url = "ws://127.0.0.1:18080",
                token = token,
                connectionMode = ServerConnectionMode.ManagedSsh,
                ssh = SshServerProfile(host = "host", username = "user", password = "password"),
            )
        val executor: suspend (String, ByteArray, Int) -> SshCommandResult = { command, stdin, _ ->
            val text = stdin.decodeToString()
            when {
                command == "sh -s" && text.contains("uname -s") -> {
                    val output =
                        "os=Linux\narch=x86_64\nhome=/home/user\nwork=/home/user\n" +
                            "pi=/usr/bin/pi\npi_path=/usr/bin:/bin\nmanager=systemd\n" +
                            "installed=ohpi-gateway 1.10.5\n" +
                            "managed=true\nrunning=$running\ntoken_sha256=$tokenHash\n"
                    SshCommandResult(0, output.encodeToByteArray(), byteArrayOf())
                }
                command.startsWith("sh -c") ->
                    SshCommandResult(0, byteArrayOf(), byteArrayOf())
                text.contains("systemctl --user start") ->
                    SshCommandResult(1, byteArrayOf(), "stale task".encodeToByteArray())
                text.contains("enable --now") -> {
                    installRuns++
                    running = true
                    SshCommandResult(0, byteArrayOf(), byteArrayOf())
                }
                else -> SshCommandResult(99, byteArrayOf(), "unexpected command".encodeToByteArray())
            }
        }

        ManagedGatewayProvisioner(profile, executor).ensureRunning()

        assertEquals(1, installRuns)
    }

    @Test
    fun provisionsOnceThenTreatsRunningHostAsIdempotent() = kotlinx.coroutines.test.runTest {
        val token = "managed-test-token"
        val tokenHash = (token + "\n").encodeToByteArray().toByteString().sha256().hex()
        var installed = false
        var artifactDownloads = 0
        val uploads = mutableListOf<Pair<String, ByteArray>>()
        val profile =
            ServerProfile(
                id = "managed",
                name = "Managed",
                url = "ws://127.0.0.1:18080",
                token = token,
                connectionMode = ServerConnectionMode.ManagedSsh,
                ssh = SshServerProfile(host = "host", username = "user", password = "password"),
            )
        val executor: suspend (String, ByteArray, Int) -> SshCommandResult = { command, stdin, _ ->
            val text = stdin.decodeToString()
            when {
                command == "sh -s" && text.contains("uname -s") -> {
                    val version = if (installed) "ohpi-gateway 1.10.4" else ""
                    val output =
                        "os=Linux\narch=x86_64\nhome=/home/user\nwork=/home/user\n" +
                            "pi=/usr/bin/pi\npi_path=/usr/bin:/bin\nmanager=systemd\ninstalled=$version\n" +
                            "managed=$installed\nrunning=$installed\n" +
                            "token_sha256=${if (installed) tokenHash else ""}\n"
                    SshCommandResult(0, output.encodeToByteArray(), byteArrayOf())
                }
                command.startsWith("sh -c") -> {
                    uploads += command to stdin.copyOf()
                    SshCommandResult(0, byteArrayOf(), byteArrayOf())
                }
                command == "sh -s" && text.contains("enable --now") -> {
                    installed = true
                    SshCommandResult(0, byteArrayOf(), byteArrayOf())
                }
                else -> SshCommandResult(99, byteArrayOf(), "unexpected command".encodeToByteArray())
            }
        }
        val provisioner =
            ManagedGatewayProvisioner(
                profile = profile,
                execute = executor,
                artifactSource = ManagedGatewayArtifactSource { _, _ ->
                    artifactDownloads++
                    ManagedGatewayArtifact("1.10.4", "gateway", byteArrayOf(1, 2, 3))
                },
            )

        provisioner.ensureRunning()
        provisioner.ensureRunning()

        assertEquals(1, artifactDownloads)
        assertTrue(uploads.any { it.second.contentEquals(byteArrayOf(1, 2, 3)) })
        assertTrue(uploads.any { it.second.decodeToString() == "$token\n" })
    }
}
