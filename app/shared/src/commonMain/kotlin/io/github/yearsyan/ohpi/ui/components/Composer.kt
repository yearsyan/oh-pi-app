package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import io.github.yearsyan.ohpi.chat.ChatController
import io.github.yearsyan.ohpi.chat.PromptImage
import io.github.yearsyan.ohpi.chat.QueuedPromptItem
import io.github.yearsyan.ohpi.chat.QueuedPromptKind
import io.github.yearsyan.ohpi.chat.QueuedPromptState
import io.github.yearsyan.ohpi.chat.SlashCommand
import io.github.yearsyan.ohpi.chat.SlashCommandSource
import io.github.yearsyan.ohpi.chat.matchingSlashCommands
import io.github.yearsyan.ohpi.chat.queuedPromptItems
import io.github.yearsyan.ohpi.i18n.S
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Rounded message composer with model controls, image attachment and send/stop action. */
@Composable
fun Composer(
    controller: ChatController,
    backdropState: HazeState? = null,
    modifier: Modifier = Modifier,
    onPromptSent: () -> Unit = {},
) {
    val controllerText = controller.composerText
    val editorState =
        remember(controller) {
            TextFieldState(
                initialText = controllerText,
                initialSelection = TextRange(controllerText.length),
            )
        }
    LaunchedEffect(controller, editorState) {
        snapshotFlow { editorState.text.toString() }.collect(controller::updateComposerText)
    }
    LaunchedEffect(controllerText, editorState) {
        if (editorState.text.toString() != controllerText) {
            editorState.setTextAndPlaceCursorAtEnd(controllerText)
        }
    }
    val text = editorState.text.toString()
    val images = controller.composerImages
    var pickerError by remember { mutableStateOf<String?>(null) }
    var observedConfirmation by
        remember(controller) { mutableStateOf(controller.lastConfirmedPromptSourceId) }
    val focusRequester = remember { FocusRequester() }
    val promptPending = controller.isPromptPending
    LaunchedEffect(controller.lastConfirmedPromptSourceId) {
        val confirmed = controller.lastConfirmedPromptSourceId
        if (confirmed != null && confirmed != observedConfirmation) {
            pickerError = null
            onPromptSent()
        }
        observedConfirmation = confirmed
    }
    val imageTooLarge = S.imageTooLarge
    val imageReadFailed = S.imageReadFailed
    val onImageResult: (ImagePickResult) -> Unit = imageResult@{ result ->
        if (controller.isPromptPending) return@imageResult
        when (result) {
            is ImagePickResult.Success -> {
                controller.updateComposerImages(
                    (controller.composerImages + result.images).take(MaxPickedImageCount),
                )
                pickerError = null
            }

            ImagePickResult.TooLarge -> pickerError = imageTooLarge
            ImagePickResult.Failed -> pickerError = imageReadFailed
        }
    }
    val picker = rememberImagePicker(onImageResult)
    val clipboardImagePasteHandler =
        rememberClipboardImagePasteHandler(
            enabled = !promptPending,
            onResult = onImageResult,
        )
    val hasPrompt = text.isNotBlank() || images.isNotEmpty()
    val canSend = controller.canSubmitInput(text, images.isNotEmpty())
    val showStop = controller.isStreaming && !hasPrompt
    val queuedPrompts =
        queuedPromptItems(
            pending = controller.pendingSubmission,
            steering = controller.steeringQueue,
            followUp = controller.followUpQueue,
        )
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
    val transcriptScrimColor = MaterialTheme.colorScheme.background
    val transcriptScrim =
        remember(transcriptScrimColor) {
            Brush.verticalGradient(
                colorStops = composerTranscriptScrimStops(transcriptScrimColor),
            )
        }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .imePadding(),
    ) {
        // Let the transcript travel beneath 75% of the composer. It stays fully
        // visible through the first two thirds of that underlap, then fades into
        // the page background over the final third so there is no hard cutoff.
        Box(
            Modifier
                .matchParentSize()
                .background(transcriptScrim),
        )
        PlatformComposerSurface(
            backdropState = backdropState,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (slashMatches.isNotEmpty()) {
                    SlashCommandMenu(
                        commands = slashMatches,
                        onSelect = { command ->
                            controller.updateComposerText("/${command.name} ")
                            focusRequester.requestFocus()
                        },
                    )
                }
                if (queuedPrompts.isNotEmpty()) {
                    QueuedPromptPanel(queuedPrompts)
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
                        state = editorState,
                        enabled = !promptPending,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent(clipboardImagePasteHandler),
                        textStyle =
                            MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
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
                                    controller.updateComposerImages(images.filterIndexed { i, _ -> i != index })
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
                                        controller.submitInput(text, images)
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

private const val ComposerTranscriptUnderlapFraction = 0.75f
private const val ComposerTranscriptFadeStartFraction =
    ComposerTranscriptUnderlapFraction * (2f / 3f)

/** Vertical scrim stops keeping the transcript visible, then fading it away. */
internal fun composerTranscriptScrimStops(background: Color): Array<Pair<Float, Color>> =
    arrayOf(
        0f to Color.Transparent,
        ComposerTranscriptFadeStartFraction to Color.Transparent,
        ComposerTranscriptUnderlapFraction to background,
        1f to background,
    )

@Composable
private fun QueuedPromptPanel(items: List<QueuedPromptItem>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items.take(3).forEach { item -> QueuedPromptRow(item) }
            if (items.size > 3) {
                Text(
                    "+${items.size - 3}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun QueuedPromptRow(item: QueuedPromptItem) {
    val status =
        when (item.state) {
            QueuedPromptState.Submitting -> S.queueSubmitting
            QueuedPromptState.AwaitingConsumption -> {
                val prefix =
                    when (item.kind) {
                        QueuedPromptKind.Submission -> S.queueSubmitted
                        QueuedPromptKind.Steering -> S.queueSteer
                        QueuedPromptKind.FollowUp -> S.queueFollowUp
                    }
                "$prefix · ${S.queueAwaitingConsumption}"
            }
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    if (item.state == QueuedPromptState.Submitting) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            // While submitting, the draft is still visible in the input field
            // below, so repeating the text here would render it twice on screen.
            if (item.state == QueuedPromptState.AwaitingConsumption) {
                if (item.text.isNotBlank()) {
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (item.imageCount > 0) {
                    Text(
                        S.imageAttachment(item.imageCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
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
    val decoded by rememberDecodedPromptImage(image)
    var previewOpen by remember { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(enabled = enabled) { previewOpen = true }
                .padding(start = 4.dp, end = 2.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = decoded) {
                is ImagePreviewState.Ready ->
                    Image(
                        bitmap = state.bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                else ->
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
            }
        }
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
    if (previewOpen) {
        ImagePreviewDialog(
            state = decoded,
            onDismiss = { previewOpen = false },
            title = image.name.ifBlank { null },
        )
    }
}

@Composable
private fun rememberDecodedPromptImage(image: PromptImage) =
    produceState<ImagePreviewState>(ImagePreviewState.Loading, image.data) {
        value =
            withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = Base64.decode(image.data)
                    ImagePreviewState.Ready(bytes.decodeToImageBitmap(), bytes)
                }.getOrElse { ImagePreviewState.Failed() }
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
