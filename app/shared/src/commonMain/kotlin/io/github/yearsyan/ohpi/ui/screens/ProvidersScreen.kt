package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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

/** Signed-in providers, shown as a plain list; details open in a bottom sheet. */
@Composable
fun ProvidersScreen(vm: AppViewModel, onBack: () -> Unit, onAddProvider: () -> Unit) {
    var detailProviderId by remember { mutableStateOf<String?>(null) }
    var logoutTarget by remember { mutableStateOf<GatewayProvider?>(null) }
    val configured = vm.providers.filter { it.configured }

    LaunchedEffect(vm.activeServerId) {
        vm.refreshProviders()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        ProviderTopBar(
            title = S.providersTitle,
            loading = vm.providersLoading,
            onBack = onBack,
            onRefresh = vm::refreshProviders,
        ) {
            IconButton(onClick = onAddProvider) {
                Icon(Icons.Filled.Add, contentDescription = S.providerAdd)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when {
            vm.providersLoading && vm.providers.isEmpty() -> ProviderLoadingBody()
            vm.providers.isEmpty() -> ProviderEmptyBody(S.providerEmpty)
            configured.isEmpty() -> ProviderEmptyBody(S.providerEmptyConfigured)
            else -> ProviderList(
                providers = configured,
                hint = S.providersBuiltInOnlyHint,
                onOpen = { detailProviderId = it.id },
            )
        }
    }

    detailProviderId?.let { id ->
        vm.providers.firstOrNull { it.id == id }?.let { provider ->
            ProviderDetailSheet(
                provider = provider,
                logoutRunning = vm.providerLogoutId == provider.id,
                onLogin = { method -> vm.startProviderLogin(provider, method) },
                onLogout = { logoutTarget = provider },
                onDismiss = { detailProviderId = null },
            )
        }
    }

    logoutTarget?.let { provider ->
        ConfirmDialog(
            title = S.providerLogoutTitle,
            body = S.providerLogoutBody(provider.name),
            confirmLabel = S.providerLogout,
            onDismiss = { logoutTarget = null },
            onConfirm = {
                logoutTarget = null
                vm.logoutProvider(provider)
            },
        )
    }

    ProviderAuthFlowHost(vm)
}

/** Providers that are not signed in yet; the user can pick one to sign in to. */
@Composable
fun AddProviderScreen(vm: AppViewModel, onBack: () -> Unit) {
    var detailProviderId by remember { mutableStateOf<String?>(null) }
    val unconfigured = vm.providers.filter { !it.configured }

    LaunchedEffect(vm.activeServerId) {
        vm.refreshProviders()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        ProviderTopBar(
            title = S.providerAdd,
            loading = vm.providersLoading,
            onBack = onBack,
            onRefresh = vm::refreshProviders,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when {
            vm.providersLoading && vm.providers.isEmpty() -> ProviderLoadingBody()
            vm.providers.isEmpty() -> ProviderEmptyBody(S.providerEmpty)
            unconfigured.isEmpty() -> ProviderEmptyBody(S.providerEmptyUnconfigured)
            else -> ProviderList(
                providers = unconfigured,
                hint = null,
                onOpen = { detailProviderId = it.id },
            )
        }
    }

    detailProviderId?.let { id ->
        vm.providers.firstOrNull { it.id == id }?.let { provider ->
            ProviderDetailSheet(
                provider = provider,
                logoutRunning = false,
                onLogin = { method -> vm.startProviderLogin(provider, method) },
                onLogout = {},
                onDismiss = { detailProviderId = null },
            )
        }
    }

    ProviderAuthFlowHost(vm)
}

@Composable
private fun ProviderTopBar(
    title: String,
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S.back)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = onRefresh, enabled = !loading) {
            Icon(Icons.Filled.Refresh, contentDescription = S.retry)
        }
        actions()
    }
}

@Composable
private fun ProviderLoadingBody() {
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

@Composable
private fun ProviderEmptyBody(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.ProviderList(
    providers: List<GatewayProvider>,
    hint: String?,
    onOpen: (GatewayProvider) -> Unit,
) {
    LazyColumn(
        modifier =
            Modifier
                .weight(1f)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .align(Alignment.CenterHorizontally),
    ) {
        if (hint != null) {
            item(key = "provider-hint") {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
                )
            }
        }
        items(providers, key = { it.id }) { provider ->
            ProviderRow(provider = provider, onClick = { onOpen(provider) })
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 0.5.dp,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        item(key = "provider-bottom-space") { Spacer(Modifier.height(36.dp)) }
    }
}

@Composable
private fun ProviderRow(provider: GatewayProvider, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            provider.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (provider.models.isEmpty()) S.providerNoModels else S.providerModels(provider.models.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

/** Bottom sheet with the provider's models and sign-in / sign-out actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDetailSheet(
    provider: GatewayProvider,
    logoutRunning: Boolean,
    onLogin: (GatewayProviderAuthMethod) -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            ) {
                Text(
                    provider.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
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

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            Text(
                if (provider.models.isEmpty()) S.providerNoModels else S.providerModels(provider.models.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (provider.models.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    provider.models.forEachIndexed { index, model ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 20.dp),
                            )
                        }
                        ProviderModelRow(model)
                    }
                }
            }

            if (provider.authMethods.isNotEmpty() || provider.storedAuthType.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                provider.authMethods.forEach { method ->
                    val methodName = method.label.ifBlank { method.name }
                    ProviderSheetAction(
                        icon = Icons.AutoMirrored.Filled.Login,
                        label = "${if (provider.configured) S.providerRelogin else S.providerLogin} · $methodName",
                        onClick = { onLogin(method) },
                    )
                }
                if (provider.storedAuthType.isNotBlank()) {
                    ProviderSheetAction(
                        icon = Icons.AutoMirrored.Filled.Logout,
                        label = S.providerLogout,
                        destructive = true,
                        busy = logoutRunning,
                        onClick = onLogout,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProviderSheetAction(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val tint =
        if (destructive) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 13.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ProviderModelRow(model: GatewayProviderModel) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
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
private fun ProviderAuthFlowHost(vm: AppViewModel) {
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
