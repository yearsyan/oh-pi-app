package io.github.yearsyan.ohpi.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.chat.Toast
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.i18n.Strings
import io.github.yearsyan.ohpi.net.GatewayCapabilities
import io.github.yearsyan.ohpi.net.GatewayScheduledTaskMutation
import io.github.yearsyan.ohpi.net.GatewayTaskSchedule
import io.github.yearsyan.ohpi.net.ScheduledTaskKinds
import io.github.yearsyan.ohpi.ui.AppViewModel
import io.github.yearsyan.ohpi.ui.components.AppDropdownMenu
import io.github.yearsyan.ohpi.ui.components.AppMenuItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

private enum class ScheduledTaskIntervalUnit(val seconds: Long) {
    Minutes(60),
    Hours(60 * 60),
    Days(24 * 60 * 60),
}

private enum class ScheduledTaskEditorPicker {
    CronTime,
    AnchorDate,
    AnchorTime,
    RunAtDate,
    RunAtTime,
}

@Composable
fun ScheduledTaskEditorScreen(
    vm: AppViewModel,
    taskId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val strings = S
    val timeZone = remember { TimeZone.currentSystemDefault() }
    var loading by remember(taskId, vm.activeServerId) { mutableStateOf(true) }
    var initialized by remember(taskId, vm.activeServerId) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var name by remember { mutableStateOf("") }
    var workspaceId by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf("") }
    var skillPaths by remember { mutableStateOf("") }
    var noSkills by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var scheduleKind by remember { mutableStateOf(ScheduledTaskKinds.Cron) }
    var eventKey by remember { mutableStateOf("") }
    var cronWeekdays by remember { mutableStateOf((1..5).toSet()) }
    var cronTime by remember { mutableStateOf(LocalTime(9, 0)) }
    var cronTimezone by remember { mutableStateOf(timeZone.id) }
    var intervalValue by remember { mutableStateOf("60") }
    var intervalUnit by remember { mutableStateOf(ScheduledTaskIntervalUnit.Minutes) }
    var anchorDateTime by remember { mutableStateOf(Clock.System.now().toLocalDateTime(timeZone)) }
    var runAtDateTime by remember { mutableStateOf((Clock.System.now() + 1.hours).toLocalDateTime(timeZone)) }
    var activePicker by remember { mutableStateOf<ScheduledTaskEditorPicker?>(null) }

    var capabilities by remember { mutableStateOf<GatewayCapabilities?>(null) }
    var capabilitiesLoading by remember { mutableStateOf(false) }
    var capabilitiesError by remember { mutableStateOf<String?>(null) }
    val supportsSkillConfiguration = vm.activeGatewayInfo?.supportsScheduledTaskSkills == true
    val supportsHTTPTriggers = vm.activeGatewayInfo?.supportsScheduledHTTPTriggers == true

    LaunchedEffect(taskId, vm.activeServerId) {
        loading = true
        initialized = false
        error = null
        val now = Clock.System.now()
        anchorDateTime = now.toLocalDateTime(timeZone)
        runAtDateTime = (now + 1.hours).toLocalDateTime(timeZone)
        skillPaths = ""
        noSkills = false
        eventKey = ""
        try {
            if (taskId == null) {
                workspaceId = vm.workspaces.firstOrNull()?.id.orEmpty()
            } else {
                val task = vm.loadScheduledTask(taskId)
                name = task.name
                workspaceId = task.workspaceId
                model = task.model
                thinking = task.thinking
                skillPaths = task.skillPaths.joinToString("\n")
                noSkills = task.noSkills
                prompt = task.prompt
                enabled = task.enabled
                scheduleKind = task.schedule.kind
                eventKey = task.eventKey
                if (task.schedule.kind == ScheduledTaskKinds.Cron) {
                    val weeklySchedule = parseScheduledTaskWeeklyCron(task.schedule.expression)
                        ?: throw IllegalArgumentException(strings.scheduledTaskUnsupportedCron)
                    cronWeekdays = weeklySchedule.weekdays
                    cronTime = LocalTime(weeklySchedule.hour, weeklySchedule.minute)
                    cronTimezone = task.schedule.timezone.ifBlank { cronTimezone }
                }
                intervalValue = intervalDisplayValue(task.schedule.everySeconds).first
                intervalUnit = intervalDisplayValue(task.schedule.everySeconds).second
                task.schedule.anchorAt
                    ?.let { rfc3339ToLocalDateTime(it, timeZone) }
                    ?.let { anchorDateTime = it }
                task.schedule.at
                    ?.let { rfc3339ToLocalDateTime(it, timeZone) }
                    ?.let { runAtDateTime = it }
            }
            initialized = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = strings.scheduledTaskLoadFailed(failure.message ?: strings.unknownError)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(initialized, workspaceId, vm.activeServerId) {
        if (!initialized || workspaceId.isBlank()) {
            capabilities = null
            capabilitiesError = null
            return@LaunchedEffect
        }
        capabilitiesLoading = true
        capabilitiesError = null
        try {
            capabilities = vm.loadScheduledTaskCapabilities(workspaceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            capabilities = null
            capabilitiesError = strings.modelOptionsFailed(failure.message ?: strings.unknownError)
        } finally {
            capabilitiesLoading = false
        }
    }

    fun save() {
        if (saving) return
        error = null
        val schedule = validateScheduledTaskEditor(
            name = name,
            workspaceId = workspaceId,
            prompt = prompt,
            kind = scheduleKind,
            cronWeekdays = cronWeekdays,
            cronTime = cronTime,
            cronTimezone = cronTimezone,
            intervalValue = intervalValue,
            intervalUnit = intervalUnit,
            anchorDateTime = anchorDateTime,
            runAtDateTime = runAtDateTime,
            timeZone = timeZone,
            strings = strings,
        ).getOrElse { failure ->
            error = failure.message
            return
        }
        val mutation = GatewayScheduledTaskMutation(
            name = name.trim(),
            workspaceId = workspaceId,
            model = model.trim(),
            thinking = if (model.isBlank()) "" else thinking.trim(),
            skillPaths = parseScheduledTaskSkillPaths(skillPaths),
            noSkills = noSkills,
            prompt = prompt.trim(),
            schedule = schedule,
            enabled = enabled,
        )
        saving = true
        scope.launch {
            try {
                if (taskId == null) vm.createScheduledTask(mutation)
                else vm.updateScheduledTask(taskId, mutation)
                vm.toast(strings.scheduledTaskSaved, Toast.Kind.Success)
                onSaved()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = strings.scheduledTaskSaveFailed(failure.message ?: strings.unknownError)
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(enabled = !saving, onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
            }
            Text(
                if (taskId == null) strings.scheduledTaskCreateTitle else strings.scheduledTaskEditTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                strings.scheduledTaskSave,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (initialized && !saving) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = initialized && !saving, onClick = ::save)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            !initialized -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                EditorErrorCard(error ?: strings.unknownError)
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing
                            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                            .union(WindowInsets.ime),
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    error?.let { EditorErrorCard(it) }

                    EditorSection(title = strings.scheduledTaskSectionBasics) {
                        EditorTextField(
                            value = name,
                            onValueChange = { name = it; error = null },
                            label = strings.scheduledTaskName,
                            placeholder = strings.scheduledTaskNameHint,
                            enabled = !saving,
                        )
                        EditorDivider()
                        ScheduledTaskWorkspaceSelector(
                            vm = vm,
                            workspaceId = workspaceId,
                            enabled = !saving,
                            onSelect = { selected ->
                                workspaceId = selected
                                model = ""
                                thinking = ""
                                error = null
                            },
                        )
                    }

                    if (supportsSkillConfiguration) {
                        EditorSection(title = strings.scheduledTaskSkillsTitle) {
                            EditorTextField(
                                value = skillPaths,
                                onValueChange = { skillPaths = it; error = null },
                                label = strings.scheduledTaskSkillPathsLabel,
                                enabled = !saving,
                                singleLine = false,
                                minLines = 3,
                            )
                            EditorHint(strings.scheduledTaskSkillPathsHint)
                            EditorDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    strings.scheduledTaskNoSkillsLabel,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = noSkills,
                                    enabled = !saving,
                                    onCheckedChange = { noSkills = it; error = null },
                                )
                            }
                            EditorHint(strings.scheduledTaskNoSkillsHint)
                        }
                    }

                    EditorSection(title = strings.scheduledTaskScheduleType) {
                        ScheduledTaskKindSelector(
                            value = scheduleKind,
                            supportsHTTPTriggers = supportsHTTPTriggers,
                            enabled = !saving,
                            onSelect = { selected -> scheduleKind = selected; error = null },
                        )

                        when (scheduleKind) {
                            ScheduledTaskKinds.Cron -> {
                                EditorDivider()
                                EditorCaption(
                                    strings.scheduledTaskWeekdays,
                                    modifier = Modifier.padding(start = 16.dp, top = 10.dp),
                                )
                                ScheduledTaskWeekdaySelector(
                                    selected = cronWeekdays,
                                    enabled = !saving,
                                    onToggle = { weekday ->
                                        cronWeekdays =
                                            if (weekday in cronWeekdays) cronWeekdays - weekday
                                            else cronWeekdays + weekday
                                        error = null
                                    },
                                )
                                EditorDivider()
                                EditorValueRow(
                                    label = strings.scheduledTaskTimeLabel,
                                    value = formatEditorTime(cronTime),
                                    onClick = { activePicker = ScheduledTaskEditorPicker.CronTime },
                                    enabled = !saving,
                                    showChevron = false,
                                )
                                EditorHint("${strings.scheduledTaskTimezone}: $cronTimezone")
                            }

                            ScheduledTaskKinds.Interval -> {
                                EditorDivider()
                                EditorTextField(
                                    value = intervalValue,
                                    onValueChange = {
                                        intervalValue = it.filter(Char::isDigit)
                                        error = null
                                    },
                                    label = strings.scheduledTaskIntervalValue,
                                    enabled = !saving,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                                EditorDivider()
                                ScheduledTaskIntervalUnitSelector(
                                    value = intervalUnit,
                                    enabled = !saving,
                                    onSelect = { intervalUnit = it; error = null },
                                )
                                EditorDivider()
                                ScheduledTaskDateTimeRows(
                                    dateTime = anchorDateTime,
                                    timeZone = timeZone,
                                    enabled = !saving,
                                    onPickDate = { activePicker = ScheduledTaskEditorPicker.AnchorDate },
                                    onPickTime = { activePicker = ScheduledTaskEditorPicker.AnchorTime },
                                )
                            }

                            ScheduledTaskKinds.Once -> {
                                EditorDivider()
                                ScheduledTaskDateTimeRows(
                                    dateTime = runAtDateTime,
                                    timeZone = timeZone,
                                    enabled = !saving,
                                    onPickDate = { activePicker = ScheduledTaskEditorPicker.RunAtDate },
                                    onPickTime = { activePicker = ScheduledTaskEditorPicker.RunAtTime },
                                )
                            }

                            ScheduledTaskKinds.HTTP -> {
                                EditorDivider()
                                if (eventKey.isBlank()) {
                                    EditorHint(strings.scheduledTaskHTTPCreateHint)
                                } else {
                                    EditorCaption(
                                        strings.scheduledTaskHTTPEndpoint,
                                        modifier = Modifier.padding(start = 16.dp, top = 10.dp),
                                    )
                                    SelectionContainer {
                                        Text(
                                            "POST /api/task-events/$eventKey",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                            modifier = Modifier.fillMaxWidth().padding(
                                                horizontal = 16.dp,
                                                vertical = 10.dp,
                                            ),
                                        )
                                    }
                                }
                                EditorHint(strings.scheduledTaskHTTPHint)
                            }
                        }
                    }

                    EditorSection(title = strings.model) {
                        ScheduledTaskModelSelector(
                            capabilities = capabilities,
                            model = model,
                            thinking = thinking,
                            loading = capabilitiesLoading,
                            error = capabilitiesError,
                            enabled = !saving,
                            onModel = { selected -> model = selected; thinking = ""; error = null },
                            onThinking = { selected -> thinking = selected; error = null },
                        )
                    }

                    EditorSection(title = strings.scheduledTaskPrompt) {
                        EditorTextField(
                            value = prompt,
                            onValueChange = { prompt = it; error = null },
                            placeholder = strings.scheduledTaskPromptHint,
                            enabled = !saving,
                            singleLine = false,
                            minLines = 6,
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = editorCardColor,
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                strings.scheduledTaskEnabled,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = enabled,
                                enabled = !saving,
                                onCheckedChange = { enabled = it },
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        }
    }

    when (val picker = activePicker) {
        ScheduledTaskEditorPicker.CronTime -> EditorTimePickerDialog(
            title = strings.scheduledTaskSelectTime,
            initial = cronTime,
            onDismiss = { activePicker = null },
            onConfirm = { picked -> cronTime = picked; error = null },
        )

        ScheduledTaskEditorPicker.AnchorDate -> EditorDatePickerDialog(
            initial = anchorDateTime.date,
            onDismiss = { activePicker = null },
            onConfirm = { picked ->
                anchorDateTime = LocalDateTime(picked, anchorDateTime.time)
                error = null
            },
        )

        ScheduledTaskEditorPicker.AnchorTime -> EditorTimePickerDialog(
            title = strings.scheduledTaskSelectTime,
            initial = anchorDateTime.time,
            onDismiss = { activePicker = null },
            onConfirm = { picked ->
                anchorDateTime = LocalDateTime(anchorDateTime.date, picked)
                error = null
            },
        )

        ScheduledTaskEditorPicker.RunAtDate -> EditorDatePickerDialog(
            initial = runAtDateTime.date,
            onDismiss = { activePicker = null },
            onConfirm = { picked ->
                runAtDateTime = LocalDateTime(picked, runAtDateTime.time)
                error = null
            },
        )

        ScheduledTaskEditorPicker.RunAtTime -> EditorTimePickerDialog(
            title = strings.scheduledTaskSelectTime,
            initial = runAtDateTime.time,
            onDismiss = { activePicker = null },
            onConfirm = { picked ->
                runAtDateTime = LocalDateTime(runAtDateTime.date, picked)
                error = null
            },
        )

        null -> Unit
    }
}

@Composable
private fun EditorSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = editorCardColor,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

/** Light theme uses white cards on a dim page; dark theme lifts cards instead. */
private val editorCardColor: Color
    @Composable get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }

@Composable
private fun EditorDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(start = 16.dp),
    )
}

@Composable
private fun EditorCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun EditorHint(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun EditorErrorCard(message: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun EditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = if (label != null) (@Composable { Text(label) }) else null,
        placeholder = if (placeholder != null) (@Composable { Text(placeholder) }) else null,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun EditorValueRow(
    label: String?,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    showChevron: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            loading -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            showChevron -> Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun EditorDropdownRow(
    label: String?,
    value: String,
    items: List<AppMenuItem>,
    onItemClick: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    AppDropdownMenu(
        items = items,
        onItemClick = onItemClick,
        enabled = enabled,
        modifier = modifier,
        accessibilityLabel = label,
    ) { openMenu ->
        EditorValueRow(
            label = label,
            value = value,
            onClick = openMenu,
            enabled = enabled,
            loading = loading,
        )
    }
}

@Composable
private fun ColumnScope.ScheduledTaskDateTimeRows(
    dateTime: LocalDateTime,
    timeZone: TimeZone,
    enabled: Boolean,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
) {
    EditorValueRow(
        label = S.scheduledTaskDateLabel,
        value = formatEditorDate(dateTime.date, S),
        onClick = onPickDate,
        enabled = enabled,
        showChevron = false,
    )
    EditorDivider()
    EditorValueRow(
        label = S.scheduledTaskTimeLabel,
        value = formatEditorTime(dateTime.time),
        onClick = onPickTime,
        enabled = enabled,
        showChevron = false,
    )
    EditorHint("${S.scheduledTaskTimezone}: ${timeZone.id}")
}

@Composable
private fun ScheduledTaskWeekdaySelector(
    selected: Set<Int>,
    enabled: Boolean,
    onToggle: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        (1..7).forEach { weekday ->
            val isSelected = weekday in selected
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    )
                    .clickable(enabled = enabled) { onToggle(weekday) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    S.scheduledTaskWeekdayLabels[weekday - 1],
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private data class ScheduledTaskKindOption(
    val kind: String,
    val title: String,
    val description: String,
)

@Composable
private fun ScheduledTaskKindSelector(
    value: String,
    supportsHTTPTriggers: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val strings = S
    val kinds = buildList {
        add(
            ScheduledTaskKindOption(
                kind = ScheduledTaskKinds.Cron,
                title = strings.scheduledTaskCron,
                description = strings.scheduledTaskCronDesc,
            ),
        )
        add(
            ScheduledTaskKindOption(
                kind = ScheduledTaskKinds.Interval,
                title = strings.scheduledTaskInterval,
                description = strings.scheduledTaskIntervalDesc,
            ),
        )
        add(
            ScheduledTaskKindOption(
                kind = ScheduledTaskKinds.Once,
                title = strings.scheduledTaskOnce,
                description = strings.scheduledTaskOnceDesc,
            ),
        )
        if (supportsHTTPTriggers || value == ScheduledTaskKinds.HTTP) {
            add(
                ScheduledTaskKindOption(
                    kind = ScheduledTaskKinds.HTTP,
                    title = strings.scheduledTaskHTTP,
                    description = strings.scheduledTaskHTTPDesc,
                ),
            )
        }
    }
    val selected = kinds.firstOrNull { it.kind == value } ?: kinds.first()
    EditorDropdownRow(
        label = null,
        value = selected.title,
        items = kinds.map { option ->
            AppMenuItem(
                id = option.kind,
                title = option.title,
                subtitle = option.description,
                checkable = true,
                selected = option.kind == value,
            )
        },
        onItemClick = onSelect,
        enabled = enabled,
    )
}

@Composable
private fun ScheduledTaskWorkspaceSelector(
    vm: AppViewModel,
    workspaceId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val workspaces = vm.workspaces
    val selected = workspaces.firstOrNull { it.id == workspaceId }
    EditorDropdownRow(
        label = S.scheduledTaskWorkspace,
        value = selected?.displayName ?: workspaceId.ifBlank { "—" },
        items = workspaces.map { workspace ->
            AppMenuItem(
                id = workspace.id,
                title = workspace.displayName,
                subtitle = workspace.directory,
                checkable = true,
                selected = workspace.id == workspaceId,
            )
        },
        onItemClick = onSelect,
        enabled = enabled && workspaces.isNotEmpty(),
    )
}

@Composable
private fun ScheduledTaskIntervalUnitSelector(
    value: ScheduledTaskIntervalUnit,
    enabled: Boolean,
    onSelect: (ScheduledTaskIntervalUnit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = S
    fun label(unit: ScheduledTaskIntervalUnit): String = when (unit) {
        ScheduledTaskIntervalUnit.Minutes -> strings.scheduledTaskMinutes
        ScheduledTaskIntervalUnit.Hours -> strings.scheduledTaskHours
        ScheduledTaskIntervalUnit.Days -> strings.scheduledTaskDays
    }
    EditorDropdownRow(
        label = strings.scheduledTaskIntervalUnit,
        value = label(value),
        items = ScheduledTaskIntervalUnit.entries.map { unit ->
            AppMenuItem(
                id = unit.name,
                title = label(unit),
                checkable = true,
                selected = unit == value,
            )
        },
        onItemClick = { id -> ScheduledTaskIntervalUnit.entries.firstOrNull { it.name == id }?.let(onSelect) },
        enabled = enabled,
        modifier = modifier,
    )
}

@Composable
private fun ColumnScope.ScheduledTaskModelSelector(
    capabilities: GatewayCapabilities?,
    model: String,
    thinking: String,
    loading: Boolean,
    error: String?,
    enabled: Boolean,
    onModel: (String) -> Unit,
    onThinking: (String) -> Unit,
) {
    val models = capabilities?.models.orEmpty()
    val selected = models.firstOrNull { qualifiedModel(it.provider, it.id) == model }
    val modelLabel = when {
        model.isBlank() -> S.scheduledTaskDefaultModel
        selected != null -> selected.name.ifBlank { selected.id } + " · " + selected.provider
        else -> model
    }
    val modelItems = buildList {
        add(AppMenuItem(id = "", title = S.scheduledTaskDefaultModel, checkable = true, selected = model.isBlank()))
        models.forEach { option ->
            val qualified = qualifiedModel(option.provider, option.id)
            add(
                AppMenuItem(
                    id = qualified,
                    title = option.name.ifBlank { option.id },
                    subtitle = option.provider,
                    checkable = true,
                    selected = model == qualified,
                ),
            )
        }
        if (model.isNotBlank() && none { it.id == model }) {
            add(AppMenuItem(id = model, title = model, checkable = true, selected = true))
        }
    }

    EditorDropdownRow(
        label = null,
        value = modelLabel,
        items = modelItems,
        onItemClick = onModel,
        enabled = enabled && !loading,
        loading = loading,
    )
    error?.let { EditorHint(it, isError = true) }

    if (model.isNotBlank()) {
        val levels = selected?.thinkingLevels.orEmpty()
        val thinkingItems = buildList {
            add(
                AppMenuItem(
                    id = "",
                    title = S.scheduledTaskDefaultThinking,
                    checkable = true,
                    selected = thinking.isBlank(),
                ),
            )
            levels.forEach { level ->
                add(AppMenuItem(id = level, title = level, checkable = true, selected = thinking == level))
            }
            if (thinking.isNotBlank() && none { it.id == thinking }) {
                add(AppMenuItem(id = thinking, title = thinking, checkable = true, selected = true))
            }
        }
        EditorDivider()
        EditorDropdownRow(
            label = S.thinkingLevel,
            value = thinking.ifBlank { S.scheduledTaskDefaultThinking },
            items = thinkingItems,
            onItemClick = onThinking,
            enabled = enabled,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorDatePickerDialog(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis
                        ?.let { millis -> Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date }
                        ?.let(onConfirm)
                    onDismiss()
                },
            ) { Text(S.confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.cancel) } },
    ) {
        DatePicker(state = pickerState, showModeToggle = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTimePickerDialog(
    title: String,
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(LocalTime(pickerState.hour, pickerState.minute))
                    onDismiss()
                },
            ) { Text(S.confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.cancel) } },
        title = { Text(title) },
        text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(state = pickerState)
            }
        },
    )
}

private fun qualifiedModel(provider: String, id: String): String =
    if (provider.isBlank()) id else "$provider/$id"

private fun formatEditorTime(time: LocalTime): String =
    "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"

private fun formatEditorDate(date: LocalDate, strings: Strings): String {
    val month = date.month.number.toString().padStart(2, '0')
    val day = date.day.toString().padStart(2, '0')
    val weekday = strings.scheduledTaskWeekdayLabels.getOrNull(date.dayOfWeek.isoDayNumber - 1).orEmpty()
    return "${date.year}-$month-$day · $weekday"
}

private fun rfc3339ToLocalDateTime(value: String, timeZone: TimeZone): LocalDateTime? =
    runCatching { Instant.parse(value.trim()).toLocalDateTime(timeZone) }.getOrNull()

private fun localDateTimeToRfc3339(dateTime: LocalDateTime, timeZone: TimeZone): String {
    fun pad(value: Int, length: Int = 2): String = value.toString().padStart(length, '0')
    val offsetSeconds = timeZone.offsetAt(dateTime.toInstant(timeZone)).totalSeconds
    val offset = if (offsetSeconds == 0) {
        "Z"
    } else {
        val sign = if (offsetSeconds < 0) "-" else "+"
        val absolute = abs(offsetSeconds)
        "$sign${pad(absolute / 3600)}:${pad((absolute % 3600) / 60)}"
    }
    return "${pad(dateTime.year, 4)}-${pad(dateTime.month.number)}-${pad(dateTime.day)}" +
        "T${pad(dateTime.hour)}:${pad(dateTime.minute)}:${pad(dateTime.second)}$offset"
}

private fun intervalDisplayValue(seconds: Long): Pair<String, ScheduledTaskIntervalUnit> = when {
    seconds > 0 && seconds % ScheduledTaskIntervalUnit.Days.seconds == 0L ->
        (seconds / ScheduledTaskIntervalUnit.Days.seconds).toString() to ScheduledTaskIntervalUnit.Days
    seconds > 0 && seconds % ScheduledTaskIntervalUnit.Hours.seconds == 0L ->
        (seconds / ScheduledTaskIntervalUnit.Hours.seconds).toString() to ScheduledTaskIntervalUnit.Hours
    else -> (seconds / ScheduledTaskIntervalUnit.Minutes.seconds).coerceAtLeast(1).toString() to
        ScheduledTaskIntervalUnit.Minutes
}

internal fun parseScheduledTaskSkillPaths(value: String): List<String> =
    value.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()

private fun validateScheduledTaskEditor(
    name: String,
    workspaceId: String,
    prompt: String,
    kind: String,
    cronWeekdays: Set<Int>,
    cronTime: LocalTime,
    cronTimezone: String,
    intervalValue: String,
    intervalUnit: ScheduledTaskIntervalUnit,
    anchorDateTime: LocalDateTime,
    runAtDateTime: LocalDateTime,
    timeZone: TimeZone,
    strings: Strings,
): Result<GatewayTaskSchedule> = runCatching {
    require(name.isNotBlank() && workspaceId.isNotBlank() && prompt.isNotBlank()) {
        strings.scheduledTaskRequiredFields
    }
    when (kind) {
        ScheduledTaskKinds.Cron -> {
            require(
                cronWeekdays.isNotEmpty() &&
                    cronWeekdays.all { it in 1..7 } &&
                    cronTimezone.isNotBlank(),
            ) {
                strings.scheduledTaskInvalidCron
            }
            GatewayTaskSchedule(
                kind = ScheduledTaskKinds.Cron,
                expression = scheduledTaskWeeklyScheduleToCron(
                    ScheduledTaskWeeklySchedule(
                        weekdays = cronWeekdays,
                        hour = cronTime.hour,
                        minute = cronTime.minute,
                    ),
                ),
                timezone = cronTimezone.trim(),
            )
        }

        ScheduledTaskKinds.Interval -> {
            val count = intervalValue.toLongOrNull()
            require(count != null && count > 0 && count <= Long.MAX_VALUE / intervalUnit.seconds) {
                strings.scheduledTaskInvalidInterval
            }
            GatewayTaskSchedule(
                kind = ScheduledTaskKinds.Interval,
                everySeconds = count * intervalUnit.seconds,
                anchorAt = localDateTimeToRfc3339(anchorDateTime, timeZone),
            )
        }

        ScheduledTaskKinds.Once -> {
            val instant = runAtDateTime.toInstant(timeZone)
            require(instant > Clock.System.now()) { strings.scheduledTaskInvalidTime }
            GatewayTaskSchedule(
                kind = ScheduledTaskKinds.Once,
                at = localDateTimeToRfc3339(runAtDateTime, timeZone),
            )
        }

        ScheduledTaskKinds.HTTP -> GatewayTaskSchedule(kind = ScheduledTaskKinds.HTTP)

        else -> error("unsupported schedule kind")
    }
}
