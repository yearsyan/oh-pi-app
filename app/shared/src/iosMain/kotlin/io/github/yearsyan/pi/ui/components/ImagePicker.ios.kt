package io.github.yearsyan.pi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import io.github.yearsyan.pi.chat.PromptImage
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.base64EncodedStringWithOptions
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.conformsToType
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberImagePicker(onResult: (ImagePickResult) -> Unit): ImagePicker {
    val viewController = LocalUIViewController.current
    val currentOnResult by rememberUpdatedState(onResult)
    // PHPickerViewController.delegate is weak, so the active delegate must be
    // retained here until the pick finishes (or is cancelled).
    val holder = remember { DelegateHolder() }
    return remember(viewController) {
        ImagePicker(available = true) {
            val configuration = PHPickerConfiguration()
            configuration.filter = PHPickerFilter.imagesFilter()
            configuration.selectionLimit = 1
            val picker = PHPickerViewController(configuration = configuration)
            val delegate =
                ImagePickerDelegate { result ->
                    holder.activeDelegate = null
                    if (result != null) currentOnResult(result)
                }
            holder.activeDelegate = delegate
            picker.delegate = delegate
            topViewController(viewController).presentViewController(
                picker,
                animated = true,
                completion = null,
            )
        }
    }
}

private class DelegateHolder {
    var activeDelegate: ImagePickerDelegate? = null
}

private fun topViewController(root: UIViewController): UIViewController {
    var top = root
    while (true) {
        top = top.presentedViewController ?: return top
    }
}

@OptIn(ExperimentalForeignApi::class)
private class ImagePickerDelegate(
    private val onFinished: (ImagePickResult?) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        if (result == null) {
            // Cancelled.
            onFinished(null)
            return
        }
        val provider = result.itemProvider
        val typeId =
            provider.registeredTypeIdentifiers
                .filterIsInstance<String>()
                .firstOrNull { id -> UTType.typeWithIdentifier(id)?.conformsToType(UTTypeImage) == true }
                ?: UTTypeImage.identifier.takeIf { provider.hasItemConformingToTypeIdentifier(it) }
        if (typeId == null) {
            onFinished(ImagePickResult.Failed)
            return
        }
        provider.loadDataRepresentationForTypeIdentifier(typeId) { data, _ ->
            val pickResult =
                when {
                    data == null -> ImagePickResult.Failed
                    data.length > MaxPickedImageBytes.toULong() -> ImagePickResult.TooLarge
                    else ->
                        ImagePickResult.Success(
                            PromptImage(
                                data = data.base64EncodedStringWithOptions(0uL),
                                mimeType =
                                    UTType.typeWithIdentifier(typeId)?.preferredMIMEType
                                        ?: "image/jpeg",
                                name = fileName(provider.suggestedName, typeId),
                            ),
                        )
                }
            dispatch_async(dispatch_get_main_queue()) { onFinished(pickResult) }
        }
    }
}

private fun fileName(suggestedName: String?, typeId: String): String {
    val base = suggestedName?.takeIf { it.isNotBlank() } ?: "image"
    if (base.contains('.')) return base
    val extension = UTType.typeWithIdentifier(typeId)?.preferredFilenameExtension ?: return base
    return "$base.$extension"
}
