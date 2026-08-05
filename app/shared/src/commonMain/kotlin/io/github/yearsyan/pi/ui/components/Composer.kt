package io.github.yearsyan.pi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yearsyan.pi.chat.ChatController
import io.github.yearsyan.pi.chat.PromptImage
import io.github.yearsyan.pi.chat.SlashCommand
import io.github.yearsyan.pi.chat.SlashCommandSource
import io.github.yearsyan.pi.chat.matchingSlashCommands
import io.github.yearsyan.pi.i18n.S

/** Rounded message composer with model controls, image attachment and send/stop action. */
@Composable
fun Composer(
    controller: ChatController,
    modifier: Modifier = Modifier,
    onPromptSent: () -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    var images by remember { mutableStateOf<List<PromptImage>>(emptyList()) }
    var pickerError by remember { mutableStateOf<String?>(null) }
    var submittedSourceId by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    val promptPending = controller.isPromptPending
    LaunchedEffect(controller.lastConfirmedPromptSourceId) {
        val confirmed = controller.lastConfirmedPromptSourceId
        if (confirmed != null && confirmed == submittedSourceId) {
            text = ""
            images = emptyList()
            pickerError = null
            submittedSourceId = null
            onPromptSent()
        }
    }
    val imageTooLarge = S.imageTooLarge
    val imageReadFailed = S.imageReadFailed
    val picker =
        rememberImagePicker { result ->
            if (controller.isPromptPending) return@rememberImagePicker
            when (result) {
                is ImagePickResult.Success -> {
                    images = (images + result.images).take(MaxPickedImageCount)
                    pickerError = null
                }
                ImagePickResult.TooLarge -> pickerError = imageTooLarge
                ImagePickResult.Failed -> pickerError = imageReadFailed
            }
        }
    val hasPrompt = text.isNotBlank() || images.isNotEmpty()
    val canSend = controller.canSubmitInput(text, images.isNotEmpty())
    val showStop = controller.isStreaming && !hasPrompt
    val slashMatches =
        matchingSlashCommands(
            text,
            listOf(
                SlashCommand(
                    name = "compact",
                    description = S.compactCommandDescription,
                    source = SlashCommandSource.BuiltIn,
                ),
            ) + controller.slashCommands,
        )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .imePadding(),
    ) {
        // Keep the rounded top edge floating over the transcript, while making
        // everything below the card opaque so messages never show underneath it.
        Box(
            Modifier
                .matchParentSize()
                .padding(top = 36.dp)
                .background(MaterialTheme.colorScheme.background),
        )
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (slashMatches.isNotEmpty()) {
                    SlashCommandMenu(
                        commands = slashMatches,
                        onSelect = { command ->
                            text = "/${command.name} "
                            focusRequester.requestFocus()
                        },
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 144.dp)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(
                            S.messagePlaceholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        enabled = !promptPending,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        textStyle =
                            MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 6,
                    )
                }

                if (images.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        images.forEachIndexed { index, selected ->
                            SelectedImageChip(
                                image = selected,
                                enabled = !promptPending,
                                onRemove = {
                                    images = images.filterIndexed { i, _ -> i != index }
                                    pickerError = null
                                },
                            )
                        }
                    }
                }
                pickerError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (controller.isDraft || controller.models.isNotEmpty()) ModelSelector(controller)
                        if (controller.isDraft || controller.thinkingLevels.isNotEmpty()) ThinkingSelector(controller)
                    }
                    Spacer(Modifier.width(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        ComposerIconButton(
                            onClick = { picker.launch() },
                            enabled = picker.available && !promptPending,
                            background = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = S.addImage,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        ComposerIconButton(
                            onClick = {
                                when {
                                    showStop -> controller.abort()
                                    canSend -> {
                                        submittedSourceId = controller.submitInput(text, images)
                                    }
                                }
                            },
                            enabled = showStop || canSend,
                            background =
                                when {
                                    showStop -> MaterialTheme.colorScheme.errorContainer
                                    canSend -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                        ) {
                            if (showStop) {
                                Icon(
                                    Icons.Filled.Stop,
                                    contentDescription = S.stop,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = S.send,
                                    tint =
                                        if (canSend) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlashCommandMenu(
    commands: List<SlashCommand>,
    onSelect: (SlashCommand) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 224.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
        ) {
            items(commands, key = { it.name }) { command ->
                Surface(
                    onClick = { onSelect(command) },
                    color = androidx.compose.ui.graphics.Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "/${command.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (command.description.isNotBlank()) {
                                Text(
                                    text = command.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = commandSourceLabel(command.source),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun commandSourceLabel(source: SlashCommandSource): String =
    when (source) {
        SlashCommandSource.BuiltIn -> S.commandSourceBuiltIn
        SlashCommandSource.Extension -> S.commandSourceExtension
        SlashCommandSource.Prompt -> S.commandSourcePrompt
        SlashCommandSource.Skill -> S.commandSourceSkill
        SlashCommandSource.Other -> S.commandSourceCommand
    }

@Composable
private fun SelectedImageChip(
    image: PromptImage,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(start = 10.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Image,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            image.name.ifBlank { S.imageAttachment(1) },
            modifier = Modifier.widthIn(max = 180.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = S.removeImage,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun ComposerIconButton(
    onClick: () -> Unit,
    enabled: Boolean,
    background: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(CircleShape).background(background),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}
