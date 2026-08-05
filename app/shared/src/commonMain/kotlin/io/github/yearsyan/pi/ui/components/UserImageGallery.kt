package io.github.yearsyan.pi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.yearsyan.pi.chat.TimelineImage
import io.github.yearsyan.pi.i18n.S
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

internal const val UserImageThumbnailTag = "user-image-thumbnail"
internal const val UserImagePreviewTag = "user-image-preview"

private sealed interface DecodedTimelineImage {
    data object Loading : DecodedTimelineImage

    data object Failed : DecodedTimelineImage

    data class Ready(val bitmap: ImageBitmap) : DecodedTimelineImage
}

/** Right-aligned image attachments rendered separately from the user text bubble. */
@Composable
internal fun UserImageGallery(images: List<TimelineImage>) {
    if (images.isEmpty()) return
    var preview by remember { mutableStateOf<TimelineImage?>(null) }
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (images.size == 1) {
            UserImageThumbnail(
                image = images.single(),
                square = false,
                onClick = { preview = images.single() },
            )
        } else {
            images.chunked(2).forEach { rowImages ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    rowImages.forEach { image ->
                        UserImageThumbnail(
                            image = image,
                            square = true,
                            onClick = { preview = image },
                        )
                    }
                }
            }
        }
    }
    preview?.let { image ->
        UserImagePreview(image = image, onDismiss = { preview = null })
    }
}

@Composable
private fun UserImageThumbnail(
    image: TimelineImage,
    square: Boolean,
    onClick: () -> Unit,
) {
    val decoded by rememberDecodedTimelineImage(image)
    val size =
        if (square) {
            136.dp to 136.dp
        } else {
            singleImageSize((decoded as? DecodedTimelineImage.Ready)?.bitmap)
        }
    Surface(
        onClick = onClick,
        modifier =
            Modifier
                .size(width = size.first, height = size.second)
                .testTag(UserImageThumbnailTag),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        when (val state = decoded) {
            DecodedTimelineImage.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            DecodedTimelineImage.Failed ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = S.imageReadFailed,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            is DecodedTimelineImage.Ready ->
                Image(
                    bitmap = state.bitmap,
                    contentDescription = S.imageAttachment(1),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (square) ContentScale.Crop else ContentScale.Fit,
                )
        }
    }
}

private fun singleImageSize(bitmap: ImageBitmap?): Pair<Dp, Dp> {
    if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return 220.dp to 180.dp
    val ratio = (bitmap.width.toFloat() / bitmap.height).coerceIn(0.62f, 2f)
    val width = if (ratio < 0.8f) 220.dp else 280.dp
    val height = (width.value / ratio).coerceIn(140f, 320f).dp
    return width to height
}

@Composable
private fun UserImagePreview(
    image: TimelineImage,
    onDismiss: () -> Unit,
) {
    val decoded by rememberDecodedTimelineImage(image)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f))
                    .testTag(UserImagePreviewTag),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = decoded) {
                DecodedTimelineImage.Loading ->
                    CircularProgressIndicator(color = Color.White)
                DecodedTimelineImage.Failed ->
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = S.imageReadFailed,
                        modifier = Modifier.size(48.dp),
                        tint = Color.White.copy(alpha = 0.7f),
                    )
                is DecodedTimelineImage.Ready ->
                    Image(
                        bitmap = state.bitmap,
                        contentDescription = S.imageAttachment(1),
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentScale = ContentScale.Fit,
                    )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = S.cancel,
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun rememberDecodedTimelineImage(image: TimelineImage) =
    produceState<DecodedTimelineImage>(DecodedTimelineImage.Loading, image.data) {
        value =
            withContext(Dispatchers.Default) {
                runCatching {
                    DecodedTimelineImage.Ready(Base64.decode(image.data).decodeToImageBitmap())
                }.getOrElse { DecodedTimelineImage.Failed }
            }
    }
