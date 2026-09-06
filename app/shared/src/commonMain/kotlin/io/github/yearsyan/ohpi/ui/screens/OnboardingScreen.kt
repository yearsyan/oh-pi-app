package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import io.github.yearsyan.ohpi.data.ServerConnectionMode
import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.SshAuthentication
import io.github.yearsyan.ohpi.data.SshPrivateKey
import io.github.yearsyan.ohpi.data.SshServerProfile
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.i18n.Strings
import io.github.yearsyan.ohpi.net.GatewayTransport
import io.github.yearsyan.ohpi.net.ManagedInstallStep
import io.github.yearsyan.ohpi.net.SshHostKeyPrompt
import io.github.yearsyan.ohpi.secureRandomHex
import io.github.yearsyan.ohpi.ssh.SshCommandStream
import io.github.yearsyan.ohpi.ui.components.SshHostKeyDialog
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class OnboardingPath { NewMachine, Existing }

/**
 * Drives the managed install run shown by the wizard: SSH connect, pi install,
 * gateway download/provision and service start, one linear step at a time.
 */
internal class OnboardingInstallController {
    var stepLabels: List<String> = emptyList(); private set
    var activeStep by mutableStateOf(0); private set
    var completedSteps by mutableStateOf(0); private set
    var failedMessage by mutableStateOf<String?>(null); private set
    var hostKeyPrompt by mutableStateOf<SshHostKeyPrompt?>(null); private set
    var resultProfile by mutableStateOf<ServerProfile?>(null); private set
    var logLines by mutableStateOf<List<ManagedInstallLogLine>>(emptyList()); private set

    private var profile: ServerProfile? = null
    private var trustedHostKey = ""
    private var hostKeyDecision: CompletableDeferred<Boolean>? = null
    private var logBuffer = ManagedInstallLogBuffer()

    fun prepare(profile: ServerProfile, labels: List<String>, trustedHostKey: String = "") {
        this.profile = profile
        stepLabels = labels
        this.trustedHostKey = trustedHostKey
        logBuffer =
            ManagedInstallLogBuffer(
                redactedValues =
                    listOf(
                        profile.token,
                        profile.ssh.password.takeIf { it.length >= 8 }.orEmpty(),
                        profile.ssh.privateKeyPassphrase.takeIf { it.length >= 8 }.orEmpty(),
                    ),
            )
        reset()
    }

    fun reset() {
        activeStep = 0
        completedSteps = 0
        failedMessage = null
        resultProfile = null
        logBuffer.clear()
        logLines = emptyList()
        hostKeyPrompt = null
        hostKeyDecision?.complete(false)
        hostKeyDecision = null
    }

    fun answerHostKey(trust: Boolean) {
        hostKeyPrompt = null
        hostKeyDecision?.complete(trust)
        hostKeyDecision = null
    }

    /** Runs the full install; safe to call again after a failure. */
    suspend fun run() {
        val base = profile ?: return
        reset()
        val transport =
            GatewayTransport(
                profile = base.copy(ssh = base.ssh.copy(hostKeySha256 = trustedHostKey)),
                confirmHostKey = ::confirmHostKey,
                onHostKeyTrusted = { trustedHostKey = it },
                installProgress = { step ->
                    val index =
                        when (step) {
                            ManagedInstallStep.DetectingSystem -> 1
                            ManagedInstallStep.InstallingPi -> 2
                            ManagedInstallStep.DownloadingGateway -> 3
                            ManagedInstallStep.InstallingGateway -> 4
                            ManagedInstallStep.StartingGateway -> 5
                        }.coerceAtMost(stepLabels.size - 1)
                    if (index >= completedSteps) {
                        completedSteps = index
                        activeStep = index
                    }
                },
                installOutput = { output -> logLines = logBuffer.append(output) },
            )
        try {
            transport.resolveGateway()
            logLines = logBuffer.flush()
            completedSteps = stepLabels.size
            resultProfile =
                base.copy(
                    ssh =
                        base.ssh.copy(
                            hostKeySha256 = trustedHostKey,
                            // Key material stays in the managed key store only.
                            privateKey = "",
                            privateKeyPassphrase = "",
                        ),
                )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            logLines = logBuffer.flush()
            failedMessage = failure.message ?: "unknown error"
        } finally {
            transport.close()
        }
    }

    private suspend fun confirmHostKey(prompt: SshHostKeyPrompt): Boolean {
        val decision = CompletableDeferred<Boolean>()
        hostKeyDecision = decision
        hostKeyPrompt = prompt
        return try {
            decision.await()
        } finally {
            if (hostKeyDecision === decision) {
                hostKeyDecision = null
                hostKeyPrompt = null
            }
        }
    }
}

/** Form state for the "set up a new machine" wizard path. */
internal class NewMachineFormState {
    var name by mutableStateOf("")
    var sshHost by mutableStateOf("")
    var sshPort by mutableStateOf("22")
    var sshUsername by mutableStateOf("")
    var authentication by mutableStateOf(SshAuthentication.Password)
    var password by mutableStateOf("")
    val keySelection = SshKeySelectionState()
    var error by mutableStateOf<String?>(null)

    fun clearError() {
        error = null
    }

    /**
     * Validates the form and, on success, builds the managed profile (with key
     * material injected for the install run) plus any new key to import.
     */
    fun build(
        strings: Strings,
        keys: List<SshPrivateKey>,
    ): ServerEditorResult? {
        val parsedPort = sshPort.toIntOrNull()
        val usesPrivateKey = authentication == SshAuthentication.PrivateKey
        error =
            when {
                sshHost.isBlank() -> strings.sshHostRequired
                parsedPort !in 1..65535 -> strings.sshPortInvalid
                sshUsername.isBlank() -> strings.sshUsernameRequired
                authentication == SshAuthentication.Password && password.isEmpty() ->
                    strings.sshPasswordRequired
                usesPrivateKey -> keySelection.validate(strings, keys)
                else -> null
            }
        if (error != null) return null

        val newKey = if (usesPrivateKey) keySelection.buildNewKey(strings, keys) else null
        val keyMaterial =
            if (usesPrivateKey) {
                newKey ?: keys.firstOrNull { it.id == keySelection.selectedKeyId }
            } else {
                null
            }
        val profile =
            ServerProfile(
                id = Random.nextLong().toString(16),
                name = name.trim().ifBlank { sshHost.trim() },
                url = "ws://127.0.0.1:$DefaultGatewayPort",
                token = secureRandomHex(32),
                connectionMode = ServerConnectionMode.ManagedSsh,
                ssh =
                    SshServerProfile(
                        host = sshHost.trim(),
                        port = parsedPort ?: 22,
                        username = sshUsername.trim(),
                        authentication = authentication,
                        password =
                            password.takeIf { authentication == SshAuthentication.Password }
                                .orEmpty(),
                        privateKeyId =
                            if (usesPrivateKey) {
                                newKey?.id ?: keySelection.selectedKeyId
                            } else {
                                ""
                            },
                        privateKey = keyMaterial?.privateKey.orEmpty(),
                        privateKeyPassphrase = keyMaterial?.passphrase.orEmpty(),
                    ),
            )
        return ServerEditorResult(profile, newKey)
    }
}

/** First-run wizard shown until at least one machine is configured. */
@Composable
fun OnboardingScreen(
    keys: List<SshPrivateKey>,
    onSave: (ServerProfile, SshPrivateKey?) -> Unit,
) {
    var path by remember { mutableStateOf<OnboardingPath?>(null) }
    var installing by remember { mutableStateOf(false) }
    val newMachineForm = remember { NewMachineFormState() }
    val existingEditor = rememberServerEditor(null)
    val install = remember { OnboardingInstallController() }
    val scope = rememberCoroutineScope()
    var installJob by remember { mutableStateOf<Job?>(null) }
    var pendingNewKey by remember { mutableStateOf<SshPrivateKey?>(null) }
    val strings = S

    fun cancelInstall() {
        install.answerHostKey(false)
        installJob?.cancel()
        installJob = null
        install.reset()
        installing = false
    }

    fun startInstall(result: ServerEditorResult) {
        pendingNewKey = result.newKey
        install.prepare(
            profile = result.profile,
            labels =
                listOf(
                    strings.installStepConnect,
                    strings.installStepDetect,
                    strings.installStepPi,
                    strings.installStepDownload,
                    strings.installStepService,
                    strings.installStepStart,
                ),
        )
        installing = true
        installJob = scope.launch { install.run() }
    }

    // The machine is ready: hand the profile (and any imported key) to the app.
    LaunchedEffect(install.resultProfile) {
        install.resultProfile?.let { onSave(it, pendingNewKey) }
    }

    Box(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                installing -> InstallProgressContent(
                    install = install,
                    host = newMachineForm.sshHost.trim(),
                    username = newMachineForm.sshUsername.trim(),
                    onCancel = ::cancelInstall,
                    onRetry = { installJob = scope.launch { install.run() } },
                )

                path == null -> ChoosePathContent(onChoose = { path = it })

                path == OnboardingPath.NewMachine -> NewMachineContent(
                    form = newMachineForm,
                    keys = keys,
                    onBack = { path = null },
                    onInstall = { startInstall(it) },
                )

                else -> ExistingMachineContent(
                    editor = existingEditor,
                    keys = keys,
                    onBack = { path = null },
                    onConnect = { result -> onSave(result.profile, result.newKey) },
                )
            }
        }

        install.hostKeyPrompt?.let { prompt ->
            SshHostKeyDialog(
                prompt = prompt,
                onReject = { install.answerHostKey(false) },
                onTrust = { install.answerHostKey(true) },
            )
        }
    }
}

@Composable
private fun OnboardingHeader() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(64.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                "π",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    Text(
        S.welcomeTitle,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        S.welcomeBody,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ChoosePathContent(onChoose: (OnboardingPath) -> Unit) {
    OnboardingHeader()
    Spacer(Modifier.height(32.dp))
    Text(
        S.onboardingChooseTitle,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    PathCard(
        icon = { Icon(Icons.Filled.CloudDownload, contentDescription = null) },
        title = S.onboardingNewMachineTitle,
        body = S.onboardingNewMachineBody,
        onClick = { onChoose(OnboardingPath.NewMachine) },
    )
    Spacer(Modifier.height(12.dp))
    PathCard(
        icon = { Icon(Icons.Filled.Link, contentDescription = null) },
        title = S.onboardingExistingTitle,
        body = S.onboardingExistingBody,
        onClick = { onChoose(OnboardingPath.Existing) },
    )
}

@Composable
internal fun PathCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(18.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.size(22.dp)) { icon() }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun FormTopBar(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S.back)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun NewMachineContent(
    form: NewMachineFormState,
    keys: List<SshPrivateKey>,
    onBack: () -> Unit,
    onInstall: (ServerEditorResult) -> Unit,
) {
    val strings = S
    FormTopBar(S.onboardingNewMachineTitle, onBack)
    Text(
        S.onboardingNewMachineFormBody,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = form.name,
        onValueChange = { form.name = it; form.clearError() },
        label = { Text(S.serverNameLabel) },
        placeholder = { Text(S.serverNamePlaceholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = form.sshHost,
            onValueChange = { form.sshHost = it; form.clearError() },
            label = { Text(S.sshHostLabel) },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            placeholder = { Text("server.example.com") },
            singleLine = true,
            isError = form.error == strings.sshHostRequired,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        OutlinedTextField(
            value = form.sshPort,
            onValueChange = { form.sshPort = it.filter(Char::isDigit); form.clearError() },
            label = { Text(S.sshPortLabel) },
            singleLine = true,
            isError = form.error == strings.sshPortInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(0.38f),
        )
    }
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = form.sshUsername,
        onValueChange = { form.sshUsername = it; form.clearError() },
        label = { Text(S.sshUsernameLabel) },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        singleLine = true,
        isError = form.error == strings.sshUsernameRequired,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    SshAuthenticationFields(
        authentication = form.authentication,
        onAuthenticationChange = { form.authentication = it; form.clearError() },
        password = form.password,
        onPasswordChange = { form.password = it; form.clearError() },
        keySelection = form.keySelection,
        keys = keys,
        onFieldEdited = form::clearError,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        S.sshTrustOnFirstUseHint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    form.error?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    Spacer(Modifier.height(22.dp))
    Button(
        onClick = { form.build(strings, keys)?.let(onInstall) },
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(Icons.Filled.Computer, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(S.installAndConnect, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ExistingMachineContent(
    editor: ServerEditorState,
    keys: List<SshPrivateKey>,
    onBack: () -> Unit,
    onConnect: (ServerEditorResult) -> Unit,
) {
    val strings = S
    FormTopBar(S.onboardingExistingTitle, onBack)
    Text(
        S.onboardingExistingFormBody,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    ServerEditorFields(
        editor = editor,
        keys = keys,
        modes = listOf(ServerConnectionMode.Direct, ServerConnectionMode.Ssh),
    )
    Spacer(Modifier.height(22.dp))
    Button(
        onClick = { editor.build(strings, keys)?.let(onConnect) },
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(S.connectAndSave, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun InstallProgressContent(
    install: OnboardingInstallController,
    host: String,
    username: String,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val target = if (username.isBlank()) host else "$username@$host"
    Spacer(Modifier.height(12.dp))
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(64.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Computer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(30.dp),
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    Text(
        S.installTitle(target),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        S.installHint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(28.dp))

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            install.stepLabels.forEachIndexed { index, label ->
                InstallStepRow(
                    label = label,
                    state =
                        when {
                            index < install.completedSteps -> InstallStepState.Done
                            index == install.activeStep && install.failedMessage != null ->
                                InstallStepState.Failed
                            index == install.activeStep -> InstallStepState.Active
                            else -> InstallStepState.Pending
                        },
                    hint =
                        if (index == PiInstallStepIndex &&
                            index == install.activeStep &&
                            install.failedMessage == null
                        ) {
                            S.installStepPiHint
                        } else {
                            null
                        },
                )
            }
        }
    }

    if (install.logLines.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        InstallLogPanel(install.logLines)
    }

    install.failedMessage?.let { message ->
        Spacer(Modifier.height(16.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onCancel) { Text(S.installBackToForm) }
            Button(onClick = onRetry, shape = RoundedCornerShape(14.dp)) {
                Text(S.installRetry)
            }
        }
    } ?: run {
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onCancel) { Text(S.installCancel) }
    }
}

@Composable
private fun InstallLogPanel(lines: List<ManagedInstallLogLine>) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()?.text) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                S.installLogTitle,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                ) {
                    itemsIndexed(lines) { _, line ->
                        Text(
                            text = line.text.ifEmpty { " " },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color =
                                if (line.stream == SshCommandStream.Stderr) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    }
                }
            }
        }
    }
}

internal enum class InstallStepState { Pending, Active, Done, Failed }

/** Position of the "installing pi" step in the wizard's fixed step list. */
internal const val PiInstallStepIndex = 2

@Composable
internal fun InstallStepRow(label: String, state: InstallStepState, hint: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        when (state) {
            InstallStepState.Done ->
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            InstallStepState.Active ->
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            InstallStepState.Failed ->
                Icon(
                    Icons.Filled.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            InstallStepState.Pending ->
                Icon(
                    Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(20.dp),
                )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    when (state) {
                        InstallStepState.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                fontWeight = if (state == InstallStepState.Active) FontWeight.SemiBold else null,
            )
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
