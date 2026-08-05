package io.github.yearsyan.pi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yearsyan.pi.chat.TimelineImage
import io.github.yearsyan.pi.i18n.S
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

internal const val UserImageThumbnailTag = "user-image-thumbnail"
internal const val UserImagePreviewTag = "user-image-preview"

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
        ImagePreviewDialog(
            state = rememberDecodedTimelineImage(image).value,
            onDismiss = { preview = null },
            testTag = UserImagePreviewTag,
        )
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
            singleImageSize((decoded as? ImagePreviewState.Ready)?.bitmap)
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
            ImagePreviewState.Loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            is ImagePreviewState.Failed ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = S.imageReadFailed,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            is ImagePreviewState.Ready ->
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
private fun rememberDecodedTimelineImage(image: TimelineImage) =
    produceState<ImagePreviewState>(ImagePreviewState.Loading, image.data) {
        value =
            withContext(Dispatchers.Default) {
                runCatching {
                    ImagePreviewState.Ready(Base64.decode(image.data).decodeToImageBitmap())
                }.getOrElse { ImagePreviewState.Failed() }
            }
    }
