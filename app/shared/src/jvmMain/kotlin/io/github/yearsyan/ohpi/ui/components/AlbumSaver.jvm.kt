package io.github.yearsyan.ohpi.ui.components

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Desktop has no photo album; ask for a destination with a native save dialog. */
internal actual suspend fun saveImageToAlbum(
    bytes: ByteArray,
    displayName: String,
    mimeType: String,
): AlbumSaveResult =
    withContext(Dispatchers.IO) {
        val dialog = FileDialog(null as Frame?, "Save image", FileDialog.SAVE)
        try {
            dialog.file = displayName
            dialog.isVisible = true // modal; blocks until the user decides
            val directory = dialog.directory ?: return@withContext AlbumSaveResult.Cancelled
            val fileName = dialog.file ?: return@withContext AlbumSaveResult.Cancelled
            runCatching { File(directory, fileName).writeBytes(bytes) }
                .fold(
                    onSuccess = { AlbumSaveResult.Success },
                    onFailure = { AlbumSaveResult.Failure(it.message) },
                )
        } finally {
            dialog.dispose()
        }
    }
