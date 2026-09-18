package io.github.yearsyan.ohpi.filebrowser

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private class PendingDocument {
    var continuation: CancellableContinuation<Uri?>? = null
}

@Composable
internal actual fun rememberTextFileExporter(): TextFileExporter {
    val context = LocalContext.current
    val pending = remember { PendingDocument() }
    val contract = remember {
        object : ActivityResultContracts.CreateDocument("text/plain") {
            override fun createIntent(context: Context, input: String): Intent =
                super.createIntent(context, input).setType(textFileMimeType(input))
        }
    }
    val launcher = rememberLauncherForActivityResult(contract) { uri ->
        val continuation = pending.continuation
        pending.continuation = null
        if (continuation?.isActive == true) continuation.resume(uri)
    }
    return remember(context, launcher) {
        object : TextFileExporter {
            override suspend fun save(bytes: ByteArray, fileName: String): FileExportResult {
                val uri = try {
                    suspendCancellableCoroutine<Uri?> { continuation ->
                        pending.continuation = continuation
                        launcher.launch(fileName)
                    }
                } finally {
                    pending.continuation = null
                } ?: return FileExportResult.Cancelled
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: error("Could not open the selected document")
                }
                return FileExportResult.Completed
            }

            override suspend fun share(bytes: ByteArray, fileName: String): FileExportResult {
                val file = withContext(Dispatchers.IO) {
                    val root = File(context.cacheDir, "text-files").apply { mkdirs() }
                    // Recipients may read after the chooser closes. Keep recent shares for a day.
                    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                    root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
                    val directory = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
                    File(directory, fileName).apply { writeBytes(bytes) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = textFileMimeType(fileName)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(fileName, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, null).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                return FileExportResult.Completed
            }
        }
    }
}
