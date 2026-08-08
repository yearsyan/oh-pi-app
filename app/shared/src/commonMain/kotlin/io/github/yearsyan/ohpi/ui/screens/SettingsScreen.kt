package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import io.github.yearsyan.ohpi.appVersion
import io.github.yearsyan.ohpi.data.AppLanguage
import io.github.yearsyan.ohpi.data.ServerProfile
import io.github.yearsyan.ohpi.data.SshPrivateKey
import io.github.yearsyan.ohpi.data.ThemeMode
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.i18n.Strings
import io.github.yearsyan.ohpi.net.gatewayAddressLabel
import io.github.yearsyan.ohpi.net.GatewayRuntimeConfig
import io.github.yearsyan.ohpi.ssh.PlatformSsh
import io.github.yearsyan.ohpi.ui.AppViewModel
import io.github.yearsyan.ohpi.ui.GatewayServerInfo
import io.github.yearsyan.ohpi.ui.components.ConfirmDialog
import io.github.yearsyan.ohpi.ui.components.longPressHaptic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ohpiapp.shared.generated.resources.Res
import ohpiapp.shared.generated.resources.app_icon
import org.jetbrains.compose.resources.painterResource

private val SettingsListPaneWidth = 320.dp

/** Top-level settings destinations shown in the master list; each opens a detail page. */
enum class SettingsSection {
    Servers,
    Gateway,
    SshKeys,
    Providers,
    Appearance,
    Language,
    About,
    Licenses,
}

/** Parses a [SettingsSection] from its route argument; null when unknown. */
internal fun settingsSectionFor(name: String): SettingsSection? =
    SettingsSection.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

/** Sections with too little content for a full page open a bottom sheet instead. */
private fun settingsSectionUsesSheet(section: SettingsSection): Boolean = when (section) {
    SettingsSection.Appearance, SettingsSection.Language, SettingsSection.About -> true
    else -> false
}

/**
 * Adaptive settings: a master list of sections. On wide layouts the list stays
 * pinned on the left with the selected section's detail page on the right (the
 * same list+detail interaction as the session list). On phones the list pushes
 * the detail page onto the navigation stack instead.
 */
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    servers: List<ServerProfile>,
    sshKeys: List<SshPrivateKey>,
    activeServerId: String,
    themeMode: ThemeMode,
    language: AppLanguage,
    wide: Boolean,
    section: SettingsSection?,
    onBack: (() -> Unit)?,
    onOpenSection: (SettingsSection) -> Unit,
    onSelectServer: (String) -> Unit,
    onSaveServer: (ServerProfile, SshPrivateKey?) -> Unit,
    onDeleteServer: (String) -> Unit,
    onSaveSshKey: (SshPrivateKey) -> Unit,
    onDeleteSshKey: (SshPrivateKey) -> Unit,
    onStopManagedGateway: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onAddServer: () -> Unit,
    onAddProvider: () -> Unit,
) {
    var sheetSectionName by rememberSaveable { mutableStateOf<String?>(null) }

    // Light sections open a sheet; full pages navigate (compact) or swap the
    // detail pane (wide).
    fun openSection(target: SettingsSection, openPage: (SettingsSection) -> Unit) {
        if (settingsSectionUsesSheet(target)) sheetSectionName = target.name
        else openPage(target)
    }

    when {
        wide -> {
            var selectedName by rememberSaveable {
                mutableStateOf(section?.name ?: SettingsSection.Servers.name)
            }
            val selected = settingsSectionFor(selectedName) ?: SettingsSection.Servers
            Row(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .safeDrawingPadding(),
            ) {
                SettingsListPane(
                    servers = servers,
                    sshKeys = sshKeys,
                    activeServerId = activeServerId,
                    themeMode = themeMode,
                    language = language,
                    selectedSection = selected,
                    onBack = onBack,
                    onSelect = { target -> openSection(target) { selectedName = it.name } },
                    modifier = Modifier.width(SettingsListPaneWidth),
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f)) {
                    SettingsDetail(
                        section = selected,
                        vm = vm,
                        servers = servers,
                        sshKeys = sshKeys,
                        activeServerId = activeServerId,
                        themeMode = themeMode,
                        language = language,
                        onBack = null,
                        onSelectServer = onSelectServer,
                        onSaveServer = onSaveServer,
                        onDeleteServer = onDeleteServer,
                        onSaveSshKey = onSaveSshKey,
                        onDeleteSshKey = onDeleteSshKey,
                        onStopManagedGateway = onStopManagedGateway,
                        onThemeMode = onThemeMode,
                        onLanguage = onLanguage,
                        onAddServer = onAddServer,
                        onAddProvider = onAddProvider,
                    )
                }
            }
        }

        section != null -> Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding(),
        ) {
            SettingsDetail(
                section = section,
                vm = vm,
                servers = servers,
                sshKeys = sshKeys,
                activeServerId = activeServerId,
                themeMode = themeMode,
                language = language,
                onBack = onBack,
                onSelectServer = onSelectServer,
                onSaveServer = onSaveServer,
                onDeleteServer = onDeleteServer,
                onSaveSshKey = onSaveSshKey,
                onDeleteSshKey = onDeleteSshKey,
                onStopManagedGateway = onStopManagedGateway,
                onThemeMode = onThemeMode,
                onLanguage = onLanguage,
                onAddServer = onAddServer,
                onAddProvider = onAddProvider,
            )
        }

        else -> Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding(),
        ) {
            SettingsListPane(
                servers = servers,
                sshKeys = sshKeys,
                activeServerId = activeServerId,
                themeMode = themeMode,
                language = language,
                selectedSection = null,
                onBack = onBack,
                onSelect = { target -> openSection(target, onOpenSection) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    sheetSectionName?.let { name ->
        val sheetSection = settingsSectionFor(name)
        if (sheetSection != null && settingsSectionUsesSheet(sheetSection)) {
            SettingsSectionSheet(
                section = sheetSection,
                themeMode = themeMode,
                language = language,
                onThemeMode = onThemeMode,
                onLanguage = onLanguage,
                onDismiss = { sheetSectionName = null },
            )
        }
    }
}

/** Master list of settings sections, styled like the session list pane. */
@Composable
private fun SettingsListPane(
    servers: List<ServerProfile>,
    sshKeys: List<SshPrivateKey>,
    activeServerId: String,
    themeMode: ThemeMode,
    language: AppLanguage,
    selectedSection: SettingsSection?,
    onBack: (() -> Unit)?,
    onSelect: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = S
    val activeServer = servers.firstOrNull { it.id == activeServerId }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S.back)
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                S.settingsTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            SettingsSection.entries.forEachIndexed { index, settingsSection ->
                SettingsSectionRow(
                    icon = settingsSectionIcon(settingsSection),
                    title = settingsSectionTitle(settingsSection, strings),
                    subtitle =
                        settingsSectionSubtitle(
                            settingsSection,
                            strings,
                            activeServer,
                            sshKeys.size,
                            themeMode,
                            language,
                        ),
                    selected = settingsSection == selectedSection,
                    showChevron = selectedSection == null,
                    onClick = { onSelect(settingsSection) },
                )
                if (index < SettingsSection.entries.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 48.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 0.5.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    selected: Boolean,
    showChevron: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showChevron) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Routes a section to its detail page; null [onBack] hides the back arrow (wide). */
@Composable
private fun SettingsDetail(
    section: SettingsSection,
    vm: AppViewModel,
    servers: List<ServerProfile>,
    sshKeys: List<SshPrivateKey>,
    activeServerId: String,
    themeMode: ThemeMode,
    language: AppLanguage,
    onBack: (() -> Unit)?,
    onSelectServer: (String) -> Unit,
    onSaveServer: (ServerProfile, SshPrivateKey?) -> Unit,
    onDeleteServer: (String) -> Unit,
    onSaveSshKey: (SshPrivateKey) -> Unit,
    onDeleteSshKey: (SshPrivateKey) -> Unit,
    onStopManagedGateway: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onAddServer: () -> Unit,
    onAddProvider: () -> Unit,
) {
    when (section) {
        SettingsSection.Servers -> ServersSettingsDetail(
            servers = servers,
            sshKeys = sshKeys,
            activeServerId = activeServerId,
            gatewayInfo = vm.activeGatewayInfo,
            onBack = onBack,
            onSelectServer = onSelectServer,
            onSaveServer = onSaveServer,
            onDeleteServer = onDeleteServer,
            onStopManagedGateway = onStopManagedGateway,
            onAddServer = onAddServer,
        )

        SettingsSection.Gateway -> GatewayRuntimeSettingsDetail(
            vm = vm,
            onBack = onBack,
        )

        SettingsSection.SshKeys -> SshKeysSettingsDetail(
            sshKeys = sshKeys,
            servers = servers,
            onBack = onBack,
            onSaveSshKey = onSaveSshKey,
            onDeleteSshKey = onDeleteSshKey,
        )

        SettingsSection.Providers -> ProvidersScreen(
            vm = vm,
            onBack = onBack,
            onAddProvider = onAddProvider,
        )

        // Sheet sections are normally opened as bottom sheets; these scaffold
        // fallbacks keep a pushed [SettingsSectionRoute] usable.
        SettingsSection.Appearance -> SettingsDetailScaffold(
            title = S.appearanceSection,
            onBack = onBack,
        ) {
            ThemeModePicker(themeMode, onThemeMode)
        }

        SettingsSection.Language -> SettingsDetailScaffold(
            title = S.languageSection,
            onBack = onBack,
        ) {
            LanguagePicker(language, onLanguage)
        }

        SettingsSection.About -> SettingsDetailScaffold(title = S.aboutSection, onBack = onBack) {
            AboutContent()
        }

        SettingsSection.Licenses -> LicensesScreen(onBack = onBack)
    }
}

/** Shared detail-page chrome: title header plus a centered, scrollable body. */
@Composable
private fun SettingsDetailScaffold(
    title: String,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S.back)
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.CenterHorizontally),
        ) {
            content()
        }
    }
}

@Composable
private fun ServersSettingsDetail(
    servers: List<ServerProfile>,
    sshKeys: List<SshPrivateKey>,
    activeServerId: String,
    gatewayInfo: GatewayServerInfo?,
    onBack: (() -> Unit)?,
    onSelectServer: (String) -> Unit,
    onSaveServer: (ServerProfile, SshPrivateKey?) -> Unit,
    onDeleteServer: (String) -> Unit,
    onStopManagedGateway: () -> Unit,
    onAddServer: () -> Unit,
) {
    var editing by remember { mutableStateOf<ServerProfile?>(null) }
    var deleting by remember { mutableStateOf<ServerProfile?>(null) }
    var stopping by remember { mutableStateOf<ServerProfile?>(null) }

    SettingsDetailScaffold(title = S.serversSection, onBack = onBack) {
        servers.forEach { server ->
            ServerRow(
                server = server,
                active = server.id == activeServerId,
                gatewayInfo = gatewayInfo.takeIf { server.id == activeServerId },
                onSelect = { onSelectServer(server.id) },
                onEdit = { editing = server },
                onDelete = { deleting = server },
                onStopManaged =
                    if (server.id == activeServerId && server.connectionMode.isManaged) {
                        { stopping = server }
                    } else {
                        null
                    },
            )
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = onAddServer) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(S.addServer)
        }
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { server ->
        ServerEditDialog(
            initial = server,
            keys = sshKeys,
            onDismiss = { editing = null },
            onSave = onSaveServer,
        )
    }
    deleting?.let { server ->
        ConfirmDialog(
            title = S.deleteServerTitle,
            body = S.deleteServerBody,
            confirmLabel = S.delete,
            onDismiss = { deleting = null },
            onConfirm = { onDeleteServer(server.id) },
        )
    }
    stopping?.let {
        ConfirmDialog(
            title = S.stopManagedGatewayTitle,
            body = S.stopManagedGatewayBody,
            confirmLabel = S.stopManagedGateway,
            onDismiss = { stopping = null },
            onConfirm = {
                stopping = null
                onStopManagedGateway()
            },
        )
    }
}

private enum class GatewayEnvironmentPreset { None, Zsh, Bash, Custom }

private fun gatewayEnvironmentPreset(file: String, shell: String): GatewayEnvironmentPreset {
    if (file.isBlank()) return GatewayEnvironmentPreset.None
    val normalizedFile = file.lowercase()
    val normalizedShell = shell.lowercase()
    if ((normalizedFile == "~/.zshrc" || normalizedFile.endsWith("/.zshrc")) &&
        (normalizedShell.isBlank() || normalizedShell.endsWith("/zsh"))
    ) {
        return GatewayEnvironmentPreset.Zsh
    }
    if ((normalizedFile == "~/.bashrc" || normalizedFile.endsWith("/.bashrc")) &&
        (normalizedShell.isBlank() || normalizedShell.endsWith("/bash"))
    ) {
        return GatewayEnvironmentPreset.Bash
    }
    return GatewayEnvironmentPreset.Custom
}

@Composable
private fun GatewayRuntimeSettingsDetail(
    vm: AppViewModel,
    onBack: (() -> Unit)?,
) {
    val strings = S
    val scope = rememberCoroutineScope()
    val serverId = vm.activeServer?.id
    val gatewayInfo = vm.activeGatewayInfo
    val supported = gatewayInfo?.supportsRuntimeConfig == true
    var config by remember(serverId) { mutableStateOf<GatewayRuntimeConfig?>(null) }
    var loading by remember(serverId) { mutableStateOf(false) }
    var busy by remember(serverId) { mutableStateOf(false) }
    var error by remember(serverId) { mutableStateOf<String?>(null) }
    var status by remember(serverId) { mutableStateOf<String?>(null) }
    var reloadKey by remember(serverId) { mutableStateOf(0) }
    var titleModel by remember(serverId) { mutableStateOf("auto") }
    var environmentPreset by remember(serverId) { mutableStateOf(GatewayEnvironmentPreset.None) }
    var customEnvironmentFile by remember(serverId) { mutableStateOf("") }
    var customEnvironmentShell by remember(serverId) { mutableStateOf("") }
    var confirmRestart by remember(serverId) { mutableStateOf(false) }

    fun applyConfig(value: GatewayRuntimeConfig) {
        config = value
        titleModel = value.titleModel
        customEnvironmentFile = value.piEnvironmentFile
        customEnvironmentShell = value.piEnvironmentShell
        environmentPreset = gatewayEnvironmentPreset(value.piEnvironmentFile, value.piEnvironmentShell)
    }

    LaunchedEffect(serverId, supported, reloadKey) {
        if (serverId == null || !supported) return@LaunchedEffect
        loading = true
        error = null
        try {
            applyConfig(vm.loadGatewayRuntimeConfig())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = strings.gatewayRuntimeFailed(failure.message ?: "unknown error")
        } finally {
            loading = false
        }
    }

    fun save(restart: Boolean) {
        val (environmentFile, environmentShell) = when (environmentPreset) {
            GatewayEnvironmentPreset.None -> "" to ""
            GatewayEnvironmentPreset.Zsh -> "~/.zshrc" to "/bin/zsh"
            GatewayEnvironmentPreset.Bash -> "~/.bashrc" to "/bin/bash"
            GatewayEnvironmentPreset.Custom ->
                customEnvironmentFile.trim() to customEnvironmentShell.trim()
        }
        scope.launch {
            busy = true
            error = null
            status = null
            try {
                var updated = vm.saveGatewayRuntimeConfig(
                    titleModel = titleModel.trim(),
                    piEnvironmentFile = environmentFile,
                    piEnvironmentShell = environmentShell,
                )
                if (restart) {
                    updated = vm.restartGatewayRuntime()
                }
                applyConfig(updated)
                status = if (restart) strings.gatewayRuntimeRestarted else strings.gatewayRuntimeSaved
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = strings.gatewayRuntimeFailed(failure.message ?: "unknown error")
            } finally {
                busy = false
            }
        }
    }

    SettingsDetailScaffold(title = S.gatewayRuntimeSection, onBack = onBack) {
        when {
            serverId == null -> Text(
                S.gatewayRuntimeUnavailable,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            gatewayInfo != null && !supported -> Text(
                S.gatewayRuntimeUnsupported,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(S.gatewayRuntimeLoading)
            }

            config == null -> {
                Text(
                    error ?: S.gatewayRuntimeUnavailable,
                    color = if (error == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (supported) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { reloadKey++ }) { Text(S.retry) }
                }
            }

            else -> {
                Text(
                    S.gatewayEnvironmentSource,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                val presets = listOf(
                    GatewayEnvironmentPreset.None to S.gatewayEnvironmentNone,
                    GatewayEnvironmentPreset.Zsh to "zsh",
                    GatewayEnvironmentPreset.Bash to "bash",
                    GatewayEnvironmentPreset.Custom to S.gatewayEnvironmentCustom,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    presets.forEachIndexed { index, (preset, label) ->
                        SegmentedButton(
                            selected = environmentPreset == preset,
                            onClick = { environmentPreset = preset },
                            enabled = !busy,
                            shape = SegmentedButtonDefaults.itemShape(index, presets.size),
                        ) { Text(label, maxLines = 1) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    S.gatewayEnvironmentHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (environmentPreset == GatewayEnvironmentPreset.Custom) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customEnvironmentFile,
                        onValueChange = { customEnvironmentFile = it },
                        label = { Text(S.gatewayEnvironmentFile) },
                        enabled = !busy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customEnvironmentShell,
                        onValueChange = { customEnvironmentShell = it },
                        label = { Text(S.gatewayEnvironmentShell) },
                        enabled = !busy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(22.dp))
                OutlinedTextField(
                    value = titleModel,
                    onValueChange = { titleModel = it },
                    label = { Text(S.gatewayTitleModel) },
                    supportingText = { Text(S.gatewayTitleModelHint) },
                    enabled = !busy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                config?.takeIf { it.restartRequired }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        S.gatewayRuntimeRestartRequired,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }

                val customSourceValid =
                    environmentPreset != GatewayEnvironmentPreset.Custom || customEnvironmentFile.isNotBlank()
                val canSave = !busy && titleModel.isNotBlank() && customSourceValid
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { save(restart = false) },
                        enabled = canSave,
                        modifier = Modifier.weight(1f),
                    ) { Text(S.gatewayRuntimeSave) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { confirmRestart = true },
                        enabled = canSave && config?.restartSupported == true,
                        modifier = Modifier.weight(1f),
                    ) { Text(S.gatewayRuntimeSaveAndRestart) }
                }
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmRestart) {
        ConfirmDialog(
            title = S.gatewayRuntimeRestartTitle,
            body = S.gatewayRuntimeRestartBody,
            confirmLabel = S.gatewayRuntimeRestartConfirm,
            onDismiss = { confirmRestart = false },
            onConfirm = {
                confirmRestart = false
                save(restart = true)
            },
        )
    }
}

private enum class SshKeyAddType { Existing, Generate }

@Composable
private fun SshKeysSettingsDetail(
    sshKeys: List<SshPrivateKey>,
    servers: List<ServerProfile>,
    onBack: (() -> Unit)?,
    onSaveSshKey: (SshPrivateKey) -> Unit,
    onDeleteSshKey: (SshPrivateKey) -> Unit,
) {
    var editingKey by remember { mutableStateOf<SshPrivateKey?>(null) }
    var choosingAddType by remember { mutableStateOf(false) }
    var addingKeyType by remember { mutableStateOf<SshKeyAddType?>(null) }
    var actionKey by remember { mutableStateOf<SshPrivateKey?>(null) }
    var viewingPublicKey by remember { mutableStateOf<SshPrivateKey?>(null) }
    var deletingKey by remember { mutableStateOf<SshPrivateKey?>(null) }

    SettingsDetailScaffold(title = S.sshKeysSection, onBack = onBack) {
        if (sshKeys.isEmpty()) {
            Text(
                S.sshKeysEmpty,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        sshKeys.forEach { key ->
            SshKeyRow(
                key = key,
                usageCount = servers.count { it.ssh.privateKeyId == key.id },
                onClick = { actionKey = key },
            )
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = { choosingAddType = true }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(S.sshKeyAdd)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (choosingAddType) {
        SshKeyAddTypeSheet(
            onDismiss = { choosingAddType = false },
            onSelect = {
                choosingAddType = false
                addingKeyType = it
            },
        )
    }
    if (addingKeyType == SshKeyAddType.Existing) {
        SshKeyEditDialog(
            initial = null,
            onDismiss = { addingKeyType = null },
            onSave = {
                addingKeyType = null
                onSaveSshKey(it)
            },
        )
    }
    if (addingKeyType == SshKeyAddType.Generate) {
        SshKeyGenerateDialog(
            onDismiss = { addingKeyType = null },
            onSave = {
                addingKeyType = null
                onSaveSshKey(it)
            },
        )
    }
    editingKey?.let { key ->
        SshKeyEditDialog(
            initial = key,
            onDismiss = { editingKey = null },
            onSave = {
                editingKey = null
                onSaveSshKey(it)
            },
        )
    }
    actionKey?.let { key ->
        SshKeyActionsSheet(
            key = key,
            onDismiss = { actionKey = null },
            onViewPublic = {
                actionKey = null
                viewingPublicKey = key
            },
            onEdit = {
                actionKey = null
                editingKey = key
            },
            onDelete = {
                actionKey = null
                deletingKey = key
            },
        )
    }
    viewingPublicKey?.let { key ->
        SshPublicKeyDialog(
            key = key,
            onDismiss = { viewingPublicKey = null },
        )
    }
    deletingKey?.let { key ->
        ConfirmDialog(
            title = S.sshKeyDeleteTitle,
            body = S.sshKeyDeleteBody(key.name),
            confirmLabel = S.delete,
            onDismiss = { deletingKey = null },
            onConfirm = { onDeleteSshKey(key) },
        )
    }
}

/** Bottom sheet for light sections (appearance / language / about). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSectionSheet(
    section: SettingsSection,
    themeMode: ThemeMode,
    language: AppLanguage,
    onThemeMode: (ThemeMode) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                settingsSectionTitle(section, S),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))
            when (section) {
                SettingsSection.Appearance -> ThemeModePicker(themeMode, onThemeMode)
                SettingsSection.Language -> LanguagePicker(language, onLanguage)
                SettingsSection.About -> AboutContent()
                else -> {}
            }
        }
    }
}

@Composable
private fun ThemeModePicker(themeMode: ThemeMode, onThemeMode: (ThemeMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val items = listOf(
            ThemeMode.System to S.themeSystem,
            ThemeMode.Light to S.themeLight,
            ThemeMode.Dark to S.themeDark,
        )
        items.forEachIndexed { i, (mode, label) ->
            SegmentedButton(
                selected = themeMode == mode,
                onClick = { onThemeMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = items.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun LanguagePicker(language: AppLanguage, onLanguage: (AppLanguage) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        val items = listOf(
            AppLanguage.System to S.languageSystem,
            AppLanguage.English to "English",
            AppLanguage.Chinese to "中文",
        )
        items.forEachIndexed { i, (lang, label) ->
            SegmentedButton(
                selected = language == lang,
                onClick = { onLanguage(lang) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = items.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun AboutContent() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .border(
                    0.5.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(14.dp),
                ),
        ) {
            Image(
                painter = painterResource(Res.drawable.app_icon),
                contentDescription = S.appName,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                S.appName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "v${appVersion()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        S.appTagline,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun settingsSectionIcon(section: SettingsSection): ImageVector = when (section) {
    SettingsSection.Servers -> Icons.Filled.Cloud
    SettingsSection.Gateway -> Icons.Filled.Settings
    SettingsSection.SshKeys -> Icons.Filled.Key
    SettingsSection.Providers -> Icons.Filled.Psychology
    SettingsSection.Appearance -> Icons.Filled.Palette
    SettingsSection.Language -> Icons.Filled.Language
    SettingsSection.About -> Icons.Filled.Info
    SettingsSection.Licenses -> Icons.Filled.Gavel
}

private fun settingsSectionTitle(section: SettingsSection, strings: Strings): String =
    when (section) {
        SettingsSection.Servers -> strings.serversSection
        SettingsSection.Gateway -> strings.gatewayRuntimeSection
        SettingsSection.SshKeys -> strings.sshKeysSection
        SettingsSection.Providers -> strings.providersSection
        SettingsSection.Appearance -> strings.appearanceSection
        SettingsSection.Language -> strings.languageSection
        SettingsSection.About -> strings.aboutSection
        SettingsSection.Licenses -> strings.openSourceLicenses
    }

private fun settingsSectionSubtitle(
    section: SettingsSection,
    strings: Strings,
    activeServer: ServerProfile?,
    sshKeyCount: Int,
    themeMode: ThemeMode,
    language: AppLanguage,
): String? = when (section) {
    SettingsSection.Servers -> activeServer?.displayName
    SettingsSection.Gateway -> strings.gatewayRuntimeDescription
    SettingsSection.SshKeys -> strings.sshKeyCount(sshKeyCount)
    SettingsSection.Providers -> strings.providerSettingsDescription
    SettingsSection.Appearance -> when (themeMode) {
        ThemeMode.System -> strings.themeSystem
        ThemeMode.Light -> strings.themeLight
        ThemeMode.Dark -> strings.themeDark
    }

    SettingsSection.Language -> when (language) {
        AppLanguage.System -> strings.languageSystem
        AppLanguage.English -> "English"
        AppLanguage.Chinese -> "中文"
    }

    SettingsSection.About -> "v${appVersion()}"
    SettingsSection.Licenses ->
        "${strings.licenseApache20} · ${strings.licenseMit} · ${strings.licenseLgpl21}"
}

@Composable
private fun ServerRow(
    server: ServerProfile,
    active: Boolean,
    gatewayInfo: GatewayServerInfo?,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onStopManaged: (() -> Unit)?,
) {
    val gatewayLabel = gatewayAddressLabel(server.url)
    Surface(
        color = if (active) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onSelect)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(
                if (active) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    server.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (server.connectionMode.usesSsh) {
                        val mode = if (server.connectionMode.isManaged) S.managedSshConnection else "SSH"
                        "$mode · ${server.ssh.username}@${server.ssh.host}:${server.ssh.port} → $gatewayLabel"
                    } else {
                        gatewayLabel
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                gatewayInfo?.takeIf { it.version.isNotBlank() }?.let { info ->
                    Text(
                        S.gatewayVersion(info.version, info.protocol),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (active) {
                Text(
                    S.activeServerHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            if (onStopManaged != null) {
                IconButton(onClick = onStopManaged, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Filled.PowerSettingsNew,
                        contentDescription = S.stopManagedGateway,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = S.editServer,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = S.delete,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
fun ServerEditDialog(
    initial: ServerProfile?,
    keys: List<SshPrivateKey> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (ServerProfile, SshPrivateKey?) -> Unit,
) {
    val editor = rememberServerEditor(initial)
    val strings = S

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) S.addServer else S.editServer) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ServerEditorFields(editor, keys)
            }
        },
        confirmButton = {
            Button(onClick = {
                editor.build(strings, keys)?.let { result ->
                    onSave(result.profile, result.newKey)
                    onDismiss()
                }
            }) { Text(S.confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S.cancel) }
        },
    )
}

@Composable
private fun SshKeyRow(
    key: SshPrivateKey,
    usageCount: Int,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = {
                            longPressHaptic()
                            onClick()
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Icon(
                Icons.Filled.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    key.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    S.sshKeyUsage(usageCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (key.publicKey.isNotBlank()) {
                Icon(
                    Icons.Filled.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SshKeyEditDialog(
    initial: SshPrivateKey?,
    onDismiss: () -> Unit,
    onSave: (SshPrivateKey) -> Unit,
) {
    val strings = S
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var contents by remember { mutableStateOf(initial?.privateKey.orEmpty()) }
    var passphrase by remember { mutableStateOf(initial?.passphrase.orEmpty()) }
    var publicKey by remember { mutableStateOf(initial?.publicKey.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) S.sshKeyAddExisting else S.sshKeyEdit) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text(S.sshKeyNameLabel) },
                    placeholder = { Text(S.sshKeyNamePlaceholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = contents,
                    onValueChange = { contents = it; error = null },
                    label = { Text(S.sshPrivateKeyLabel) },
                    minLines = 4,
                    maxLines = 8,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(S.sshPrivateKeyPassphraseLabel) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = publicKey,
                    onValueChange = { publicKey = it },
                    label = { Text(S.sshKeyPublicLabel) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (contents.isBlank()) {
                    error = strings.sshPrivateKeyRequired
                    return@Button
                }
                onSave(
                    SshPrivateKey(
                        id = initial?.id ?: ("key-" + Random.nextLong().toString(16)),
                        name = name.trim().ifBlank { strings.sshKeyDefaultName },
                        privateKey = contents.trim(),
                        passphrase = passphrase,
                        publicKey = publicKey.trim(),
                    ),
                )
            }) { Text(S.confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S.cancel) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshKeyAddTypeSheet(
    onDismiss: () -> Unit,
    onSelect: (SshKeyAddType) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
            Text(
                S.sshKeyAddChoiceTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SshKeySheetAction(
                icon = Icons.Filled.Key,
                label = S.sshKeyAddExisting,
                supportingText = S.sshKeyAddExistingHint,
                onClick = { onSelect(SshKeyAddType.Existing) },
            )
            SshKeySheetAction(
                icon = Icons.Filled.Add,
                label = S.sshKeyGenerate,
                supportingText = S.sshKeyGenerateHint,
                onClick = { onSelect(SshKeyAddType.Generate) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshKeyActionsSheet(
    key: SshPrivateKey,
    onDismiss: () -> Unit,
    onViewPublic: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text(
                key.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            if (key.publicKey.isNotBlank()) {
                SshKeySheetAction(
                    icon = Icons.Filled.Visibility,
                    label = S.sshKeyViewPublic,
                    onClick = onViewPublic,
                )
            }
            SshKeySheetAction(
                icon = Icons.Filled.Edit,
                label = S.sshKeyEdit,
                onClick = onEdit,
            )
            SshKeySheetAction(
                icon = Icons.Filled.Delete,
                label = S.delete,
                destructive = true,
                onClick = onDelete,
            )
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(S.cancel, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun SshKeySheetAction(
    icon: ImageVector,
    label: String,
    supportingText: String? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint =
        if (destructive) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (supportingText != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SshPublicKeyDialog(
    key: SshPrivateKey,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(key.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.sshKeyPublicTitle) },
        text = {
            Column {
                Text(
                    key.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                SelectionContainer {
                    Text(key.publicKey, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(key.publicKey))
                    copied = true
                },
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (copied) S.copied else S.copy)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.close) } },
    )
}

@Composable
private fun SshKeyGenerateDialog(
    onDismiss: () -> Unit,
    onSave: (SshPrivateKey) -> Unit,
) {
    val strings = S
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!generating) onDismiss() },
        title = { Text(S.sshKeyGenerate) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    S.sshKeyGenerateHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text(S.sshKeyNameLabel) },
                    placeholder = { Text(S.sshKeyNamePlaceholder) },
                    singleLine = true,
                    enabled = !generating,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it; error = null },
                    label = { Text(S.sshPrivateKeyPassphraseLabel) },
                    singleLine = true,
                    enabled = !generating,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !generating,
                onClick = {
                    generating = true
                    error = null
                    scope.launch {
                        try {
                            val generated =
                                withContext(Dispatchers.Default) {
                                    PlatformSsh.generateEd25519KeyPair(
                                        passphrase.takeIf { it.isNotEmpty() },
                                    )
                                }
                            onSave(
                                SshPrivateKey(
                                    id = "key-" + Random.nextLong().toString(16),
                                    name = name.trim().ifBlank { strings.sshKeyDefaultName },
                                    privateKey = generated.privateKey,
                                    passphrase = passphrase,
                                    publicKey = generated.publicKey.trim(),
                                ),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            error =
                                strings.sshKeyGenerationFailed(
                                    failure.message.orEmpty().ifBlank { "libssh" },
                                )
                        } finally {
                            generating = false
                        }
                    }
                },
            ) {
                if (generating) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(7.dp))
                }
                Text(if (generating) S.sshKeyGenerating else S.sshKeyGenerate)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !generating) { Text(S.cancel) }
        },
    )
}
