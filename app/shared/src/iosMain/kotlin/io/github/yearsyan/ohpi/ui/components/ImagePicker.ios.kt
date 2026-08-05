package io.github.yearsyan.ohpi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import io.github.yearsyan.ohpi.chat.PromptImage
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSItemProvider
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
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_group_create
import platform.darwin.dispatch_group_enter
import platform.darwin.dispatch_group_leave
import platform.darwin.dispatch_group_notify

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
            configuration.selectionLimit = MaxPickedImageCount.toLong()
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
        val results = didFinishPicking.filterIsInstance<PHPickerResult>()
        if (results.isEmpty()) {
            // Cancelled.
            onFinished(null)
            return
        }
        // Each item loads asynchronously; collect per-index outcomes and emit a
        // single aggregated result once every load has completed.
        val outcomes = arrayOfNulls<LoadOutcome>(results.size)
        val group = dispatch_group_create()
        results.forEachIndexed { index, result ->
            val provider = result.itemProvider
            val typeId = imageTypeIdentifier(provider)
            if (typeId == null) {
                outcomes[index] = LoadOutcome.Failed
                return@forEachIndexed
            }
            dispatch_group_enter(group)
            provider.loadDataRepresentationForTypeIdentifier(typeId) { data, _ ->
                outcomes[index] =
                    when {
                        data == null -> LoadOutcome.Failed
                        data.length > MaxPickedImageBytes.toULong() -> LoadOutcome.TooLarge
                        else ->
                            LoadOutcome.Ok(
                                PromptImage(
                                    data = data.base64EncodedStringWithOptions(0uL),
                                    mimeType =
                                        UTType.typeWithIdentifier(typeId)?.preferredMIMEType
                                            ?: "image/jpeg",
                                    name = fileName(provider.suggestedName, typeId),
                                ),
                            )
                    }
                dispatch_group_leave(group)
            }
        }
        dispatch_group_notify(group, dispatch_get_main_queue()) {
            onFinished(aggregateOutcomes(outcomes))
        }
    }
}

private sealed interface LoadOutcome {
    data class Ok(val image: PromptImage) : LoadOutcome

    data object TooLarge : LoadOutcome

    data object Failed : LoadOutcome
}

private fun aggregateOutcomes(outcomes: Array<LoadOutcome?>): ImagePickResult {
    val images = mutableListOf<PromptImage>()
    for (outcome in outcomes) {
        when (outcome) {
            is LoadOutcome.Ok -> images += outcome.image
            LoadOutcome.TooLarge -> return ImagePickResult.TooLarge
            else -> return ImagePickResult.Failed
        }
    }
    return ImagePickResult.Success(images)
}

@OptIn(ExperimentalForeignApi::class)
private fun imageTypeIdentifier(provider: NSItemProvider): String? =
    provider.registeredTypeIdentifiers
        .filterIsInstance<String>()
        .firstOrNull { id -> UTType.typeWithIdentifier(id)?.conformsToType(UTTypeImage) == true }
        ?: UTTypeImage.identifier.takeIf { provider.hasItemConformingToTypeIdentifier(it) }

private fun fileName(suggestedName: String?, typeId: String): String {
    val base = suggestedName?.takeIf { it.isNotBlank() } ?: "image"
    if (base.contains('.')) return base
    val extension = UTType.typeWithIdentifier(typeId)?.preferredFilenameExtension ?: return base
    return "$base.$extension"
}
