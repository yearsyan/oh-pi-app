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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yearsyan.pi.chat.ChatController
import io.github.yearsyan.pi.chat.PromptImage
import io.github.yearsyan.pi.i18n.S

/** Rounded message composer with model controls, image attachment and send/stop action. */
@Composable
fun Composer(
    controller: ChatController,
    modifier: Modifier = Modifier,
    onPromptSent: () -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<PromptImage?>(null) }
    var pickerError by remember { mutableStateOf<String?>(null) }
    val imageTooLarge = S.imageTooLarge
    val imageReadFailed = S.imageReadFailed
    val picker =
        rememberImagePicker { result ->
            when (result) {
                is ImagePickResult.Success -> {
                    image = result.image
                    pickerError = null
                }
                ImagePickResult.TooLarge -> pickerError = imageTooLarge
                ImagePickResult.Failed -> pickerError = imageReadFailed
            }
        }
    val hasPrompt = text.isNotBlank() || image != null
    val canSend = controller.canSendPrompt && hasPrompt
    val showStop = controller.isStreaming && !hasPrompt

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
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
            shadowElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 144.dp)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(
                            if (controller.isStreaming) S.messagePlaceholderStreaming else S.messagePlaceholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle =
                            MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 6,
                    )
                }

                image?.let { selected ->
                    SelectedImageChip(
                        image = selected,
                        onRemove = {
                            image = null
                            pickerError = null
                        },
                    )
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
                        if (controller.models.isNotEmpty()) ModelSelector(controller)
                        if (controller.thinkingLevels.isNotEmpty()) ThinkingSelector(controller)
                    }
                    Spacer(Modifier.width(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        ComposerIconButton(
                            onClick = { picker.launch() },
                            enabled = picker.available,
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
                                        val sent = controller.sendPrompt(text, listOfNotNull(image))
                                        if (sent) {
                                            text = ""
                                            image = null
                                            pickerError = null
                                            onPromptSent()
                                        }
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
private fun SelectedImageChip(
    image: PromptImage,
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
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
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
