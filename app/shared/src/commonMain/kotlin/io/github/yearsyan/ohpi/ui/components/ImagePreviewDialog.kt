package io.github.yearsyan.ohpi.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import io.github.yearsyan.ohpi.theme.rememberCodeFontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.i18n.S
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Load/decode state consumed by [ImagePreviewDialog]. */
internal sealed interface ImagePreviewState {
    data object Loading : ImagePreviewState

    data class Failed(val message: String? = null) : ImagePreviewState

    /**
     * [sourceBytes] are the undecoded payload, when the caller has them, so the
     * viewer can save the image losslessly to the system photo album.
     */
    data class Ready(val bitmap: ImageBitmap, val sourceBytes: ByteArray? = null) : ImagePreviewState
}

private const val MinZoom = 1f
private const val MaxZoom = 5f
private const val DoubleTapZoom = 2.5f
private val ZoomInFloat = tween<Float>(durationMillis = 250)
private val ZoomInOffset = tween<Offset>(durationMillis = 250)
private val ZoomResetFloat = spring<Float>(stiffness = Spring.StiffnessMediumLow)
private val ZoomResetOffset = spring<Offset>(stiffness = Spring.StiffnessMediumLow)
private val Backdrop = Color.Black.copy(alpha = 0.94f)
private const val WheelZoomInFactor = 1.2f
private const val WheelZoomOutFactor = 1f / WheelZoomInFactor

/**
 * Shared full-screen image viewer, hosted by the platform [ImagePreviewContainer]:
 * a full-screen dialog on Android/iOS, a separate OS window on desktop.
 * Gestures: pinch to zoom and pan, mouse wheel to zoom around the pointer,
 * double-tap to toggle between 1x and [DoubleTapZoom], single tap to dismiss
 * (dialog containers only). Long-press opens a bottom sheet with a
 * save-to-album action when the undecoded source bytes are available.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImagePreviewDialog(
    state: ImagePreviewState,
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    testTag: String? = null,
) {
    ImagePreviewContainer(onDismiss = onDismiss, title = title) { dismissOnTap ->
        val scope = rememberCoroutineScope()
        var scale by remember { mutableFloatStateOf(MinZoom) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var viewSize by remember { mutableStateOf(IntSize.Zero) }
        var animJob by remember { mutableStateOf<Job?>(null) }
        val zoomable = state is ImagePreviewState.Ready
        val savableBytes = (state as? ImagePreviewState.Ready)?.sourceBytes
        var saveSheetOpen by remember { mutableStateOf(false) }
        var saveFeedback by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }
        val strings = S

        fun startSave() {
            val bytes = savableBytes ?: return
            if (saving) return
            saving = true
            scope.launch {
                val mime = imageMimeOf(bytes)
                val name =
                    "ohpi-${Clock.System.now().toEpochMilliseconds()}.${imageExtensionOf(mime)}"
                saveFeedback =
                    when (saveImageToAlbum(bytes, name, mime)) {
                        AlbumSaveResult.Success -> strings.imageSavedToAlbum
                        AlbumSaveResult.Cancelled -> null
                        is AlbumSaveResult.Failure -> strings.imageSaveFailed
                    }
                saving = false
            }
        }

        LaunchedEffect(saveFeedback) {
            if (saveFeedback != null) {
                delay(2600)
                saveFeedback = null
            }
        }

        fun center() = Offset(viewSize.width / 2f, viewSize.height / 2f)

        fun clampOffset(scaleValue: Float, value: Offset): Offset {
            if (scaleValue <= MinZoom) return Offset.Zero
            val maxX = (viewSize.width * (scaleValue - MinZoom) / 2f).coerceAtLeast(0f)
            val maxY = (viewSize.height * (scaleValue - MinZoom) / 2f).coerceAtLeast(0f)
            return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
        }

        val transformState = rememberTransformableState { centroid, zoomChange, panChange, _ ->
            if (zoomable) {
                // A running double-tap animation loses to the gesture.
                animJob?.cancel()
                animJob = null
                val newScale = (scale * zoomChange).coerceIn(MinZoom, MaxZoom)
                // Zoom around the gesture centroid, then apply the pan.
                val d = centroid - center()
                val newOffset = (offset - d) * (newScale / scale) + d + panChange
                scale = newScale
                offset = clampOffset(newScale, newOffset)
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Backdrop)
                    .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                    .onSizeChanged { viewSize = it }
                    .pointerInput(onDismiss, zoomable, dismissOnTap) {
                        detectTapGestures(
                            onTap = if (dismissOnTap) ({ onDismiss() }) else null,
                            onLongPress =
                                if (savableBytes != null) {
                                    { saveSheetOpen = true }
                                } else {
                                    null
                                },
                            onDoubleTap =
                                if (zoomable) {
                                    { tap ->
                                        val startScale = scale
                                        val startOffset = offset
                                        animJob?.cancel()
                                        animJob =
                                            scope.launch {
                                                if (startScale > MinZoom + 0.01f) {
                                                    launch {
                                                        animate(
                                                            startScale,
                                                            MinZoom,
                                                            animationSpec = ZoomResetFloat,
                                                        ) { value, _ ->
                                                            scale = value
                                                        }
                                                    }
                                                    launch {
                                                        animate(
                                                            Offset.VectorConverter,
                                                            startOffset,
                                                            Offset.Zero,
                                                            animationSpec = ZoomResetOffset,
                                                        ) { value, _ ->
                                                            offset = value
                                                        }
                                                    }
                                                } else {
                                                    // Keep the tapped content point under the
                                                    // finger while zooming in.
                                                    val d = tap - center()
                                                    val factor = DoubleTapZoom / startScale
                                                    val targetOffset =
                                                        clampOffset(
                                                            DoubleTapZoom,
                                                            (startOffset - d) * factor + d,
                                                        )
                                                    launch {
                                                        animate(
                                                            startScale,
                                                            DoubleTapZoom,
                                                            animationSpec = ZoomInFloat,
                                                        ) { value, _ ->
                                                            scale = value
                                                        }
                                                    }
                                                    launch {
                                                        animate(
                                                            Offset.VectorConverter,
                                                            startOffset,
                                                            targetOffset,
                                                            animationSpec = ZoomInOffset,
                                                        ) { value, _ ->
                                                            offset = value
                                                        }
                                                    }
                                                }
                                            }
                                    }
                                } else {
                                    null
                                },
                        )
                    }
                    .transformable(transformState)
                    .pointerInput(zoomable) {
                        // Mouse-wheel/trackpad scroll zooms around the pointer,
                        // mirroring the pinch math; touch platforms simply never
                        // deliver scroll events here.
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (!zoomable || event.type != PointerEventType.Scroll) continue
                                val change = event.changes.firstOrNull() ?: continue
                                val deltaY = change.scrollDelta.y
                                if (deltaY == 0f) continue
                                animJob?.cancel()
                                animJob = null
                                val factor = if (deltaY < 0f) WheelZoomInFactor else WheelZoomOutFactor
                                val newScale = (scale * factor).coerceIn(MinZoom, MaxZoom)
                                if (newScale == scale) continue
                                // Keep the content point under the pointer stationary.
                                val d = change.position - center()
                                offset = clampOffset(newScale, (offset - d) * (newScale / scale) + d)
                                scale = newScale
                                change.consume()
                            }
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                ImagePreviewState.Loading -> CircularProgressIndicator(color = Color.White)

                is ImagePreviewState.Failed ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Image,
                            contentDescription = S.imageReadFailed,
                            modifier = Modifier.size(48.dp),
                            tint = Color.White.copy(alpha = 0.7f),
                        )
                        state.message?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }

                is ImagePreviewState.Ready ->
                    Image(
                        bitmap = state.bitmap,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier.fillMaxSize().graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            ),
                    )
            }
            saveFeedback?.let { feedback ->
                Surface(
                    color = Color(0xCC2A2A35),
                    shape = RoundedCornerShape(20.dp),
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .safeDrawingPadding()
                            .padding(bottom = 56.dp),
                ) {
                    Text(
                        feedback,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (title != null) {
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .safeDrawingPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(
                        title,
                        style =
                            MaterialTheme.typography.titleSmall.copy(
                                fontFamily = rememberCodeFontFamily()
                            ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (saveSheetOpen && savableBytes != null) {
            ModalBottomSheet(onDismissRequest = { saveSheetOpen = false }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                saveSheetOpen = false
                                startSave()
                            }
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(strings.saveToAlbum, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/**
 * Platform container hosting the image viewer: a full-screen dialog on
 * Android/iOS, a separate OS window on desktop. [content] receives whether a
 * single tap on the backdrop should dismiss the viewer — true for dialogs;
 * false for the desktop window, which is closed through its own window
 * controls (close button, Escape).
 */
@Composable
internal expect fun ImagePreviewContainer(
    onDismiss: () -> Unit,
    title: String?,
    content: @Composable (dismissOnTap: Boolean) -> Unit,
)
