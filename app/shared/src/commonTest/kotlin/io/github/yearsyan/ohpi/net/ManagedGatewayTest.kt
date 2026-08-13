package io.github.yearsyan.ohpi.net

import io.github.yearsyan.ohpi.data.ServerConnectionMode
import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.SshServerProfile
import io.github.yearsyan.ohpi.ssh.SshCommandOutput
import io.github.yearsyan.ohpi.ssh.SshCommandResult
import io.github.yearsyan.ohpi.ssh.SshCommandStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okio.ByteString.Companion.toByteString

private const val TestUnixInstallerUrl =
    "https://raw.githubusercontent.com/yearsyan/oh-pi-app/v9.9.9/scripts/install.sh"

class ManagedGatewayTest {
    @Test
    fun decodesGatewayVersionAndFeatures() {
        val health =
            PiJson.decodeFromString<ManagedGatewayHealth>(
                """{"status":"ok","service":"ohpi-gateway","version":"1.11.0","protocol":1,"os":"darwin","features":["session_process_stop","workspace_delete_v1"]}""",
            )

        assertEquals("1.11.0", health.version)
        assertEquals(1, health.protocol)
        assertEquals("darwin", health.os)
        assertEquals(
            listOf(GATEWAY_FEATURE_SESSION_PROCESS_STOP, GATEWAY_FEATURE_WORKSPACE_DELETE),
            health.features,
        )

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
    fun pinsUnixInstallerToAppReleaseTag() {
        assertEquals(
            "https://raw.githubusercontent.com/yearsyan/oh-pi-app/v2.4.1/scripts/install.sh",
            managedUnixInstallerUrl("2.4.1"),
        )
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
    fun unixUsesCanonicalRemoteInstallerAndWindowsKeepsNativeBootstrap() {
        val environment =
            ManagedHostEnvironment(
                os = ManagedHostOs.Linux,
                architecture = "amd64",
                home = "/home/user",
                workDir = "/home/user",
                piPath = "/home/user/pi's/bin/pi",
                piEnvironmentPath = "/home/user/pi's/bin:/usr/bin:/bin",
                manager = "systemd",
                installedVersion = "",
                managed = false,
                running = false,
                tokenSha256 = "",
            )
        val installerUrl = managedUnixInstallerUrl("9.9.9")
        val unix = unixManagedInstallCommand(environment, installerUrl = installerUrl)
        assertTrue(unix.contains(installerUrl))
        assertTrue(unix.contains("main/scripts/install.sh"))
        assertTrue(unix.contains("curl </dev/null -fsSL"))
        assertTrue(unix.contains("managed installer download or checksum verification failed"))
        assertTrue(unix.contains("OHPI_REPO="))
        assertTrue(unix.contains("yearsyan/oh-pi-app"))
        assertTrue(unix.contains("OHPI_LISTEN="))
        assertTrue(unix.contains("127.0.0.1:18080"))
        assertTrue(unix.contains("OHPI_NO_SERVICE="))
        assertTrue(unix.contains("OHPI_REPLACE_CONFIG=1"))
        assertTrue(unix.contains("OHPI_PROGRESS_PROTOCOL=1"))
        assertTrue(unix.contains("OHPI_TOKEN_STDIN=1"))
        assertEquals("'pi'\"'\"'s'", posixShellQuote("pi's"))
        assertTrue(!unix.contains("nodejs.org"))

        val windows = windowsPiInstallScript()
        assertTrue(windows.contains("nodejs.org/dist/latest-v22.x"))
        assertTrue(windows.contains("Get-FileHash -Algorithm SHA256"))
        assertTrue(windows.contains("--ignore-scripts"))
        assertTrue(!windows.contains("RunLevel Highest"))
    }

    @Test
    fun acceptsLinuxWithoutSystemdForRemoteInstallerFallback() {
        val environment =
            parseManagedHostEnvironment(
                """
                os=Linux
                arch=x86_64
                home=/home/user
                work=/home/user
                pi=/usr/bin/pi
                pi_path=/usr/bin:/bin
                manager=unavailable
                installed=
                managed=false
                running=false
                token_sha256=
                """.trimIndent(),
            )
        assertNotNull(environment)
        assertEquals("unavailable", environment.manager)
        assertTrue(
            unixManagedInstallCommand(environment, installerUrl = TestUnixInstallerUrl)
                .contains(TestUnixInstallerUrl),
        )
    }

    @Test
    fun delegatesMissingPiAndGatewayInstallToRemoteScript() = kotlinx.coroutines.test.runTest {
        val token = "managed-bootstrap-token"
        val tokenHash = (token + "\n").encodeToByteArray().toByteString().sha256().hex()
        var installed = false
        var remoteInstallRuns = 0
        var artifactDownloads = 0
        val streamedLines = mutableListOf<String>()
        val progress = mutableListOf<ManagedInstallStep>()
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
                    val pi = if (installed) "/home/user/.local/bin/pi" else ""
                    val version = if (installed) "ohpi-gateway 1.10.5" else ""
                    val output =
                        "os=Linux\narch=x86_64\nhome=/home/user\nwork=/home/user\n" +
                            "pi=$pi\npi_path=/home/user/.local/bin:/usr/bin:/bin\nmanager=systemd\n" +
                            "installed=$version\nmanaged=$installed\nrunning=$installed\n" +
                            "token_sha256=${if (installed) tokenHash else ""}\n"
                    SshCommandResult(0, output.encodeToByteArray(), byteArrayOf())
                }
                command.startsWith("sh -c") && command.contains(TestUnixInstallerUrl) -> {
                    assertTrue(!command.contains(token))
                    assertTrue(command.contains("OHPI_REPLACE_CONFIG=1"))
                    assertTrue(command.contains("OHPI_REUSE_GATEWAY="))
                    assertTrue(!command.contains("OHPI_REUSE_GATEWAY=1"))
                    assertEquals("$token\n", text)
                    remoteInstallRuns++
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
                executeStreaming = { command, stdin, timeout, onOutput ->
                    if (command.contains(TestUnixInstallerUrl)) {
                        onOutput(
                            SshCommandOutput(
                                SshCommandStream.Stdout,
                                "@@OHPI_PROGRESS:installing_pi@@\n".encodeToByteArray(),
                            ),
                        )
                        onOutput(
                            SshCommandOutput(
                                SshCommandStream.Stdout,
                                "@@OHPI_PROGRESS:downloading_".encodeToByteArray(),
                            ),
                        )
                        onOutput(
                            SshCommandOutput(
                                SshCommandStream.Stdout,
                                (
                                    "gateway@@\n==> Downloading gateway\n" +
                                        "@@OHPI_PROGRESS:installing_gateway@@\n" +
                                        "@@OHPI_PROGRESS:starting_gateway@@\n"
                                ).encodeToByteArray(),
                            ),
                        )
                    }
                    executor(command, stdin, timeout)
                },
                artifactSource = ManagedGatewayArtifactSource { _, _ ->
                    artifactDownloads++
                    ManagedGatewayArtifact("1.10.5", "gateway", byteArrayOf(1, 2, 3))
                },
                onOutput = { output -> streamedLines += output.bytes.decodeToString() },
                onProgress = progress::add,
                unixInstallerUrl = TestUnixInstallerUrl,
            )

        provisioner.ensureRunning()

        assertEquals(1, remoteInstallRuns)
        assertEquals(0, artifactDownloads)
        assertEquals(listOf("==> Downloading gateway\n"), streamedLines)
        assertEquals(
            listOf(
                ManagedInstallStep.DetectingSystem,
                ManagedInstallStep.InstallingPi,
                ManagedInstallStep.DownloadingGateway,
                ManagedInstallStep.InstallingGateway,
                ManagedInstallStep.StartingGateway,
            ),
            progress.distinct(),
        )
        assertTrue(installed)
    }

    @Test
    fun adoptsExistingUnmanagedGatewayWithManagedConfig() = kotlinx.coroutines.test.runTest {
        val token = "managed-adoption-token"
        val tokenHash = (token + "\n").encodeToByteArray().toByteString().sha256().hex()
        var managed = false
        var installRuns = 0
        val profile =
            ServerProfile(
                id = "adopt",
                name = "Adopt",
                url = "ws://127.0.0.1:18080",
                token = token,
                connectionMode = ServerConnectionMode.ManagedSsh,
                ssh = SshServerProfile(host = "host", username = "user", password = "password"),
            )
        val executor: suspend (String, ByteArray, Int) -> SshCommandResult = { command, stdin, _ ->
            when {
                command == "sh -s" && stdin.decodeToString().contains("uname -s") -> {
                    val output =
                        "os=Linux\narch=x86_64\nhome=/home/user\nwork=/home/user\n" +
                            "pi=/usr/bin/pi\npi_path=/usr/bin:/bin\nmanager=systemd\n" +
                            "installed=ohpi-gateway 1.10.5\nmanaged=$managed\nrunning=$managed\n" +
                            "token_sha256=$tokenHash\n"
                    SshCommandResult(0, output.encodeToByteArray(), byteArrayOf())
                }
                command.startsWith("sh -c") && command.contains(TestUnixInstallerUrl) -> {
                    assertTrue(command.contains("OHPI_REPLACE_CONFIG=1"))
                    assertTrue(command.contains("OHPI_REUSE_GATEWAY=1"))
                    assertEquals("$token\n", stdin.decodeToString())
                    installRuns++
                    managed = true
                    SshCommandResult(0, byteArrayOf(), byteArrayOf())
                }
                else -> SshCommandResult(99, byteArrayOf(), "unexpected command".encodeToByteArray())
            }
        }

        ManagedGatewayProvisioner(
            profile = profile,
            execute = executor,
            unixInstallerUrl = TestUnixInstallerUrl,
        ).ensureRunning()

        assertEquals(1, installRuns)
        assertTrue(managed)
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
                text.contains("systemctl --user start") ->
                    SshCommandResult(1, byteArrayOf(), "stale task".encodeToByteArray())
                command.startsWith("sh -c") && command.contains(TestUnixInstallerUrl) -> {
                    assertTrue(command.contains("OHPI_REUSE_GATEWAY=1"))
                    assertTrue(command.contains("OHPI_REPLACE_CONFIG="))
                    assertTrue(!command.contains("OHPI_REPLACE_CONFIG=1"))
                    assertEquals("$token\n", text)
                    installRuns++
                    running = true
                    SshCommandResult(0, byteArrayOf(), byteArrayOf())
                }
                else -> SshCommandResult(99, byteArrayOf(), "unexpected command".encodeToByteArray())
            }
        }

        ManagedGatewayProvisioner(
            profile = profile,
            execute = executor,
            unixInstallerUrl = TestUnixInstallerUrl,
        ).ensureRunning()

        assertEquals(1, installRuns)
    }

    @Test
    fun provisionsOnceThenTreatsRunningHostAsIdempotent() = kotlinx.coroutines.test.runTest {
        val token = "managed-test-token"
        val tokenHash = (token + "\n").encodeToByteArray().toByteString().sha256().hex()
        var installed = false
        var artifactDownloads = 0
        val remoteInstallerInputs = mutableListOf<ByteArray>()
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
                command.startsWith("sh -c") && command.contains(TestUnixInstallerUrl) -> {
                    remoteInstallerInputs += stdin.copyOf()
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
                unixInstallerUrl = TestUnixInstallerUrl,
            )

        provisioner.ensureRunning()
        provisioner.ensureRunning()

        assertEquals(0, artifactDownloads)
        assertEquals(1, remoteInstallerInputs.size)
        assertEquals("$token\n", remoteInstallerInputs.single().decodeToString())
    }
}
