package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import io.github.yearsyan.ohpi.chat.PromptImage
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberClipboardImagePasteHandler(
    enabled: Boolean,
    onResult: (ImagePickResult) -> Unit,
): (KeyEvent) -> Boolean {
    val scope = rememberCoroutineScope()
    val currentEnabled = rememberUpdatedState(enabled)
    val currentOnResult = rememberUpdatedState(onResult)
    val pasteInProgress = remember { AtomicBoolean(false) }
    return remember(scope) {
        handler@{ event ->
            if (!currentEnabled.value || !event.isPasteShortcut()) return@handler false
            if (!clipboardContainsImage()) return@handler false
            if (!pasteInProgress.compareAndSet(false, true)) return@handler true

            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { readClipboardImage() }
                    currentOnResult.value(result)
                } finally {
                    pasteInProgress.set(false)
                }
            }
            true
        }
    }
}

private fun KeyEvent.isPasteShortcut(): Boolean =
    type == KeyEventType.KeyDown &&
        key == Key.V &&
        (isCtrlPressed || isMetaPressed) &&
        !isAltPressed &&
        !isShiftPressed

private fun clipboardContainsImage(): Boolean =
    try {
        Toolkit.getDefaultToolkit().systemClipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)
    } catch (_: Exception) {
        false
    }

private fun readClipboardImage(): ImagePickResult =
    try {
        val image =
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.imageFlavor) as? Image
                ?: return ImagePickResult.Failed
        encodeClipboardImage(
            image = image,
            name = "clipboard-${System.currentTimeMillis()}.png",
        )
    } catch (_: Exception) {
        ImagePickResult.Failed
    }

internal fun encodeClipboardImage(
    image: Image,
    name: String,
    maxBytes: Int = MaxPickedImageBytes,
): ImagePickResult {
    val buffered = image.toBufferedImage() ?: return ImagePickResult.Failed
    val bytes =
        try {
            ByteArrayOutputStream().use { output ->
                if (!ImageIO.write(buffered, "png", output)) return ImagePickResult.Failed
                output.toByteArray()
            }
        } catch (_: Exception) {
            return ImagePickResult.Failed
        }
    if (bytes.size > maxBytes) return ImagePickResult.TooLarge
    return ImagePickResult.Success(
        listOf(
            PromptImage(
                data = Base64.getEncoder().encodeToString(bytes),
                mimeType = "image/png",
                name = name.ifBlank { "clipboard.png" },
            ),
        ),
    )
}

private fun Image.toBufferedImage(): BufferedImage? {
    if (this is BufferedImage) return this
    val loadedImage = ImageIcon(this)
    val imageWidth = loadedImage.iconWidth
    val imageHeight = loadedImage.iconHeight
    if (imageWidth <= 0 || imageHeight <= 0) return null
    return BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB).also { buffered ->
        val graphics = buffered.createGraphics()
        try {
            graphics.drawImage(loadedImage.image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
    }
}
