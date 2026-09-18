package io.github.yearsyan.ohpi.filebrowser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import io.github.yearsyan.ohpi.web.topViewController
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

@Composable
internal actual fun rememberTextFileExporter(): TextFileExporter {
    val host = LocalUIViewController.current
    return remember(host) { IosTextFileExporter(host) }
}

@OptIn(ExperimentalForeignApi::class)
private class IosTextFileExporter(private val host: UIViewController) : TextFileExporter {
    // UIKit's document picker delegate is weak; retain it until completion/cancellation.
    private var activeDelegate: ExportDelegate? = null

    override suspend fun save(bytes: ByteArray, fileName: String): FileExportResult =
        withStagedFile(bytes, fileName) { url ->
            suspendCancellableCoroutine { continuation ->
                val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
                val delegate = ExportDelegate { result ->
                    activeDelegate = null
                    if (continuation.isActive) continuation.resume(result)
                }
                activeDelegate = delegate
                picker.delegate = delegate
                continuation.invokeOnCancellation {
                    dispatch_async(dispatch_get_main_queue()) {
                        picker.dismissViewControllerAnimated(false, completion = null)
                        activeDelegate = null
                    }
                }
                topViewController(host).presentViewController(picker, animated = true, completion = null)
            }
        }

    override suspend fun share(bytes: ByteArray, fileName: String): FileExportResult =
        withStagedFile(bytes, fileName) { url ->
            suspendCancellableCoroutine { continuation ->
                val presenter = topViewController(host)
                val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
                sheet.completionWithItemsHandler = { _, completed, _, error ->
                    if (continuation.isActive) {
                        if (error != null) continuation.resumeWithException(IllegalStateException(error.localizedDescription))
                        else continuation.resume(if (completed) FileExportResult.Completed else FileExportResult.Cancelled)
                    }
                }
                // Required on iPad, including the browser's two-pane layout.
                sheet.popoverPresentationController?.let { popover ->
                    popover.sourceView = presenter.view
                    popover.sourceRect = presenter.view.bounds
                    popover.permittedArrowDirections = 0uL
                }
                continuation.invokeOnCancellation {
                    dispatch_async(dispatch_get_main_queue()) {
                        sheet.dismissViewControllerAnimated(false, completion = null)
                    }
                }
                presenter.presentViewController(sheet, animated = true, completion = null)
            }
        }
}

@OptIn(ExperimentalForeignApi::class)
private class ExportDelegate(private val onFinished: (FileExportResult) -> Unit) :
    NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onFinished(if (didPickDocumentsAtURLs.isEmpty()) FileExportResult.Cancelled else FileExportResult.Completed)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onFinished(FileExportResult.Cancelled)
    }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun withStagedFile(
    bytes: ByteArray,
    fileName: String,
    present: suspend (NSURL) -> FileExportResult,
): FileExportResult {
    val directory = "${NSTemporaryDirectory()}ohpi-text-${NSUUID().UUIDString}"
    val path = "$directory/$fileName"
    try {
        withContext(Dispatchers.Default) {
            check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)) {
                "Could not create export directory"
            }
            val file = fopen(path, "wb") ?: error("Could not create export file")
            try {
                // Empty text files are valid and have no addressOf(0).
                if (bytes.isNotEmpty()) {
                    bytes.usePinned { pinned ->
                        check(fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file) == bytes.size.toULong()) {
                            "Could not write complete export file"
                        }
                    }
                }
            } finally {
                check(fclose(file) == 0) { "Could not finish export file" }
            }
        }
        return present(NSURL(fileURLWithPath = path))
    } finally {
        NSFileManager.defaultManager.removeItemAtPath(directory, null)
    }
}
