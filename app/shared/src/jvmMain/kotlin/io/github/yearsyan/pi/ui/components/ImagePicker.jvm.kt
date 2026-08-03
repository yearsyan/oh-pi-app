package io.github.yearsyan.pi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import io.github.yearsyan.pi.chat.PromptImage
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberImagePicker(onResult: (ImagePickResult) -> Unit): ImagePicker {
    val scope = rememberCoroutineScope()
    val currentOnResult = rememberUpdatedState(onResult)
    return remember {
        ImagePicker(available = true) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { chooseImage() }
                if (result != null) currentOnResult.value(result)
            }
        }
    }
}

private fun chooseImage(): ImagePickResult? {
    val dialog = FileDialog(null as Frame?, "Choose image", FileDialog.LOAD)
    dialog.isVisible = true
    val fileName = dialog.file ?: return null
    val file = dialog.directory?.let { java.io.File(it, fileName) } ?: return null
    if (!file.isFile) return ImagePickResult.Failed
    if (file.length() > MaxPickedImageBytes) return ImagePickResult.TooLarge
    return runCatching {
        val mimeType = Files.probeContentType(file.toPath())?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        ImagePickResult.Success(
            PromptImage(
                data = Base64.getEncoder().encodeToString(file.readBytes()),
                mimeType = mimeType,
                name = file.name,
            ),
        )
    }.getOrElse { ImagePickResult.Failed }
}
