package io.github.yearsyan.ohpi.filebrowser

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.i18n.S
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Suppress("DEPRECATION")
@Composable
internal fun TextFileActions(
    controller: FileBrowserController,
    preview: FilePreview,
    exporter: TextFileExporter = rememberTextFileExporter(),
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val strings = S
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun perform(action: suspend () -> String?) {
        if (busy) return
        busy = true
        status = null
        failed = false
        scope.launch {
            try {
                status = action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed = true
                status = strings.fileActionFailed(failure.message ?: "unknown error")
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(enabled = !busy, onClick = {
                perform {
                    val result = exporter.save(controller.downloadTextFile(preview), safeTextFileName(preview.name))
                    if (result == FileExportResult.Completed) strings.fileSaved else null
                }
            }) {
                Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                Text(strings.fileSaveLocal, Modifier.padding(start = 6.dp))
            }
            TextButton(enabled = !busy, onClick = {
                perform {
                    clipboard.setText(AnnotatedString(controller.copyText(preview)))
                    strings.fileCopied
                }
            }) {
                Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
                Text(strings.fileCopyText, Modifier.padding(start = 6.dp))
            }
            TextButton(enabled = !busy, onClick = {
                perform {
                    when (exporter.share(controller.downloadTextFile(preview), safeTextFileName(preview.name))) {
                        FileExportResult.CopiedForSharing -> strings.fileCopiedForSharing
                        else -> null
                    }
                }
            }) {
                Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                Text(strings.fileShare, Modifier.padding(start = 6.dp))
            }
        }
        if (busy) {
            CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp), strokeWidth = 2.dp)
        }
        status?.let {
            Text(
                it,
                Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}
