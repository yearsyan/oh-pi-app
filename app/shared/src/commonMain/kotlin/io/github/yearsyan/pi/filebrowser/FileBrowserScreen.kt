package io.github.yearsyan.pi.filebrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yearsyan.pi.i18n.S
import io.github.yearsyan.pi.markdown.MarkdownView
import io.github.yearsyan.pi.net.FileEntry
import io.github.yearsyan.pi.net.FileListResponse
import io.github.yearsyan.pi.net.FileReadResponse
import io.github.yearsyan.pi.syntax.Syntax
import io.github.yearsyan.pi.theme.piExtras
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State for the bottom-sheet text preview of one remote file. */
class FilePreview(
    val name: String,
    val path: String,
    val loading: Boolean,
    val content: String = "",
    val truncated: Boolean = false,
    val error: String? = null,
)

/** Drives a FileBrowserScreen: directory navigation and text preview state. */
class FileBrowserController(
    private val scope: CoroutineScope,
    initialPath: String,
    private val listFiles: suspend (String) -> FileListResponse,
    private val readFile: suspend (String) -> FileReadResponse,
) {
    var currentPath by mutableStateOf(initialPath); private set
    var parentPath by mutableStateOf(""); private set
    val entries = mutableStateListOf<FileEntry>()
    var loading by mutableStateOf(false); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    var preview by mutableStateOf<FilePreview?>(null); private set

    private var generation = 0L

    init {
        navigateTo(initialPath)
    }

    val canGoUp: Boolean get() = parentPath.isNotBlank() && parentPath != currentPath

    fun navigateTo(path: String) {
        val gen = ++generation
        loading = true
        errorMessage = null
        scope.launch {
            try {
                val result = listFiles(path)
                if (gen != generation) return@launch
                currentPath = result.path
                parentPath = result.parent
                entries.clear()
                entries.addAll(result.entries)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (gen == generation) {
                    errorMessage = failure.message ?: "unknown error"
                }
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    fun goUp() {
        if (canGoUp) navigateTo(parentPath)
    }

    fun refresh() = navigateTo(currentPath)

    /** Opens a directory in place or loads a file into the preview sheet. */
    fun openEntry(entry: FileEntry) {
        if (entry.isDir) {
            navigateTo(entry.path)
            return
        }
        preview = FilePreview(name = entry.name, path = entry.path, loading = true)
        scope.launch {
            try {
                val result = readFile(entry.path)
                val current = preview
                if (current != null && current.path == entry.path) {
                    preview = FilePreview(
                        name = result.name.ifBlank { entry.name },
                        path = result.path,
                        loading = false,
                        content = result.content,
                        truncated = result.truncated,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val current = preview
                if (current != null && current.path == entry.path) {
                    preview = FilePreview(
                        name = entry.name,
                        path = entry.path,
                        loading = false,
                        error = failure.message ?: "unknown error",
                    )
                }
            }
        }
    }

    fun dismissPreview() {
        preview = null
    }
}

/** Standalone remote file browser page backed by the gateway file HTTP API. */
@Composable
fun FileBrowserScreen(
    controller: FileBrowserController,
    onBack: () -> Unit,
    onOpenApk: (FileEntry) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        FileBrowserTopBar(controller, onBack)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                controller.entries.isEmpty() && controller.loading ->
                    CenteredState { CircularProgressIndicator(Modifier.size(32.dp)) }

                controller.entries.isEmpty() && controller.errorMessage != null ->
                    CenteredState {
                        Text(
                            S.folderLoadFailed(controller.errorMessage.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = controller::refresh) { Text(S.retry) }
                    }

                controller.entries.isEmpty() ->
                    CenteredState {
                        Text(
                            S.filesEmptyFolder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                else -> FileList(controller, onOpenApk)
            }
        }
    }

    controller.preview?.let { preview ->
        FilePreviewSheet(preview, onDismiss = controller::dismissPreview)
    }
}

@Composable
private fun FileBrowserTopBar(controller: FileBrowserController, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = S.back)
        }
        Column(Modifier.weight(1f)) {
            Text(
                controller.currentPath.substringAfterLast('/').ifBlank { controller.currentPath }
                    .ifBlank { S.browseFiles },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (controller.currentPath.isNotBlank()) {
                Text(
                    controller.currentPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (controller.loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        IconButton(onClick = controller::refresh) {
            Icon(Icons.Filled.Refresh, contentDescription = S.retry)
        }
    }
}

@Composable
private fun FileList(controller: FileBrowserController, onOpenApk: (FileEntry) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (controller.canGoUp) {
            item(key = "..") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = controller::goUp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "..",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(controller.entries, key = { it.path }) { entry ->
            FileRow(entry, onClick = {
                if (isApk(entry)) onOpenApk(entry) else controller.openEntry(entry)
            })
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                entry.isDir -> Icons.Filled.Folder
                isApk(entry) -> Icons.Filled.Android
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = when {
                entry.isDir -> MaterialTheme.colorScheme.primary
                isApk(entry) -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(12.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!entry.isDir) {
            Spacer(Modifier.width(12.dp))
            Text(
                formatFileSize(entry.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Above this size preview text stays plain; highlighting is not worth the cost. */
private const val MaxHighlightLength = 256 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePreviewSheet(preview: FilePreview, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        val isMarkdown = isMarkdownFile(preview.name)
        var showSource by remember(preview.path) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        preview.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        preview.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (preview.truncated && !preview.loading && preview.error == null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            S.fileTruncatedNotice,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                if (isMarkdown && !preview.loading && preview.error == null) {
                    TextButton(onClick = { showSource = !showSource }) {
                        Text(if (showSource) S.fileViewRendered else S.fileViewSource)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when {
                preview.loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                    }

                preview.error != null ->
                    Box(
                        Modifier.fillMaxSize().padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            S.fileOpenFailed(preview.error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                else ->
                    if (isMarkdown && !showSource) {
                        // MarkdownView owns text selection; the sheet scrolls it.
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        ) {
                            MarkdownView(preview.content)
                        }
                    } else {
                        val spec = remember(preview.name) { Syntax.specForFileName(preview.name) }
                        val syntaxColors = piExtras.syntax
                        // Highlight off the main thread; plain text shows until ready.
                        val highlighted by produceState<AnnotatedString?>(
                            null,
                            preview.content,
                            spec,
                            syntaxColors,
                        ) {
                            if (spec != null && preview.content.length <= MaxHighlightLength) {
                                value = withContext(Dispatchers.Default) {
                                    Syntax.highlight(preview.content, spec, syntaxColors)
                                }
                            }
                        }
                        SelectionContainer(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                        ) {
                            val style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            )
                            val text = highlighted
                            if (text != null) Text(text, style = style)
                            else Text(preview.content, style = style)
                        }
                    }
            }
        }
    }
}

@Composable
private fun CenteredState(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

private fun isApk(entry: FileEntry): Boolean =
    !entry.isDir && entry.name.endsWith(".apk", ignoreCase = true)

private fun isMarkdownFile(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".md") || lower.endsWith(".markdown")
}

internal fun formatFileSize(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    if (value < 1024) return "$value B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var unit = -1
    do {
        size /= 1024.0
        unit++
    } while (size >= 1024.0 && unit < units.lastIndex)
    val rounded = (size * 10).toLong() / 10.0
    return "$rounded ${units[unit]}"
}

/** Wires the APK tap action: download from the gateway, then hand to the OS. */
@Composable
fun rememberApkOpener(
    downloadFile: suspend (String) -> ByteArray,
    onToast: (String) -> Unit,
): (FileEntry) -> Unit {
    val installer = rememberApkInstaller()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val downloadingMessage = S.apkDownloading
    val failureMessage = S.apkInstallFailed
    return remember(installer, downloadFile, downloadingMessage, failureMessage) {
        opener@{ entry ->
            if (!installer.available) return@opener
            if (busy) return@opener
            busy = true
            onToast(downloadingMessage)
            scope.launch {
                try {
                    val bytes = downloadFile(entry.path)
                    val error = installer.install(bytes, entry.name)
                    if (error != null) onToast(failureMessage(error))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    onToast(failureMessage(failure.message ?: "unknown error"))
                } finally {
                    busy = false
                }
            }
        }
    }
}
