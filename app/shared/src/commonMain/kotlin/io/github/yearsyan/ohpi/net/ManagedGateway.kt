package io.github.yearsyan.ohpi.net

import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.SshAuthentication
import io.github.yearsyan.ohpi.ssh.SshCommandResult
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
private const val PowerShellStdin =
    "powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command -"

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
)

internal suspend fun awaitManagedGatewayHealth(gateway: String): ManagedGatewayHealth {
    var lastFailure: Throwable? = null
    repeat(12) { attempt ->
        if (attempt > 0) delay(250)
        try {
            val response = gatewayHttp.get(gatewayHttpBase(gateway) + "/healthz")
            if (response.status.value in 200..299) {
                val health = PiJson.decodeFromString<ManagedGatewayHealth>(response.bodyAsText())
                if (health.status == "ok" && health.service == "ohpi-gateway" && health.protocol > 0) {
                    return health
                }
            }
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
    private val artifactSource: ManagedGatewayArtifactSource = GitHubManagedGatewayArtifactSource,
) {
    suspend fun ensureRunning(forceRestart: Boolean = false) {
        var environment = probe()
        validateEnvironment(environment, requirePi = false)
        if (environment.piPath.isBlank() || environment.piEnvironmentPath.isBlank()) {
            runChecked(
                piInstallCommand(environment),
                piInstallScript(environment),
                "install pi",
                timeoutMillis = 600_000,
            )
            environment = probe()
        }
        validateEnvironment(environment)
        val expectedTokenHash =
            (profile.token + "\n").encodeToByteArray().toByteString().sha256().hex()

        if (!forceRestart && environment.managed && environment.running &&
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

        if (!forceRestart && environment.installed && environment.managed &&
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

        val artifact =
            if (environment.installed) null
            else artifactSource.download(environment.os, environment.architecture)
        provision(environment, artifact)

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
        if (environment.manager == "unavailable") {
            val manager =
                when (environment.os) {
                    ManagedHostOs.Macos -> "launchd user domain"
                    ManagedHostOs.Linux -> "systemd user manager"
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
    ) {
        artifact?.let {
            upload(environment, if (environment.os == ManagedHostOs.Windows) "gateway.new.exe" else "gateway.new", it.bytes)
        }
        upload(environment, "config.json", gatewayConfig(environment))
        upload(environment, "token", (profile.token + "\n").encodeToByteArray())
        when (environment.os) {
            ManagedHostOs.Macos -> upload(environment, "service.plist", launchdPlist(environment).encodeToByteArray())
            ManagedHostOs.Linux -> upload(environment, "service.unit", systemdUnit.encodeToByteArray())
            ManagedHostOs.Windows -> Unit
        }
        runChecked(installCommand(environment), installScript(environment), "install gateway")
    }

    private suspend fun upload(
        environment: ManagedHostEnvironment,
        name: String,
        contents: ByteArray,
    ) {
        require(name.matches(Regex("[a-zA-Z0-9.]+")))
        val command =
            if (environment.os == ManagedHostOs.Windows) {
                windowsUploadCommand(name)
            } else {
                "sh -c 'umask 077; mkdir -p \"\$HOME/.cache/oh-pi-app\"; " +
                    "cat > \"\$HOME/.cache/oh-pi-app/$name\"'"
            }
        runChecked(command, contents, "upload $name", timeoutMillis = 180_000)
    }

    private suspend fun runChecked(
        command: String,
        stdin: ByteArray,
        operation: String,
        timeoutMillis: Int = 60_000,
    ): SshCommandResult {
        val result = execute(command, stdin, timeoutMillis)
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
        val dataDir =
            when (environment.os) {
                ManagedHostOs.Macos, ManagedHostOs.Linux -> "${environment.home}/.local/state/oh-pi-app"
                ManagedHostOs.Windows -> "${environment.home}\\data"
            }
        return buildJsonObject {
            put("OHPI_LISTEN", JsonPrimitive("127.0.0.1:$ManagedGatewayPort"))
            put("OHPI_DATA_DIR", JsonPrimitive(dataDir))
            put("OHPI_WORK_DIR", JsonPrimitive(environment.workDir))
            put("OHPI_PI_COMMAND", JsonPrimitive(environment.piPath))
            put("OHPI_PI_ENV_PATH", JsonPrimitive(environment.piEnvironmentPath))
            put("OHPI_TITLE_MODEL", JsonPrimitive("auto"))
        }.toString().encodeToByteArray()
    }

    private fun launchdPlist(environment: ManagedHostEnvironment): String {
        val home = environment.home
        fun path(suffix: String) = xmlEscape("$home/$suffix")
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>Label</key><string>$ManagedLaunchdLabel</string>
              <key>ProgramArguments</key>
              <array>
                <string>${path(".local/bin/ohpi-gateway")}</string>
                <string>--config</string><string>${path(".config/oh-pi-app/config.json")}</string>
                <string>--token-file</string><string>${path(".config/oh-pi-app/token")}</string>
              </array>
              <key>RunAtLoad</key><true/>
              <key>KeepAlive</key><true/>
              <key>ProcessType</key><string>Background</string>
              <key>StandardOutPath</key><string>${path(".local/state/oh-pi-app/gateway.log")}</string>
              <key>StandardErrorPath</key><string>${path(".local/state/oh-pi-app/gateway.err.log")}</string>
            </dict>
            </plist>
        """.trimIndent() + "\n"
    }

    private fun installCommand(environment: ManagedHostEnvironment): String =
        if (environment.os == ManagedHostOs.Windows) PowerShellStdin else "sh -s"

    private fun piInstallCommand(environment: ManagedHostEnvironment): String =
        if (environment.os == ManagedHostOs.Windows) PowerShellStdin else "sh -s"

    private fun piInstallScript(environment: ManagedHostEnvironment): ByteArray =
        when (environment.os) {
            ManagedHostOs.Macos, ManagedHostOs.Linux -> unixPiInstallScript
            ManagedHostOs.Windows -> windowsPiInstallScript()
        }.encodeToByteArray()

    private fun installScript(environment: ManagedHostEnvironment): ByteArray =
        when (environment.os) {
            ManagedHostOs.Macos -> macosInstallScript
            ManagedHostOs.Linux -> linuxInstallScript
            ManagedHostOs.Windows -> windowsInstallScript(profile.ssh.password.takeIf {
                profile.ssh.authentication == SshAuthentication.Password
            })
        }.encodeToByteArray()

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

private fun xmlEscape(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

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
          systemctl --user show-environment >/dev/null 2>&1 && manager=systemd
          [ ! -f "§HOME/.config/systemd/user/$ManagedSystemdUnit" ] || managed=true
          systemctl --user is-active --quiet $ManagedSystemdUnit >/dev/null 2>&1 && running=true
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

internal val unixPiInstallScript =
    script(
        """
        set -eu
        umask 077
        pi="§HOME/.local/bin/pi"
        managed_node_bin="§HOME/.local/share/oh-pi-app/node/current/bin"
        if [ -x "§pi" ]; then
          PATH="§managed_node_bin:§HOME/.local/bin:§PATH"
          export PATH
          "§pi" --version >/dev/null 2>&1 && exit 0
        fi

        node_bin=""
        npm_command=""
        if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 &&
          node -e 'const [major, minor, patch] = process.versions.node.split(".").map(Number); process.exit(major > 22 || (major === 22 && (minor > 19 || (minor === 19 && patch >= 0))) ? 0 : 1)' >/dev/null 2>&1; then
          node_bin=§(dirname "§(command -v node)")
          npm_command=§(command -v npm)
        else
          command -v curl >/dev/null 2>&1 || { echo 'curl is required to install Pi' >&2; exit 30; }
          command -v tar >/dev/null 2>&1 || { echo 'tar is required to install Pi' >&2; exit 31; }
          case §(uname -s) in
            Darwin) node_platform=darwin ;;
            Linux) node_platform=linux ;;
            *) echo 'unsupported operating system for Node.js' >&2; exit 32 ;;
          esac
          case §(uname -m) in
            x86_64|amd64) node_arch=x64 ;;
            arm64|aarch64) node_arch=arm64 ;;
            *) echo 'unsupported architecture for Node.js' >&2; exit 33 ;;
          esac
          node_dist=https://nodejs.org/dist/latest-v22.x
          node_root="§HOME/.local/share/oh-pi-app/node"
          tmp=§(mktemp -d "§{TMPDIR:-/tmp}/ohpi-node.XXXXXX")
          trap 'rm -rf "§tmp"' EXIT HUP INT TERM
          curl -fsSL "§node_dist/SHASUMS256.txt" -o "§tmp/SHASUMS256.txt"
          node_file=§(awk -v suffix="-§node_platform-§node_arch.tar.gz" '
            index(§2, "node-v22.") == 1 && substr(§2, length(§2) - length(suffix) + 1) == suffix { print §2; exit }
          ' "§tmp/SHASUMS256.txt")
          case "§node_file" in
            node-v22.*-§node_platform-§node_arch.tar.gz) ;;
            *) echo 'could not resolve a compatible Node.js archive' >&2; exit 34 ;;
          esac
          expected=§(awk -v file="§node_file" '§2 == file { print §1; exit }' "§tmp/SHASUMS256.txt")
          curl -fsSL "§node_dist/§node_file" -o "§tmp/§node_file"
          if command -v sha256sum >/dev/null 2>&1; then
            actual=§(sha256sum "§tmp/§node_file" | awk '{ print §1 }')
          elif command -v shasum >/dev/null 2>&1; then
            actual=§(shasum -a 256 "§tmp/§node_file" | awk '{ print §1 }')
          else
            echo 'a SHA-256 utility is required to install Node.js' >&2
            exit 35
          fi
          [ -n "§expected" ] && [ "§actual" = "§expected" ] || {
            echo 'Node.js checksum verification failed' >&2
            exit 36
          }
          tar -xzf "§tmp/§node_file" -C "§tmp"
          node_dir=§{node_file%.tar.gz}
          case "§node_dir" in node-v22.*-§node_platform-§node_arch) ;; *) exit 37 ;; esac
          mkdir -p "§node_root"
          target="§node_root/§node_dir"
          rm -rf "§target"
          mv "§tmp/§node_dir" "§target"
          rm -f "§node_root/current"
          ln -s "§target" "§node_root/current"
          node_bin="§node_root/current/bin"
          npm_command="§node_bin/npm"
        fi

        PATH="§node_bin:§HOME/.local/bin:§PATH"
        export PATH
        mkdir -p "§HOME/.local"
        "§npm_command" install -g --ignore-scripts --prefix "§HOME/.local" --no-fund --no-audit '$PiNpmPackage'
        test -x "§pi"
        "§pi" --version >/dev/null
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

private val systemdUnit =
    """
    [Unit]
    Description=Oh Pi gateway
    After=network-online.target

    [Service]
    Type=simple
    ExecStart=%h/.local/bin/ohpi-gateway --config %h/.config/oh-pi-app/config.json --token-file %h/.config/oh-pi-app/token
    Restart=on-failure
    RestartSec=3
    KillSignal=SIGTERM

    [Install]
    WantedBy=default.target
    """.trimIndent() + "\n"

private val macosInstallScript =
    script(
        """
        set -eu
        umask 077
        cache="§HOME/.cache/oh-pi-app"
        bin_dir="§HOME/.local/bin"
        config_dir="§HOME/.config/oh-pi-app"
        state_dir="§HOME/.local/state/oh-pi-app"
        agents="§HOME/Library/LaunchAgents"
        mkdir -p "§bin_dir" "§config_dir" "§state_dir" "§agents"
        if [ -f "§cache/gateway.new" ]; then
          chmod 700 "§cache/gateway.new"
          mv -f "§cache/gateway.new" "§bin_dir/ohpi-gateway"
        fi
        test -x "§bin_dir/ohpi-gateway"
        chmod 600 "§cache/config.json" "§cache/token" "§cache/service.plist"
        mv -f "§cache/config.json" "§config_dir/config.json"
        mv -f "§cache/token" "§config_dir/token"
        uid=§(id -u)
        domain="user/§uid"
        launchctl print "gui/§uid" >/dev/null 2>&1 && domain="gui/§uid"
        launchctl bootout "§domain/$ManagedLaunchdLabel" >/dev/null 2>&1 || true
        mv -f "§cache/service.plist" "§agents/$ManagedLaunchdLabel.plist"
        launchctl bootstrap "§domain" "§agents/$ManagedLaunchdLabel.plist"
        launchctl enable "§domain/$ManagedLaunchdLabel"
        launchctl kickstart -k "§domain/$ManagedLaunchdLabel"
        """,
    )

private val linuxInstallScript =
    script(
        """
        set -eu
        umask 077
        cache="§HOME/.cache/oh-pi-app"
        bin_dir="§HOME/.local/bin"
        config_dir="§HOME/.config/oh-pi-app"
        state_dir="§HOME/.local/state/oh-pi-app"
        units="§HOME/.config/systemd/user"
        mkdir -p "§bin_dir" "§config_dir" "§state_dir" "§units"
        if [ -f "§cache/gateway.new" ]; then
          chmod 700 "§cache/gateway.new"
          mv -f "§cache/gateway.new" "§bin_dir/ohpi-gateway"
        fi
        test -x "§bin_dir/ohpi-gateway"
        chmod 600 "§cache/config.json" "§cache/token" "§cache/service.unit"
        mv -f "§cache/config.json" "§config_dir/config.json"
        mv -f "§cache/token" "§config_dir/token"
        mv -f "§cache/service.unit" "§units/$ManagedSystemdUnit"
        systemctl --user daemon-reload
        systemctl --user enable --now $ManagedSystemdUnit
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
        systemctl --user stop $ManagedSystemdUnit
        """,
    )

private val windowsStopScript =
    script(
        """
        §ErrorActionPreference='Stop'
        Stop-ScheduledTask -TaskName '$ManagedTaskName' -ErrorAction SilentlyContinue
        """,
    )
