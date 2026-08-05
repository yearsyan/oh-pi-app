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
                val result = withContext(Dispatchers.IO) { chooseImages() }
                if (result != null) currentOnResult.value(result)
            }
        }
    }
}

private fun chooseImages(): ImagePickResult? {
    val dialog = FileDialog(null as Frame?, "Choose images", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    val files = dialog.files?.filter { it.isFile }?.take(MaxPickedImageCount).orEmpty()
    if (files.isEmpty()) return null
    val images = mutableListOf<PromptImage>()
    for (file in files) {
        if (file.length() > MaxPickedImageBytes) return ImagePickResult.TooLarge
        val image = readImage(file) ?: return ImagePickResult.Failed
        images += image
    }
    return ImagePickResult.Success(images)
}

private fun readImage(file: java.io.File): PromptImage? =
    runCatching {
        val mimeType = Files.probeContentType(file.toPath())?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        PromptImage(
            data = Base64.getEncoder().encodeToString(file.readBytes()),
            mimeType = mimeType,
            name = file.name,
        )
    }.getOrNull()
