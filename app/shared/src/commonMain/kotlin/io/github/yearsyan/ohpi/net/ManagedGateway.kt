package io.github.yearsyan.ohpi.net

import io.github.yearsyan.ohpi.appVersion
import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.SshAuthentication
import io.github.yearsyan.ohpi.ssh.SshCommandResult
import io.github.yearsyan.ohpi.ssh.SshCommandOutput
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.ByteString.Companion.toByteString

private const val ManagedGatewayPort = 18080
private const val ManagedTaskName = "OhPi Gateway"
private const val ManagedLaunchdLabel = "io.github.yearsyan.ohpi.gateway"
private const val ManagedSystemdUnit = "ohpi-gateway.service"
private const val ReleaseRepository = "yearsyan/oh-pi-app"
private const val PiNpmPackage = "@earendil-works/pi-coding-agent"
internal const val UnixManagedInstallerSha256 =
    "1e71b6b56b20594aabc0ff175570ab33606152c7dfde56b76be017d6a14f577f"
private const val UnixManagedInstallerFallbackUrl =
    "https://raw.githubusercontent.com/yearsyan/oh-pi-app/main/scripts/install.sh"
private const val PowerShellStdin =
    "powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command -"
internal const val GATEWAY_FEATURE_SESSION_PROCESS_STOP = "session_process_stop"
internal const val GATEWAY_FEATURE_WORKSPACE_DELETE = "workspace_delete_v1"
internal const val GATEWAY_FEATURE_WORKSPACE_RESOURCES = "workspace_resources_v1"
internal const val GATEWAY_FEATURE_RUNTIME_CONFIG = "runtime_config_v1"
internal const val GATEWAY_FEATURE_SCHEDULED_TASKS = "scheduled_tasks_v1"
internal const val GATEWAY_FEATURE_SCHEDULED_TASK_SKILLS = "scheduled_task_skills_v1"
internal const val GATEWAY_FEATURE_SCHEDULED_SESSION_MANAGEMENT = "scheduled_session_management_v1"
internal const val GATEWAY_FEATURE_SCHEDULED_TASK_SESSIONS = "scheduled_task_sessions_v1"
internal const val GATEWAY_FEATURE_SCHEDULED_HTTP_TRIGGERS = "scheduled_http_triggers_v1"
internal const val ManagedGatewayHealthTimeoutMillis = 2_000L

internal enum class ManagedHostOs(val releaseName: String) {
    Macos("darwin"),
    Linux("linux"),
    Windows("windows"),
}

internal data class ManagedHostEnvironment(
    val os: ManagedHostOs,
    val architecture: String,
    val home: String,
    val workDir: String,
    val piPath: String,
    val piEnvironmentPath: String,
    val manager: String,
    val installedVersion: String,
    val managed: Boolean,
    val running: Boolean,
    val tokenSha256: String,
) {
    val installed: Boolean
        get() = installedVersion.startsWith("ohpi-gateway ")
}

/** Stages of a managed (app-supervised) gateway installation, surfaced as setup progress. */
enum class ManagedInstallStep {
    DetectingSystem,
    InstallingPi,
    DownloadingGateway,
    InstallingGateway,
    StartingGateway,
}

internal data class ManagedGatewayArtifact(
    val version: String,
    val fileName: String,
    val bytes: ByteArray,
)

internal fun interface ManagedGatewayArtifactSource {
    suspend fun download(os: ManagedHostOs, architecture: String): ManagedGatewayArtifact
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
)

@Serializable
internal data class ManagedGatewayHealth(
    val status: String,
    val service: String = "",
    val version: String = "",
    val protocol: Int = 0,
    val os: String = "",
    val features: List<String> = emptyList(),
)

internal suspend fun awaitManagedGatewayHealth(gateway: String): ManagedGatewayHealth {
    var lastFailure: Throwable? = null
    repeat(12) { attempt ->
        if (attempt > 0) delay(250)
        try {
            fetchGatewayHealthBounded(gateway)?.let { return it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            lastFailure = failure
        }
    }
    throw GatewayConnectionException(
        "Managed gateway did not become healthy",
        retryable = true,
        cause = lastFailure,
    )
}

private suspend fun fetchGatewayHealthBounded(gateway: String): ManagedGatewayHealth? =
    withTimeoutOrNull(ManagedGatewayHealthTimeoutMillis) { fetchGatewayHealth(gateway) }

/** Fetches one health snapshot; null when the gateway is unreachable or unhealthy. */
internal suspend fun fetchGatewayHealth(gateway: String): ManagedGatewayHealth? {
    val response = gatewayHttp.get(gatewayHttpBase(gateway) + "/healthz")
    if (response.status.value !in 200..299) return null
    val health = PiJson.decodeFromString<ManagedGatewayHealth>(response.bodyAsText())
    return health.takeIf {
        it.status == "ok" && it.service == "ohpi-gateway" && it.protocol > 0
    }
}

/**
 * Short non-throwing probe used by the add-server wizard: true when a healthy
 * ohpi gateway answers at [gateway] within a few attempts.
 */
internal suspend fun checkGatewayHealth(gateway: String, attempts: Int = 4): Boolean {
    repeat(attempts.coerceAtLeast(1)) { attempt ->
        if (attempt > 0) delay(250)
        try {
            if (fetchGatewayHealthBounded(gateway) != null) return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // unreachable or unparsable; keep trying until attempts run out
        }
    }
    return false
}

internal object GitHubManagedGatewayArtifactSource : ManagedGatewayArtifactSource {
    override suspend fun download(
        os: ManagedHostOs,
        architecture: String,
    ): ManagedGatewayArtifact {
        val releaseResponse =
            gatewayHttp.get("https://api.github.com/repos/$ReleaseRepository/releases/latest") {
                header(HttpHeaders.UserAgent, "oh-pi-app-managed-installer")
            }
        if (releaseResponse.status.value !in 200..299) {
            throw GatewayConnectionException(
                "Could not resolve the latest gateway release (HTTP ${releaseResponse.status.value})",
                retryable = true,
            )
        }
        val release =
            runCatching { PiJson.decodeFromString<GitHubRelease>(releaseResponse.bodyAsText()) }
                .getOrElse {
                    throw GatewayConnectionException(
                        "GitHub returned an invalid release description",
                        retryable = true,
                        cause = it,
                    )
                }
        val version = release.tagName.removePrefix("v")
        if (!version.matches(Regex("[0-9]+(?:\\.[0-9]+){1,2}"))) {
            throw GatewayConnectionException("Invalid gateway release version", retryable = false)
        }
        val fileName = managedGatewayArtifactName(version, os, architecture)
        val releaseBase =
            "https://github.com/$ReleaseRepository/releases/download/v$version"
        val (checksums, binary) =
            coroutineScope {
                val checksumRequest = async { downloadReleaseBytes("$releaseBase/SHA256SUMS.txt") }
                val binaryRequest = async { downloadReleaseBytes("$releaseBase/$fileName") }
                checksumRequest.await() to binaryRequest.await()
            }
        val expected = parseManagedGatewayChecksum(checksums.decodeToString(), fileName)
            ?: throw GatewayConnectionException(
                "Gateway release does not contain a checksum for $fileName",
                retryable = false,
            )
        val actual = binary.toByteString().sha256().hex()
        if (!actual.equals(expected, ignoreCase = true)) {
            throw GatewayConnectionException(
                "Gateway release checksum verification failed",
                retryable = false,
            )
        }
        return ManagedGatewayArtifact(version, fileName, binary)
    }

    private suspend fun downloadReleaseBytes(url: String): ByteArray {
        val response = gatewayHttp.get(url) {
            header(HttpHeaders.UserAgent, "oh-pi-app-managed-installer")
        }
        if (response.status.value !in 200..299) {
            throw GatewayConnectionException(
                "Could not download a gateway release file (HTTP ${response.status.value})",
                retryable = true,
            )
        }
        return response.body()
    }
}

internal fun managedGatewayArtifactName(
    version: String,
    os: ManagedHostOs,
    architecture: String,
): String {
    require(architecture == "amd64" || architecture == "arm64")
    val suffix = if (os == ManagedHostOs.Windows) ".exe" else ""
    return "ohpi-gateway-$version-${os.releaseName}-$architecture$suffix"
}

internal fun parseManagedGatewayChecksum(checksums: String, fileName: String): String? =
    checksums.lineSequence().map(String::trim).firstNotNullOfOrNull { line ->
        val fields = line.split(Regex("\\s+"), limit = 2)
        val listedName = fields.getOrNull(1)?.removePrefix("*")
        fields.getOrNull(0)
            ?.takeIf { listedName == fileName && it.matches(Regex("[0-9a-fA-F]{64}")) }
    }

/** Installs, starts, and stops one gateway without requiring administrator access. */
internal class ManagedGatewayProvisioner(
    private val profile: ServerProfile,
    private val execute: suspend (command: String, stdin: ByteArray, timeoutMillis: Int) -> SshCommandResult,
    private val executeStreaming: suspend (
        command: String,
        stdin: ByteArray,
        timeoutMillis: Int,
        onOutput: (SshCommandOutput) -> Unit,
    ) -> SshCommandResult = { command, stdin, timeoutMillis, _ ->
        execute(command, stdin, timeoutMillis)
    },
    private val artifactSource: ManagedGatewayArtifactSource = GitHubManagedGatewayArtifactSource,
    private val onProgress: (ManagedInstallStep) -> Unit = {},
    private val onOutput: (SshCommandOutput) -> Unit = {},
    private val unixInstallerUrl: String? = null,
    private val unixInstallerFallbackUrl: String = UnixManagedInstallerFallbackUrl,
    private val unixInstallerSha256: String = UnixManagedInstallerSha256,
) {
    suspend fun ensureRunning(forceRestart: Boolean = false) {
        onProgress(ManagedInstallStep.DetectingSystem)
        var environment = probe()
        validateEnvironment(environment, requirePi = false)
        var needsPi = environment.piPath.isBlank() || environment.piEnvironmentPath.isBlank()
        if (needsPi && environment.os == ManagedHostOs.Windows) {
            onProgress(ManagedInstallStep.InstallingPi)
            runChecked(
                PowerShellStdin,
                windowsPiInstallScript().encodeToByteArray(),
                "install pi",
                timeoutMillis = 600_000,
                streamOutput = true,
            )
            onProgress(ManagedInstallStep.DetectingSystem)
            environment = probe()
            needsPi = environment.piPath.isBlank() || environment.piEnvironmentPath.isBlank()
        }
        // The remote Unix installer owns Node.js/Pi bootstrap. Windows keeps
        // the native PowerShell path and must have Pi before provisioning.
        validateEnvironment(environment, requirePi = environment.os == ManagedHostOs.Windows)
        val expectedTokenHash =
            (profile.token + "\n").encodeToByteArray().toByteString().sha256().hex()

        if (!needsPi && !forceRestart && environment.managed && environment.running &&
            environment.tokenSha256.equals(expectedTokenHash, ignoreCase = true)
        ) {
            return
        }

        if (forceRestart && environment.managed) {
            // Best effort: a health failure can mean the manager still sees a
            // wedged process as running. The install path below remains able
            // to repair the definition if this stop/start cycle is not enough.
            execute(stopCommand(environment), stopScript(environment), 60_000)
            delay(250)
            environment = probe()
        }

        if (!needsPi && !forceRestart && environment.installed && environment.managed &&
            environment.manager != "nohup" &&
            environment.tokenSha256.equals(expectedTokenHash, ignoreCase = true)
        ) {
            val startResult =
                execute(startCommand(environment), startScript(environment), 60_000)
            if (startResult.exitStatus == 0) {
                delay(500)
                environment = probe()
                if (environment.running) return
            }
            // A stale unit, changed Windows account password, or damaged
            // service definition can make a plain start fail. Reinstalling the
            // user-level definition below is safe and repairs those cases.
        }

        val replaceUnixConfig =
            needsPi || !environment.installed || !environment.managed ||
                !environment.tokenSha256.equals(expectedTokenHash, ignoreCase = true)
        val artifact =
            if (environment.os == ManagedHostOs.Windows && !environment.installed) {
                onProgress(ManagedInstallStep.DownloadingGateway)
                artifactSource.download(environment.os, environment.architecture)
            } else {
                null
            }
        if (environment.os == ManagedHostOs.Windows) {
            onProgress(ManagedInstallStep.InstallingGateway)
            provision(environment, artifact, replaceUnixConfig = false)
        } else {
            // The canonical installer owns its internal Node/Pi, download,
            // and service phases. Its output is forwarded while it runs.
            onProgress(
                if (needsPi) ManagedInstallStep.InstallingPi
                else ManagedInstallStep.DownloadingGateway,
            )
            provision(environment, artifact = null, replaceUnixConfig)
            onProgress(ManagedInstallStep.DownloadingGateway)
            onProgress(ManagedInstallStep.InstallingGateway)
        }

        onProgress(ManagedInstallStep.StartingGateway)
        repeat(5) { attempt ->
            if (attempt > 0) delay(500)
            environment = probe()
            if (environment.running &&
                environment.tokenSha256.equals(expectedTokenHash, ignoreCase = true)
            ) {
                return
            }
        }
        val detail =
            if (environment.os == ManagedHostOs.Windows) {
                if (profile.ssh.authentication == SshAuthentication.Password) {
                    "The current-user Windows task started but the gateway did not stay running."
                } else {
                    "The current-user Windows task could not start. Private-key SSH setup requires this user to have an interactive desktop logon."
                }
            } else {
                "The user service did not reach the running state."
            }
        throw GatewayConnectionException(detail, retryable = true)
    }

    suspend fun stop() {
        val environment = probe()
        validateEnvironment(environment, requirePi = false)
        runChecked(stopCommand(environment), stopScript(environment), "stop gateway")
    }

    private suspend fun probe(): ManagedHostEnvironment {
        val unix = execute("sh -s", unixProbeScript.encodeToByteArray(), 30_000)
        if (unix.exitStatus == 0) {
            parseManagedHostEnvironment(unix.stdoutText)?.let { return it }
        }
        val windows = execute(PowerShellStdin, windowsProbeScript.encodeToByteArray(), 30_000)
        if (windows.exitStatus != 0) {
            throw commandFailure("detect remote operating system", windows)
        }
        return parseManagedHostEnvironment(windows.stdoutText)
            ?: throw GatewayConnectionException(
                "Could not understand the remote operating-system probe",
                retryable = false,
            )
    }

    private fun validateEnvironment(
        environment: ManagedHostEnvironment,
        requirePi: Boolean = true,
    ) {
        if (environment.architecture !in setOf("amd64", "arm64")) {
            throw GatewayConnectionException(
                "Gateway installation does not support remote architecture ${environment.architecture}",
                retryable = false,
            )
        }
        if (requirePi && environment.piPath.isBlank()) {
            throw GatewayConnectionException(
                "pi is not installed for the SSH user or could not be found",
                retryable = false,
            )
        }
        if (requirePi && environment.piEnvironmentPath.isBlank()) {
            throw GatewayConnectionException(
                "Could not determine the PATH needed to run pi",
                retryable = false,
            )
        }
        if (environment.manager == "unavailable" && environment.os != ManagedHostOs.Linux) {
            val manager =
                when (environment.os) {
                    ManagedHostOs.Macos -> "launchd user domain"
                    ManagedHostOs.Linux -> error("Linux uses the remote installer's nohup fallback")
                    ManagedHostOs.Windows -> "Windows Task Scheduler"
                }
            throw GatewayConnectionException(
                "$manager is unavailable for the SSH user",
                retryable = false,
            )
        }
    }

    private suspend fun provision(
        environment: ManagedHostEnvironment,
        artifact: ManagedGatewayArtifact?,
        replaceUnixConfig: Boolean,
    ) {
        if (environment.os != ManagedHostOs.Windows) {
            check(artifact == null)
            runChecked(
                unixManagedInstallCommand(
                    environment = environment,
                    installerUrl = unixInstallerUrl ?: managedUnixInstallerUrl(),
                    fallbackInstallerUrl = unixInstallerFallbackUrl,
                    installerSha256 = unixInstallerSha256,
                    replaceConfig = replaceUnixConfig,
                    reuseGateway = environment.installed,
                ),
                (profile.token + "\n").encodeToByteArray(),
                "run remote installer",
                timeoutMillis = 600_000,
                streamOutput = true,
            )
            return
        }

        artifact?.let { uploadWindows("gateway.new.exe", it.bytes) }
        uploadWindows("config.json", gatewayConfig(environment))
        uploadWindows("token", (profile.token + "\n").encodeToByteArray())
        runChecked(
            PowerShellStdin,
            windowsInstallScript(
                profile.ssh.password.takeIf {
                    profile.ssh.authentication == SshAuthentication.Password
                },
            ).encodeToByteArray(),
            "install gateway",
            streamOutput = true,
        )
    }

    private suspend fun uploadWindows(name: String, contents: ByteArray) {
        require(name.matches(Regex("[a-zA-Z0-9.]+")))
        runChecked(
            windowsUploadCommand(name),
            contents,
            "upload $name",
            timeoutMillis = 180_000,
        )
    }

    private suspend fun runChecked(
        command: String,
        stdin: ByteArray,
        operation: String,
        timeoutMillis: Int = 60_000,
        streamOutput: Boolean = false,
    ): SshCommandResult {
        val result =
            if (streamOutput) {
                executeStreaming(command, stdin, timeoutMillis, onOutput)
            } else {
                execute(command, stdin, timeoutMillis)
            }
        if (result.exitStatus != 0) throw commandFailure(operation, result)
        return result
    }

    private fun commandFailure(operation: String, result: SshCommandResult): GatewayConnectionException {
        val detail = result.stderrText.trim().ifBlank { result.stdoutText.trim() }.take(500)
        val suffix = detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
        return GatewayConnectionException(
            "Could not $operation (exit ${result.exitStatus})$suffix",
            retryable = true,
        )
    }

    private fun gatewayConfig(environment: ManagedHostEnvironment): ByteArray {
        require(environment.os == ManagedHostOs.Windows)
        val dataDir = "${environment.home}\\data"
        return buildJsonObject {
            put("OHPI_LISTEN", JsonPrimitive("127.0.0.1:$ManagedGatewayPort"))
            put("OHPI_DATA_DIR", JsonPrimitive(dataDir))
            put("OHPI_WORK_DIR", JsonPrimitive(environment.workDir))
            put("OHPI_PI_COMMAND", JsonPrimitive(environment.piPath))
            put("OHPI_PI_ENV_PATH", JsonPrimitive(environment.piEnvironmentPath))
            put("OHPI_TITLE_MODEL", JsonPrimitive("auto"))
            put("OHPI_SCHEDULED_SESSION_RETENTION", JsonPrimitive("168h"))
        }.toString().encodeToByteArray()
    }

    private fun startCommand(environment: ManagedHostEnvironment): String =
        if (environment.os == ManagedHostOs.Windows) PowerShellStdin else "sh -s"

    private fun startScript(environment: ManagedHostEnvironment): ByteArray =
        when (environment.os) {
            ManagedHostOs.Macos -> macosStartScript
            ManagedHostOs.Linux -> linuxStartScript
            ManagedHostOs.Windows -> windowsStartScript
        }.encodeToByteArray()

    private fun stopCommand(environment: ManagedHostEnvironment): String =
        if (environment.os == ManagedHostOs.Windows) PowerShellStdin else "sh -s"

    private fun stopScript(environment: ManagedHostEnvironment): ByteArray =
        when (environment.os) {
            ManagedHostOs.Macos -> macosStopScript
            ManagedHostOs.Linux -> linuxStopScript
            ManagedHostOs.Windows -> windowsStopScript
        }.encodeToByteArray()
}

internal fun parseManagedHostEnvironment(output: String): ManagedHostEnvironment? {
    val values = output.lineSequence().map(String::trim).mapNotNull { line ->
        val index = line.indexOf('=')
        if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
    }.toMap()
    val os =
        when (values["os"]?.lowercase()) {
            "darwin", "macos" -> ManagedHostOs.Macos
            "linux" -> ManagedHostOs.Linux
            "windows" -> ManagedHostOs.Windows
            else -> return null
        }
    val architecture =
        when (values["arch"]?.lowercase()) {
            "x86_64", "x64", "amd64" -> "amd64"
            "aarch64", "arm64" -> "arm64"
            else -> values["arch"].orEmpty().lowercase()
        }
    return ManagedHostEnvironment(
        os = os,
        architecture = architecture,
        home = values["home"].orEmpty(),
        workDir = values["work"].orEmpty().ifBlank { values["home"].orEmpty() },
        piPath = values["pi"].orEmpty(),
        piEnvironmentPath = values["pi_path"].orEmpty(),
        manager = values["manager"].orEmpty().ifBlank { "unavailable" },
        installedVersion = values["installed"].orEmpty(),
        managed = values["managed"]?.toBooleanStrictOrNull() ?: false,
        running = values["running"]?.toBooleanStrictOrNull() ?: false,
        tokenSha256 = values["token_sha256"].orEmpty(),
    )
}

/** Version-pinned source for the canonical Unix installer shipped with this App. */
internal fun managedUnixInstallerUrl(version: String = appVersion()): String {
    val normalized = version.removePrefix("v")
    if (!normalized.matches(Regex("[0-9]+(?:\\.[0-9]+){1,2}"))) {
        throw GatewayConnectionException("Invalid App version for managed installer", false)
    }
    return "https://raw.githubusercontent.com/$ReleaseRepository/v$normalized/scripts/install.sh"
}

/**
 * Downloads and verifies the canonical installer on the SSH host, then lets it
 * own all Unix Node.js/Pi, gateway, and user-service installation. The gateway
 * token is not included in the command or environment: install.sh reads one
 * line from SSH stdin. curl is explicitly detached from that stdin.
 */
internal fun unixManagedInstallCommand(
    environment: ManagedHostEnvironment,
    installerUrl: String = managedUnixInstallerUrl(),
    fallbackInstallerUrl: String = UnixManagedInstallerFallbackUrl,
    installerSha256: String = UnixManagedInstallerSha256,
    replaceConfig: Boolean = true,
    reuseGateway: Boolean = false,
): String {
    require(environment.os == ManagedHostOs.Macos || environment.os == ManagedHostOs.Linux)
    require(installerSha256.matches(Regex("[0-9a-f]{64}")))
    val replaceConfigValue = if (replaceConfig) "1" else ""
    val reuseGatewayValue = if (reuseGateway) "1" else ""
    val body =
        "set -eu; umask 077; directory=\"\$HOME/.cache/oh-pi-app\"; " +
            "candidate=\"\$directory/install.sh.new\"; cached=\"\$directory/install.sh\"; " +
            "expected=${posixShellQuote(installerSha256)}; mkdir -p \"\$directory\"; " +
            "if command -v sha256sum >/dev/null 2>&1; then " +
            "checksum() { sha256sum \"\$1\" | sed 's/[[:space:]].*//'; }; " +
            "elif command -v shasum >/dev/null 2>&1; then " +
            "checksum() { shasum -a 256 \"\$1\" | sed 's/[[:space:]].*//'; }; " +
            "elif command -v openssl >/dev/null 2>&1; then " +
            "checksum() { openssl dgst -sha256 \"\$1\" | sed 's/^.*= //'; }; " +
            "else echo 'no SHA-256 tool found for managed installer' >&2; exit 41; fi; " +
            "source=''; " +
            "if curl </dev/null -fsSL ${posixShellQuote(installerUrl)} -o \"\$candidate\" 2>/dev/null && " +
            "[ \"\$(checksum \"\$candidate\")\" = \"\$expected\" ]; then source=\"\$candidate\"; " +
            "else rm -f \"\$candidate\"; fi; " +
            "if [ -z \"\$source\" ] && curl </dev/null -fsSL " +
            "${posixShellQuote(fallbackInstallerUrl)} -o \"\$candidate\" 2>/dev/null && " +
            "[ \"\$(checksum \"\$candidate\")\" = \"\$expected\" ]; then source=\"\$candidate\"; " +
            "else [ -n \"\$source\" ] || rm -f \"\$candidate\"; fi; " +
            "if [ -z \"\$source\" ] && [ -f \"\$cached\" ] && " +
            "[ \"\$(checksum \"\$cached\")\" = \"\$expected\" ]; then source=\"\$cached\"; fi; " +
            "[ -n \"\$source\" ] || { echo 'managed installer download or checksum verification failed' >&2; exit 42; }; " +
            "if [ \"\$source\" = \"\$candidate\" ]; then mv -f \"\$candidate\" \"\$cached\"; fi; " +
            "OHPI_VERSION='' OHPI_REPO=${posixShellQuote(ReleaseRepository)} " +
            "OHPI_LISTEN='127.0.0.1:$ManagedGatewayPort' " +
            "OHPI_DATA_DIR=${posixShellQuote(environment.home + "/.local/state/oh-pi-app")} " +
            "OHPI_WORK_DIR=${posixShellQuote(environment.workDir)} OHPI_TOKEN='' " +
            "OHPI_NO_PI_INSTALL='' OHPI_HEALTH_URL='http://127.0.0.1:$ManagedGatewayPort/healthz' " +
            "OHPI_NO_SERVICE='' OHPI_PI_COMMAND=${posixShellQuote(environment.piPath)} " +
            "OHPI_PI_ENV_PATH=${posixShellQuote(environment.piEnvironmentPath)} " +
            "OHPI_REPLACE_CONFIG=$replaceConfigValue " +
            "OHPI_REUSE_GATEWAY=$reuseGatewayValue " +
            "OHPI_TOKEN_STDIN=1 sh \"\$cached\""
    return "sh -c ${posixShellQuote(body)}"
}

internal fun posixShellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

private fun windowsUploadCommand(name: String): String =
    powershellInline(
        """
        §ErrorActionPreference='Stop';
        §dir=Join-Path §env:LOCALAPPDATA 'OhPi\cache';
        [IO.Directory]::CreateDirectory(§dir)|Out-Null;
        §path=Join-Path §dir '$name';
        §input=[Console]::OpenStandardInput();
        §file=[IO.File]::Open(§path,[IO.FileMode]::Create,[IO.FileAccess]::Write,[IO.FileShare]::None);
        try { §input.CopyTo(§file) } finally { §file.Dispose() }
        """,
    )

private fun powershellInline(script: String): String =
    "powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " +
        base64EncodeUtf16Le(script.trimIndent().replace('§', '$'))

private fun base64EncodeUtf16Le(value: String): String {
    val bytes = ByteArray(value.length * 2)
    value.forEachIndexed { index, char ->
        bytes[index * 2] = (char.code and 0xff).toByte()
        bytes[index * 2 + 1] = (char.code ushr 8).toByte()
    }
    return base64Encode(bytes)
}

private fun base64Encode(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    return buildString(((bytes.size + 2) / 3) * 4) {
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xff
            val second = bytes.getOrNull(index + 1)?.toInt()?.and(0xff)
            val third = bytes.getOrNull(index + 2)?.toInt()?.and(0xff)
            append(alphabet[first ushr 2])
            append(alphabet[((first and 0x03) shl 4) or ((second ?: 0) ushr 4)])
            append(if (second == null) '=' else alphabet[((second and 0x0f) shl 2) or ((third ?: 0) ushr 6)])
            append(if (third == null) '=' else alphabet[third and 0x3f])
            index += 3
        }
    }
}

private fun script(contents: String): String =
    contents.trimIndent().replace('§', '$') + "\n"

private val unixProbeScript =
    script(
        """
        set -u
        os=§(uname -s 2>/dev/null) || exit 20
        arch=§(uname -m 2>/dev/null) || exit 21
        home=§HOME
        pi_path=§(command -v pi 2>/dev/null || true)
        if [ -z "§pi_path" ]; then
          for candidate in \
            "§HOME/.local/bin/pi" \
            "§HOME/.local/share/fnm/aliases/default/bin/pi" \
            "§HOME/.volta/bin/pi" \
            "§HOME/.local/share/pnpm/pi" \
            "§HOME/.bun/bin/pi" \
            "§HOME"/.nvm/versions/node/*/bin/pi \
            /opt/homebrew/bin/pi /usr/local/bin/pi; do
            if [ -x "§candidate" ]; then
              pi_path=§candidate
              break
            fi
          done
        fi
        if [ -n "§pi_path" ] && command -v realpath >/dev/null 2>&1; then
          resolved_pi=§(realpath "§pi_path" 2>/dev/null || true)
          case "§resolved_pi" in
            */installation/lib/node_modules/*)
              installation=§{resolved_pi%%/lib/node_modules/*}
              [ ! -x "§installation/bin/pi" ] || pi_path="§installation/bin/pi"
              ;;
          esac
        fi
        pi_environment_path=§{PATH:-/usr/local/bin:/usr/bin:/bin}
        managed_node_bin="§HOME/.local/share/oh-pi-app/node/current/bin"
        [ ! -x "§managed_node_bin/node" ] || pi_environment_path="§managed_node_bin:§pi_environment_path"
        [ -z "§pi_path" ] || pi_environment_path="§(dirname "§pi_path"):§pi_environment_path"
        bin="§HOME/.local/bin/ohpi-gateway"
        config="§HOME/.config/oh-pi-app"
        installed=""
        [ ! -x "§bin" ] || installed=§("§bin" --version 2>/dev/null | head -n 1 || true)
        managed=false
        running=false
        manager=unavailable
        if [ "§os" = Darwin ]; then
          manager=launchd
          [ ! -f "§HOME/Library/LaunchAgents/$ManagedLaunchdLabel.plist" ] || managed=true
          uid=§(id -u)
          domain="user/§uid"
          launchctl print "gui/§uid" >/dev/null 2>&1 && domain="gui/§uid"
          launchctl print "§domain/$ManagedLaunchdLabel" 2>/dev/null | grep -q 'state = running' && running=true
        elif [ "§os" = Linux ]; then
          if systemctl --user show-environment >/dev/null 2>&1; then
            manager=systemd
            [ ! -f "§HOME/.config/systemd/user/$ManagedSystemdUnit" ] || managed=true
            systemctl --user is-active --quiet $ManagedSystemdUnit >/dev/null 2>&1 && running=true
          elif [ -x "§HOME/.local/libexec/oh-pi-app/ohpi-gateway-launch" ]; then
            manager=nohup
            managed=true
            pid_file="§HOME/.local/state/oh-pi-app/gateway.pid"
            if [ -f "§pid_file" ]; then
              pid=§(sed -n '1p' "§pid_file")
              if [ -n "§pid" ] && kill -0 "§pid" >/dev/null 2>&1; then
                running=true
              fi
            fi
          fi
        fi
        token_sha256=""
        if [ -f "§config/token" ]; then
          if command -v sha256sum >/dev/null 2>&1; then
            token_sha256=§(sha256sum "§config/token" | awk '{print §1}')
          elif command -v shasum >/dev/null 2>&1; then
            token_sha256=§(shasum -a 256 "§config/token" | awk '{print §1}')
          fi
        fi
        printf 'os=%s\narch=%s\nhome=%s\nwork=%s\npi=%s\npi_path=%s\nmanager=%s\ninstalled=%s\nmanaged=%s\nrunning=%s\ntoken_sha256=%s\n' \
          "§os" "§arch" "§home" "§home" "§pi_path" "§pi_environment_path" "§manager" "§installed" "§managed" "§running" "§token_sha256"
        """,
    )

private val windowsProbeScript =
    script(
        """
        §ErrorActionPreference='Stop'
        §base=Join-Path §env:LOCALAPPDATA 'OhPi'
        §managedNodeBin=Join-Path §base 'node\current'
        §pi=Get-Command pi -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        §piPath=if (§null -eq §pi) { '' } else { §pi.Source }
        if ([string]::IsNullOrEmpty(§piPath)) {
          §candidates=@(
            (Join-Path §base 'pi\pi.cmd'),
            (Join-Path §env:APPDATA 'npm\pi.cmd'),
            (Join-Path §env:LOCALAPPDATA 'pnpm\pi.cmd'),
            (Join-Path §env:USERPROFILE '.volta\bin\pi.cmd'),
            (Join-Path §env:USERPROFILE '.bun\bin\pi.exe')
          )
          foreach (§candidate in §candidates) {
            if (Test-Path -LiteralPath §candidate -PathType Leaf) { §piPath=§candidate; break }
          }
        }
        §piDir=if ([string]::IsNullOrEmpty(§piPath)) { '' } else { Split-Path -Parent §piPath }
        §piEnvironmentPath=if (Test-Path (Join-Path §managedNodeBin 'node.exe')) { "§managedNodeBin;§env:PATH" } else { §env:PATH }
        if (-not [string]::IsNullOrEmpty(§piDir)) { §piEnvironmentPath="§piDir;§piEnvironmentPath" }
        §bin=Join-Path §base 'bin\ohpi-gateway.exe'
        §installed=if (Test-Path §bin) { (& §bin --version 2>§null | Select-Object -First 1) } else { '' }
        §task=Get-ScheduledTask -TaskName '$ManagedTaskName' -ErrorAction SilentlyContinue
        §managed=§null -ne §task
        §running=§managed -and §task.State -eq 'Running'
        §token=Join-Path §base 'config\token'
        §tokenHash=if (Test-Path §token) { (Get-FileHash -Algorithm SHA256 §token).Hash.ToLowerInvariant() } else { '' }
        §arch=[Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
        Write-Output 'os=Windows'
        Write-Output "arch=§arch"
        Write-Output "home=§base"
        Write-Output "work=§env:USERPROFILE"
        Write-Output "pi=§piPath"
        Write-Output "pi_path=§piEnvironmentPath"
        Write-Output 'manager=task_scheduler'
        Write-Output "installed=§installed"
        Write-Output "managed=§(§managed.ToString().ToLowerInvariant())"
        Write-Output "running=§(§running.ToString().ToLowerInvariant())"
        Write-Output "token_sha256=§tokenHash"
        """,
    )

internal fun windowsPiInstallScript(): String =
    script(
        """
        §ErrorActionPreference='Stop'
        §ProgressPreference='SilentlyContinue'
        §base=Join-Path §env:LOCALAPPDATA 'OhPi'
        §piPrefix=Join-Path §base 'pi'
        §pi=Join-Path §piPrefix 'pi.cmd'
        §managedNodeBin=Join-Path §base 'node\current'
        if (Test-Path -LiteralPath §pi -PathType Leaf) {
          §env:PATH="§managedNodeBin;§piPrefix;§env:PATH"
          & §pi --version *>§null
          if (§LASTEXITCODE -eq 0) { exit 0 }
        }

        §nodeCommand=Get-Command node.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        §npmCommand=Get-Command npm.cmd -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        §compatible=§false
        if (§null -ne §nodeCommand -and §null -ne §npmCommand) {
          & §nodeCommand.Source -e 'const [major, minor, patch] = process.versions.node.split(".").map(Number); process.exit(major > 22 || (major === 22 && (minor > 19 || (minor === 19 && patch >= 0))) ? 0 : 1)'
          §compatible=§LASTEXITCODE -eq 0
        }
        if (§compatible) {
          §nodeBin=Split-Path -Parent §nodeCommand.Source
          §npmPath=§npmCommand.Source
        } else {
          §runtimeArch=[Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
          §nodeArch=switch (§runtimeArch) { 'X64' { 'x64' } 'Arm64' { 'arm64' } default { throw "unsupported architecture for Node.js: §runtimeArch" } }
          §nodeDist='https://nodejs.org/dist/latest-v22.x'
          §tmp=Join-Path ([IO.Path]::GetTempPath()) ('ohpi-node-'+[Guid]::NewGuid().ToString('N'))
          New-Item -ItemType Directory -Force -Path §tmp | Out-Null
          try {
            §checksums=Join-Path §tmp 'SHASUMS256.txt'
            Invoke-WebRequest -UseBasicParsing -Uri "§nodeDist/SHASUMS256.txt" -OutFile §checksums
            §pattern='^([0-9a-fA-F]{64})\s+(node-v22\.[0-9]+\.[0-9]+-win-'+[Regex]::Escape(§nodeArch)+'\.zip)$'
            §selected=Get-Content -LiteralPath §checksums | ForEach-Object {
              if (§_ -match §pattern) { "§(§Matches[1])|§(§Matches[2])" }
            } | Select-Object -First 1
            if ([string]::IsNullOrEmpty(§selected)) { throw 'could not resolve a compatible Node.js archive' }
            §parts=§selected -split '\|',2
            §expected=§parts[0].ToLowerInvariant()
            §nodeFile=§parts[1]
            §archive=Join-Path §tmp §nodeFile
            Invoke-WebRequest -UseBasicParsing -Uri "§nodeDist/§nodeFile" -OutFile §archive
            §actual=(Get-FileHash -Algorithm SHA256 -LiteralPath §archive).Hash.ToLowerInvariant()
            if (§actual -ne §expected) { throw 'Node.js checksum verification failed' }
            §extract=Join-Path §tmp 'extract'
            Expand-Archive -LiteralPath §archive -DestinationPath §extract
            §extracted=Get-ChildItem -LiteralPath §extract -Directory | Select-Object -First 1
            if (§null -eq §extracted) { throw 'Node.js archive was empty' }
            §nodeRoot=Join-Path §base 'node'
            New-Item -ItemType Directory -Force -Path §nodeRoot | Out-Null
            if (Test-Path -LiteralPath §managedNodeBin) { Remove-Item -LiteralPath §managedNodeBin -Recurse -Force }
            Move-Item -LiteralPath §extracted.FullName -Destination §managedNodeBin
            §nodeBin=§managedNodeBin
            §npmPath=Join-Path §nodeBin 'npm.cmd'
          } finally {
            if (Test-Path -LiteralPath §tmp) { Remove-Item -LiteralPath §tmp -Recurse -Force }
          }
        }

        §env:PATH="§nodeBin;§piPrefix;§env:PATH"
        New-Item -ItemType Directory -Force -Path §piPrefix | Out-Null
        & §npmPath install -g --ignore-scripts --prefix §piPrefix --no-fund --no-audit '$PiNpmPackage'
        if (§LASTEXITCODE -ne 0) { throw 'npm could not install Pi' }
        if (-not (Test-Path -LiteralPath §pi -PathType Leaf)) { throw 'Pi executable is missing after installation' }
        & §pi --version *>§null
        if (§LASTEXITCODE -ne 0) { throw 'Pi executable could not start after installation' }
        """,
    )

internal fun windowsInstallScript(password: String?): String {
    val principalAndRegistration =
        if (password != null) {
            val encodedPassword = base64Encode(password.encodeToByteArray())
            """
            §taskPassword=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$encodedPassword'))
            §principal=New-ScheduledTaskPrincipal -UserId §user -LogonType Password -RunLevel Limited
            §task=New-ScheduledTask -Action §action -Trigger §trigger -Principal §principal -Settings §settings
            Register-ScheduledTask -TaskName '$ManagedTaskName' -InputObject §task -User §user -Password §taskPassword -Force | Out-Null
            §taskPassword=§null
            """.trimIndent()
        } else {
            """
            §principal=New-ScheduledTaskPrincipal -UserId §user -LogonType Interactive -RunLevel Limited
            §task=New-ScheduledTask -Action §action -Trigger §trigger -Principal §principal -Settings §settings
            Register-ScheduledTask -TaskName '$ManagedTaskName' -InputObject §task -Force | Out-Null
            """.trimIndent()
        }
    return script(
        """
        §ErrorActionPreference='Stop'
        §base=Join-Path §env:LOCALAPPDATA 'OhPi'
        §cache=Join-Path §base 'cache'
        §binDir=Join-Path §base 'bin'
        §configDir=Join-Path §base 'config'
        §dataDir=Join-Path §base 'data'
        §logDir=Join-Path §base 'logs'
        New-Item -ItemType Directory -Force -Path §binDir,§configDir,§dataDir,§logDir | Out-Null
        §existing=Get-ScheduledTask -TaskName '$ManagedTaskName' -ErrorAction SilentlyContinue
        if (§null -ne §existing) { Stop-ScheduledTask -TaskName '$ManagedTaskName' -ErrorAction SilentlyContinue }
        §staged=Join-Path §cache 'gateway.new.exe'
        §bin=Join-Path §binDir 'ohpi-gateway.exe'
        if (Test-Path §staged) { Move-Item -Force §staged §bin; Unblock-File §bin -ErrorAction SilentlyContinue }
        if (-not (Test-Path §bin)) { throw 'gateway binary is missing' }
        Move-Item -Force (Join-Path §cache 'config.json') (Join-Path §configDir 'config.json')
        Move-Item -Force (Join-Path §cache 'token') (Join-Path §configDir 'token')
        §arguments='--config "'+(Join-Path §configDir 'config.json')+'" --token-file "'+(Join-Path §configDir 'token')+'"'
        §action=New-ScheduledTaskAction -Execute §bin -Argument §arguments -WorkingDirectory §env:USERPROFILE
        §user=[Security.Principal.WindowsIdentity]::GetCurrent().Name
        §trigger=New-ScheduledTaskTrigger -AtLogOn -User §user
        §settings=New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)
        $principalAndRegistration
        Start-ScheduledTask -TaskName '$ManagedTaskName'
        """,
    )
}

private val macosStartScript =
    script(
        """
        set -eu
        uid=§(id -u)
        domain="user/§uid"
        launchctl print "gui/§uid" >/dev/null 2>&1 && domain="gui/§uid"
        plist="§HOME/Library/LaunchAgents/$ManagedLaunchdLabel.plist"
        launchctl print "§domain/$ManagedLaunchdLabel" >/dev/null 2>&1 || launchctl bootstrap "§domain" "§plist"
        launchctl enable "§domain/$ManagedLaunchdLabel"
        launchctl kickstart -k "§domain/$ManagedLaunchdLabel"
        """,
    )

private val linuxStartScript =
    script(
        """
        set -eu
        systemctl --user start $ManagedSystemdUnit
        """,
    )

private val windowsStartScript =
    script(
        """
        §ErrorActionPreference='Stop'
        Start-ScheduledTask -TaskName '$ManagedTaskName'
        """,
    )

private val macosStopScript =
    script(
        """
        set -eu
        uid=§(id -u)
        domain="user/§uid"
        launchctl print "gui/§uid" >/dev/null 2>&1 && domain="gui/§uid"
        launchctl bootout "§domain/$ManagedLaunchdLabel" >/dev/null 2>&1 || true
        """,
    )

private val linuxStopScript =
    script(
        """
        set -eu
        if systemctl --user show-environment >/dev/null 2>&1; then
          systemctl --user stop $ManagedSystemdUnit
          exit 0
        fi
        pid_file="§HOME/.local/state/oh-pi-app/gateway.pid"
        [ -f "§pid_file" ] || exit 0
        pid=§(sed -n '1p' "§pid_file")
        if [ -n "§pid" ] && kill -0 "§pid" >/dev/null 2>&1; then
          kill "§pid" >/dev/null 2>&1 || true
          attempt=0
          while kill -0 "§pid" >/dev/null 2>&1 && [ "§attempt" -lt 20 ]; do
            attempt=§((attempt + 1))
            sleep 1
          done
        fi
        rm -f "§pid_file"
        """,
    )

private val windowsStopScript =
    script(
        """
        §ErrorActionPreference='Stop'
        Stop-ScheduledTask -TaskName '$ManagedTaskName' -ErrorAction SilentlyContinue
        """,
    )
