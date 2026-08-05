package io.github.yearsyan.ohpi.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.yearsyan.ohpi.i18n.S
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Load/decode state consumed by [ImagePreviewDialog]. */
internal sealed interface ImagePreviewState {
    data object Loading : ImagePreviewState

    data class Failed(val message: String? = null) : ImagePreviewState

    data class Ready(val bitmap: ImageBitmap) : ImagePreviewState
}

private const val MinZoom = 1f
private const val MaxZoom = 5f
private const val DoubleTapZoom = 2.5f
private val ZoomInFloat = tween<Float>(durationMillis = 250)
private val ZoomInOffset = tween<Offset>(durationMillis = 250)
private val ZoomResetFloat = spring<Float>(stiffness = Spring.StiffnessMediumLow)
private val ZoomResetOffset = spring<Offset>(stiffness = Spring.StiffnessMediumLow)
private val Backdrop = Color.Black.copy(alpha = 0.94f)

/**
 * Shared full-screen image viewer. The dialog window draws edge-to-edge behind
 * the system bars. Gestures: pinch to zoom and pan, double-tap to toggle
 * between 1x and [DoubleTapZoom], single tap to dismiss.
 */
@Composable
internal fun ImagePreviewDialog(
    state: ImagePreviewState,
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    testTag: String? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ImmersiveDialogWindowEffect()
        val scope = rememberCoroutineScope()
        var scale by remember { mutableFloatStateOf(MinZoom) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var viewSize by remember { mutableStateOf(IntSize.Zero) }
        var animJob by remember { mutableStateOf<Job?>(null) }
        val zoomable = state is ImagePreviewState.Ready

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
                    .pointerInput(onDismiss, zoomable) {
                        detectTapGestures(
                            onTap = { onDismiss() },
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
                    .transformable(transformState),
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
                                fontFamily = FontFamily.Monospace
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
    }
}

/**
 * Platform hook making the dialog window draw edge-to-edge behind the system
 * bars. No-op on platforms whose dialogs already span the full screen.
 */
@Composable internal expect fun ImmersiveDialogWindowEffect()
