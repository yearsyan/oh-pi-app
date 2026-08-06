package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.net.GatewayProvider
import io.github.yearsyan.ohpi.net.GatewayProviderAuthMethod
import io.github.yearsyan.ohpi.net.GatewayProviderModel
import io.github.yearsyan.ohpi.ui.AppViewModel
import io.github.yearsyan.ohpi.ui.ProviderAuthFlowState
import io.github.yearsyan.ohpi.ui.components.ConfirmDialog

/** First-party management UI for providers shipped with pi. */
@Composable
fun ProvidersScreen(vm: AppViewModel, onBack: () -> Unit) {
    var expandedProviderId by remember { mutableStateOf<String?>(null) }
    var logoutTarget by remember { mutableStateOf<GatewayProvider?>(null) }

    LaunchedEffect(vm.activeServerId) {
        vm.refreshProviders()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S.back)
            }
            Text(
                S.providersTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (vm.providersLoading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = vm::refreshProviders, enabled = !vm.providersLoading) {
                Icon(Icons.Filled.Refresh, contentDescription = S.retry)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when {
            vm.providersLoading && vm.providers.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            S.providerLoading,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            vm.providers.isEmpty() -> {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        S.providerEmpty,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier =
                        Modifier
                            .weight(1f)
                            .widthIn(max = 720.dp)
                            .fillMaxWidth()
                            .align(Alignment.CenterHorizontally)
                            .padding(horizontal = 16.dp),
                ) {
                    item(key = "provider-hint") {
                        Text(
                            S.providersBuiltInOnlyHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                        )
                    }
                    items(vm.providers, key = { it.id }) { provider ->
                        ProviderCard(
                            provider = provider,
                            expanded = expandedProviderId == provider.id,
                            logoutRunning = vm.providerLogoutId == provider.id,
                            onToggleModels = {
                                expandedProviderId = if (expandedProviderId == provider.id) null else provider.id
                            },
                            onLogin = { method -> vm.startProviderLogin(provider, method) },
                            onLogout = { logoutTarget = provider },
                        )
                    }
                    item(key = "provider-bottom-space") { Spacer(Modifier.height(36.dp)) }
                }
            }
        }
    }

    logoutTarget?.let { provider ->
        ConfirmDialog(
            title = S.providerLogoutTitle,
            body = S.providerLogoutBody(provider.name),
            onDismiss = { logoutTarget = null },
            onConfirm = {
                logoutTarget = null
                vm.logoutProvider(provider)
            },
        )
    }

    vm.providerAuthFlow?.let { flow ->
        ProviderAuthDialog(
            flow = flow,
            onRespond = vm::respondProviderAuth,
            onCancel = vm::cancelProviderAuth,
            onDismiss = vm::dismissProviderAuth,
        )
    }
}

@Composable
private fun ProviderCard(
    provider: GatewayProvider,
    expanded: Boolean,
    logoutRunning: Boolean,
    onToggleModels: () -> Unit,
    onLogin: (GatewayProviderAuthMethod) -> Unit,
    onLogout: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(horizontal = 14.dp),
            ) {
                Icon(
                    if (provider.configured) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint =
                        if (provider.configured) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        provider.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (provider.configured) S.providerConfigured else S.providerNotConfigured,
                        style = MaterialTheme.typography.labelMedium,
                        color =
                            if (provider.configured) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (provider.authLabel.isNotBlank() || provider.authSource.isNotBlank()) {
                        Text(
                            provider.authLabel.ifBlank { S.providerCredentialSource(provider.authSource) },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleModels)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    if (provider.models.isEmpty()) S.providerNoModels else S.providerModels(provider.models.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded && provider.models.isNotEmpty()) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                provider.models.forEach { model ->
                    ProviderModelRow(model)
                }
            }

            if (provider.authMethods.isNotEmpty() || provider.storedAuthType.isNotBlank()) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
                provider.authMethods.forEach { method ->
                    val methodName = method.label.ifBlank { method.name }
                    OutlinedButton(
                        onClick = { onLogin(method) },
                        enabled = !logoutRunning,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
                    ) {
                        Text(
                            "${if (provider.configured) S.providerRelogin else S.providerLogin} · $methodName",
                        )
                    }
                }
                if (provider.storedAuthType.isNotBlank()) {
                    TextButton(
                        onClick = onLogout,
                        enabled = !logoutRunning,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    ) {
                        if (logoutRunning) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(S.providerLogout, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderModelRow(model: GatewayProviderModel) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                model.name.ifBlank { model.id },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (model.name.isNotBlank() && model.name != model.id) {
                Text(
                    model.id,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val features = buildList {
            if (model.reasoning) add(S.providerReasoning)
            if (model.input.any { it.equals("image", ignoreCase = true) }) add(S.providerImageInput)
        }
        if (features.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                features.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ProviderAuthDialog(
    flow: ProviderAuthFlowState,
    onRespond: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val terminal = flow.completed || flow.error.isNotBlank()
    val prompt = flow.prompt
    var answer by remember(prompt?.id) { mutableStateOf("") }
    val links = buildList {
        if (flow.authorizationUrl.isNotBlank()) add(flow.authorizationUrl to S.providerOpenBrowser)
        addAll(flow.links)
    }.distinctBy { it.first }

    AlertDialog(
        onDismissRequest = { if (terminal) onDismiss() else onCancel() },
        title = { Text(flow.providerName) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            ) {
                if (prompt == null && flow.status.isNotBlank()) {
                    Text(flow.status, style = MaterialTheme.typography.bodyMedium)
                }
                if (flow.error.isNotBlank()) {
                    Text(
                        flow.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (flow.userCode.isNotBlank()) {
                    Text(
                        S.providerUserCode,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(
                            flow.userCode,
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                links.forEach { (url, label) ->
                    Button(
                        onClick = { runCatching { uriHandler.openUri(url) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(label.ifBlank { S.providerOpenBrowser })
                    }
                }
                if (prompt != null) {
                    Text(prompt.message, style = MaterialTheme.typography.bodyMedium)
                    if (prompt.kind == "select") {
                        prompt.options.forEachIndexed { index, option ->
                            OutlinedButton(
                                onClick = { onRespond(option) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(option)
                                    prompt.descriptions.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { description ->
                                        Text(
                                            description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = answer,
                            onValueChange = { answer = it },
                            placeholder = prompt.placeholder.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                            singleLine = true,
                            visualTransformation =
                                if (prompt.kind == "secret") PasswordVisualTransformation()
                                else VisualTransformation.None,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (!terminal && links.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(S.providerAuthenticating, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            when {
                terminal -> TextButton(onClick = onDismiss) { Text(S.providerDone) }
                prompt != null && prompt.kind != "select" -> {
                    Button(onClick = { onRespond(answer) }, enabled = answer.isNotBlank()) {
                        Text(S.providerSubmit)
                    }
                }
            }
        },
        dismissButton = {
            if (!terminal) {
                TextButton(onClick = onCancel) { Text(S.providerCancelLogin) }
            }
        },
    )
}
