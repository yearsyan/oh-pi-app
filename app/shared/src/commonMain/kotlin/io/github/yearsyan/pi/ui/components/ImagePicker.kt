package io.github.yearsyan.pi.ui.components

import androidx.compose.runtime.Composable
import io.github.yearsyan.pi.chat.PromptImage

internal const val MaxPickedImageBytes = 8 * 1024 * 1024

internal sealed interface ImagePickResult {
    data class Success(val image: PromptImage) : ImagePickResult

    data object TooLarge : ImagePickResult

    data object Failed : ImagePickResult
}

internal class ImagePicker(
    val available: Boolean,
    private val launchPicker: () -> Unit,
) {
    fun launch() = launchPicker()
}

@Composable
internal expect fun rememberImagePicker(onResult: (ImagePickResult) -> Unit): ImagePicker
