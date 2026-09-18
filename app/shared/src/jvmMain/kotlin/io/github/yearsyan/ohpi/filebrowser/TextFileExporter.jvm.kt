package io.github.yearsyan.ohpi.filebrowser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.yearsyan.ohpi.i18n.S
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberTextFileExporter(): TextFileExporter {
    val saveTitle = S.fileSaveLocal
    return remember(saveTitle) {
        object : TextFileExporter {
            override suspend fun save(bytes: ByteArray, fileName: String): FileExportResult {
                val target = chooseSaveFile(saveTitle, fileName) ?: return FileExportResult.Cancelled
                withContext(Dispatchers.IO) { target.writeBytes(bytes) }
                return FileExportResult.Completed
            }

            override suspend fun share(bytes: ByteArray, fileName: String): FileExportResult {
                withContext(Dispatchers.IO) {
                    val directory = Files.createTempDirectory("ohpi-share-").toFile()
                    directory.deleteOnExit()
                    val file = File(directory, fileName).apply {
                        deleteOnExit()
                        writeBytes(bytes)
                    }
                    // AWT has no cross-platform share sheet. Native file clipboard data lets
                    // users paste the actual attachment into mail, chat, or their file manager.
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(SharedFile(file), null)
                }
                return FileExportResult.CopiedForSharing
            }
        }
    }
}

private suspend fun chooseSaveFile(title: String, fileName: String): File? =
    suspendCancellableCoroutine { continuation ->
        SwingUtilities.invokeLater {
            if (!continuation.isActive) return@invokeLater
            val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
            continuation.invokeOnCancellation { SwingUtilities.invokeLater { dialog.dispose() } }
            try {
                dialog.file = fileName
                dialog.isVisible = true
                val target = dialog.file?.let { File(dialog.directory, it) }
                if (continuation.isActive) continuation.resume(target)
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            } finally {
                dialog.dispose()
            }
        }
    }

internal class SharedFile(private val file: File) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor

    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return listOf(file)
    }
}
