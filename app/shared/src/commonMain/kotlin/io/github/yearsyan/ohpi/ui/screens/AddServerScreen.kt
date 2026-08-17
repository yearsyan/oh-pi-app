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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.data.ServerConnectionMode
import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.SshPrivateKey
import io.github.yearsyan.ohpi.getPlatform
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.i18n.Strings
import io.github.yearsyan.ohpi.net.GatewayTransport
import io.github.yearsyan.ohpi.net.SshHostKeyPrompt
import io.github.yearsyan.ohpi.net.buildGatewayUrl
import io.github.yearsyan.ohpi.net.checkGatewayHealth
import io.github.yearsyan.ohpi.ui.components.SshHostKeyDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class AddServerStep {
    Choose,
    Direct,
    Ssh,
    SshCheck,
    SshToken,
    SshMissing,
    Install,
    Manual,
}

/**
 * Drives the SSH connection check of the add-server wizard: opens a tunnel to
 * the default gateway port on the remote host and probes its health endpoint.
 * The probe decides whether the wizard asks for a token or offers install.
 */
internal class SshCheckController {
    var stepLabels: List<String> = emptyList(); private set
    var activeStep by mutableStateOf(0); private set
    var completedSteps by mutableStateOf(0); private set
    var failedMessage by mutableStateOf<String?>(null); private set
    var gatewayHealthy by mutableStateOf<Boolean?>(null); private set
    var trustedHostKey by mutableStateOf(""); private set
    var hostKeyPrompt by mutableStateOf<SshHostKeyPrompt?>(null); private set

    private var profile: ServerProfile? = null
    private var hostKeyDecision: CompletableDeferred<Boolean>? = null

    fun prepare(profile: ServerProfile, labels: List<String>) {
        this.profile = profile
        stepLabels = labels
        trustedHostKey = profile.ssh.hostKeySha256.trim()
        reset()
    }

    fun reset() {
        activeStep = 0
        completedSteps = 0
        failedMessage = null
        gatewayHealthy = null
        hostKeyPrompt = null
        hostKeyDecision?.complete(false)
        hostKeyDecision = null
    }

    fun answerHostKey(trust: Boolean) {
        hostKeyPrompt = null
        hostKeyDecision?.complete(trust)
        hostKeyDecision = null
    }

    /** Runs the check; safe to call again after a failure. */
    suspend fun run() {
        val base = profile ?: return
        reset()
        val transport =
            GatewayTransport(
                profile = base.copy(ssh = base.ssh.copy(hostKeySha256 = trustedHostKey)),
                confirmHostKey = ::confirmHostKey,
                onHostKeyTrusted = { trustedHostKey = it },
            )
        try {
            val localGateway = transport.resolveGateway()
            completedSteps = 1
            activeStep = 1
            gatewayHealthy = checkGatewayHealth(localGateway)
            completedSteps = stepLabels.size
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
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

/** Gateway address form shown when no gateway answered on the default port. */
internal class ManualGatewayFormState {
    var gatewayHost by mutableStateOf("")
    var gatewayPort by mutableStateOf(DefaultGatewayPort.toString())
    var token by mutableStateOf("")
    var error by mutableStateOf<String?>(null)

    fun clearError() {
        error = null
    }

    /** Validates the fields and retargets the verified SSH profile at this address. */
    fun build(strings: Strings, base: ServerProfile): ServerProfile? {
        // iOS reaches the remote gateway only through its SSH-side loopback.
        val fixedLoopback = getPlatform().isIos
        val host = if (fixedLoopback) FixedLoopbackHost else gatewayHost.trim()
        val port = gatewayPort.toIntOrNull()
        error =
            when {
                host.isBlank() -> strings.serverHostRequired
                port !in 1..65535 -> strings.serverPortInvalid
                else -> null
            }
        if (error != null) return null
        return base.copy(
            url = buildGatewayUrl(host, port ?: DefaultGatewayPort, tls = false),
            token = token.trim(),
        )
    }
}

private val FixedLoopbackHost = "127.0.0.1"

/** Full-page add-server wizard: choose direct or SSH, verify, then save. */
@Composable
fun AddServerScreen(
    keys: List<SshPrivateKey>,
    onSave: (ServerProfile, SshPrivateKey?) -> Unit,
    onBack: () -> Unit,
) {
    val strings = S
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(AddServerStep.Choose) }
    val directEditor = rememberServerEditor(null)
    val sshForm = remember { NewMachineFormState() }
    val check = remember { SshCheckController() }
    val install = remember { OnboardingInstallController() }
    val manualForm = remember { ManualGatewayFormState() }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    var installJob by remember { mutableStateOf<Job?>(null) }
    var pendingNewKey by remember { mutableStateOf<SshPrivateKey?>(null) }
    var sshBaseProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var tokenInput by remember { mutableStateOf("") }
    var manualBackStep by remember { mutableStateOf(AddServerStep.SshMissing) }

    val sshTarget =
        sshForm.sshHost.trim().let { host ->
            if (sshForm.sshUsername.isBlank()) host else "${sshForm.sshUsername.trim()}@$host"
        }

    fun startCheck() {
        val result = sshForm.build(strings, keys) ?: return
        pendingNewKey = result.newKey
        // Probe as a plain SSH tunnel so the check never provisions anything.
        val probe = result.profile.copy(connectionMode = ServerConnectionMode.Ssh)
        sshBaseProfile = probe
        check.prepare(
            profile = probe,
            labels =
                listOf(
                    strings.installStepConnect,
                    strings.addServerStepDetectGateway,
                ),
        )
        step = AddServerStep.SshCheck
        checkJob = scope.launch { check.run() }
    }

    fun cancelCheck() {
        check.answerHostKey(false)
        checkJob?.cancel()
        checkJob = null
        check.reset()
        step = AddServerStep.Ssh
    }

    fun startInstall() {
        val result = sshForm.build(strings, keys) ?: return
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
            trustedHostKey = check.trustedHostKey,
        )
        step = AddServerStep.Install
        installJob = scope.launch { install.run() }
    }

    fun cancelInstall() {
        install.answerHostKey(false)
        installJob?.cancel()
        installJob = null
        install.reset()
        step = AddServerStep.SshMissing
    }

    /** The verified SSH profile carrying the host key trusted during the check. */
    fun trustedSshProfile(): ServerProfile? =
        sshBaseProfile?.let { base ->
            base.copy(ssh = base.ssh.copy(hostKeySha256 = check.trustedHostKey))
        }

    // The probe decides the next step: token entry or the install/manual choice.
    LaunchedEffect(check.gatewayHealthy) {
        when (check.gatewayHealthy) {
            true -> step = AddServerStep.SshToken
            false -> step = AddServerStep.SshMissing
            null -> Unit
        }
    }

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
            when (step) {
                AddServerStep.Choose ->
                    ChooseMethodContent(
                        onBack = onBack,
                        onChoose = { mode ->
                            step =
                                if (mode == ServerConnectionMode.Direct) {
                                    AddServerStep.Direct
                                } else {
                                    AddServerStep.Ssh
                                }
                        },
                    )

                AddServerStep.Direct ->
                    DirectFormContent(
                        editor = directEditor,
                        onBack = { step = AddServerStep.Choose },
                        onSave = { result -> onSave(result.profile, result.newKey) },
                    )

                AddServerStep.Ssh ->
                    SshFormContent(
                        form = sshForm,
                        keys = keys,
                        onBack = { step = AddServerStep.Choose },
                        onCheck = ::startCheck,
                    )

                AddServerStep.SshCheck ->
                    SshCheckContent(
                        check = check,
                        target = sshTarget,
                        onCancel = ::cancelCheck,
                        onRetry = { checkJob = scope.launch { check.run() } },
                    )

                AddServerStep.SshToken ->
                    SshTokenContent(
                        token = tokenInput,
                        onTokenChange = { tokenInput = it },
                        onBack = { step = AddServerStep.Ssh },
                        onUseOtherGateway = {
                            manualBackStep = AddServerStep.SshToken
                            manualForm.token = tokenInput
                            step = AddServerStep.Manual
                        },
                        onSave = {
                            trustedSshProfile()?.let { profile ->
                                onSave(profile.copy(token = tokenInput.trim()), pendingNewKey)
                            }
                        },
                    )

                AddServerStep.SshMissing ->
                    SshMissingContent(
                        onBack = { step = AddServerStep.Ssh },
                        onInstall = ::startInstall,
                        onManual = {
                            manualBackStep = AddServerStep.SshMissing
                            step = AddServerStep.Manual
                        },
                    )

                AddServerStep.Install ->
                    InstallProgressContent(
                        install = install,
                        host = sshForm.sshHost.trim(),
                        username = sshForm.sshUsername.trim(),
                        onCancel = ::cancelInstall,
                        onRetry = { installJob = scope.launch { install.run() } },
                    )

                AddServerStep.Manual ->
                    ManualGatewayContent(
                        form = manualForm,
                        onBack = {
                            if (manualBackStep == AddServerStep.SshToken) {
                                tokenInput = manualForm.token
                            }
                            step = manualBackStep
                        },
                        onSave = {
                            val base = trustedSshProfile() ?: return@ManualGatewayContent
                            manualForm.build(strings, base)?.let { profile ->
                                onSave(profile, pendingNewKey)
                            }
                        },
                    )
            }
        }

        check.hostKeyPrompt?.let { prompt ->
            SshHostKeyDialog(
                prompt = prompt,
                onReject = { check.answerHostKey(false) },
                onTrust = { check.answerHostKey(true) },
            )
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
private fun ChooseMethodContent(
    onBack: () -> Unit,
    onChoose: (ServerConnectionMode) -> Unit,
) {
    FormTopBar(S.addServer, onBack)
    Text(
        S.addServerChooseTitle,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    PathCard(
        icon = { Icon(Icons.Filled.Link, contentDescription = null) },
        title = S.addServerDirectTitle,
        body = S.addServerDirectBody,
        onClick = { onChoose(ServerConnectionMode.Direct) },
    )
    Spacer(Modifier.height(12.dp))
    PathCard(
        icon = { Icon(Icons.Filled.Computer, contentDescription = null) },
        title = S.addServerSshTitle,
        body = S.addServerSshBody,
        onClick = { onChoose(ServerConnectionMode.Ssh) },
    )
}

@Composable
private fun DirectFormContent(
    editor: ServerEditorState,
    onBack: () -> Unit,
    onSave: (ServerEditorResult) -> Unit,
) {
    val strings = S
    FormTopBar(S.addServerDirectTitle, onBack)
    OutlinedTextField(
        value = editor.name,
        onValueChange = { editor.name = it; editor.clearError() },
        label = { Text(S.serverNameLabel) },
        placeholder = { Text(S.serverNamePlaceholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = editor.gatewayHost,
            onValueChange = { editor.gatewayHost = it; editor.clearError() },
            label = { Text(S.serverHostLabel) },
            placeholder = { Text(S.serverHostPlaceholder) },
            singleLine = true,
            isError = editor.error == strings.serverHostRequired,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        OutlinedTextField(
            value = editor.gatewayPort,
            onValueChange = { editor.gatewayPort = it.filter(Char::isDigit); editor.clearError() },
            label = { Text(S.serverPortLabel) },
            singleLine = true,
            isError = editor.error == strings.serverPortInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(0.38f),
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            S.serverTlsLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = editor.gatewayTls,
            onCheckedChange = { editor.gatewayTls = it; editor.clearError() },
        )
    }
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = editor.token,
        onValueChange = { editor.token = it; editor.clearError() },
        label = { Text(S.serverTokenLabel) },
        placeholder = { Text(S.serverTokenPlaceholder) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    editor.error?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    Spacer(Modifier.height(22.dp))
    Button(
        onClick = { editor.build(strings)?.let(onSave) },
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(S.connectAndSave, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SshFormContent(
    form: NewMachineFormState,
    keys: List<SshPrivateKey>,
    onBack: () -> Unit,
    onCheck: () -> Unit,
) {
    val strings = S
    FormTopBar(S.addServerSshTitle, onBack)
    Text(
        S.addServerSshBody,
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
        onClick = onCheck,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(
            Icons.Filled.NetworkCheck,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(S.addServerCheckConnection, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SshCheckContent(
    check: SshCheckController,
    target: String,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
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
        S.addServerCheckingTitle(target),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(28.dp))

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            check.stepLabels.forEachIndexed { index, label ->
                InstallStepRow(
                    label = label,
                    state =
                        when {
                            index < check.completedSteps -> InstallStepState.Done
                            index == check.activeStep && check.failedMessage != null ->
                                InstallStepState.Failed
                            index == check.activeStep -> InstallStepState.Active
                            else -> InstallStepState.Pending
                        },
                )
            }
        }
    }

    check.failedMessage?.let { message ->
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
private fun SshTokenContent(
    token: String,
    onTokenChange: (String) -> Unit,
    onBack: () -> Unit,
    onUseOtherGateway: () -> Unit,
    onSave: () -> Unit,
) {
    FormTopBar(S.addServerGatewayFoundTitle, onBack)
    Text(
        S.addServerGatewayFoundBody(DefaultGatewayPort),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = token,
        onValueChange = onTokenChange,
        label = { Text(S.serverTokenLabel) },
        placeholder = { Text(S.serverTokenPlaceholder) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(22.dp))
    Button(
        onClick = onSave,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(Icons.Filled.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(S.connectAndSave, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick = onUseOtherGateway,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (getPlatform().isIos) S.addServerUseOtherPort else S.addServerUseOtherGateway)
    }
}

@Composable
private fun SshMissingContent(
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onManual: () -> Unit,
) {
    FormTopBar(S.addServerGatewayMissingTitle, onBack)
    Text(
        S.addServerGatewayMissingBody(DefaultGatewayPort),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    PathCard(
        icon = { Icon(Icons.Filled.CloudDownload, contentDescription = null) },
        title = S.addServerInstallTitle,
        body = S.addServerInstallBody,
        onClick = onInstall,
    )
    Spacer(Modifier.height(12.dp))
    PathCard(
        icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
        title = S.addServerManualTitle,
        body = S.addServerManualBody,
        onClick = onManual,
    )
}

@Composable
private fun ManualGatewayContent(
    form: ManualGatewayFormState,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val strings = S
    val fixedLoopback = getPlatform().isIos
    FormTopBar(S.addServerManualTitle, onBack)
    Text(
        S.addServerManualBody,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth()) {
        if (!fixedLoopback) {
            OutlinedTextField(
                value = form.gatewayHost,
                onValueChange = { form.gatewayHost = it; form.clearError() },
                label = { Text(S.serverHostLabel) },
                placeholder = { Text(FixedLoopbackHost) },
                singleLine = true,
                isError = form.error == strings.serverHostRequired,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
        }
        OutlinedTextField(
            value = form.gatewayPort,
            onValueChange = { form.gatewayPort = it.filter(Char::isDigit); form.clearError() },
            label = { Text(if (fixedLoopback) S.backendPortLabel else S.serverPortLabel) },
            singleLine = true,
            isError = form.error == strings.serverPortInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(if (fixedLoopback) 1f else 0.38f),
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        if (fixedLoopback) S.sshGatewayIosFixedHostHint else S.sshGatewayPlaintextHint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = form.token,
        onValueChange = { form.token = it; form.clearError() },
        label = { Text(S.serverTokenLabel) },
        placeholder = { Text(S.serverTokenPlaceholder) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
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
        onClick = onSave,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(S.connectAndSave, fontWeight = FontWeight.SemiBold)
    }
}
