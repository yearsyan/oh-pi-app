package io.github.yearsyan.ohpi.filebrowser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.i18n.S
import io.github.yearsyan.ohpi.markdown.MarkdownView
import io.github.yearsyan.ohpi.net.FileEntry
import io.github.yearsyan.ohpi.net.FileListResponse
import io.github.yearsyan.ohpi.net.FileReadResponse
import io.github.yearsyan.ohpi.syntax.MAX_HIGHLIGHT_LENGTH
import io.github.yearsyan.ohpi.syntax.Syntax
import io.github.yearsyan.ohpi.theme.piExtras
import io.github.yearsyan.ohpi.theme.rememberCodeFontFamily
import io.github.yearsyan.ohpi.ui.components.ImagePreviewDialog
import io.github.yearsyan.ohpi.ui.components.ImagePreviewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/** State for the preview of one remote file (text sheet or image viewer). */
class FilePreview(
    val name: String,
    val path: String,
    val loading: Boolean,
    val content: String = "",
    val truncated: Boolean = false,
    val error: String? = null,
    val image: ImageBitmap? = null,
    val isImage: Boolean = false,
)

/** Drives a FileBrowserScreen: directory navigation and file preview state. */
class FileBrowserController(
    private val scope: CoroutineScope,
    initialPath: String,
    private val listFiles: suspend (String) -> FileListResponse,
    private val readFile: suspend (String) -> FileReadResponse,
    private val downloadFile: suspend (String) -> ByteArray,
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
        if (isImageFile(entry.name)) {
            openImageEntry(entry)
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

    /**
     * Downloads an image via the raw download endpoint (the text read endpoint
     * rejects binaries) and decodes it off the main thread.
     */
    private fun openImageEntry(entry: FileEntry) {
        preview = FilePreview(name = entry.name, path = entry.path, loading = true, isImage = true)
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    downloadFile(entry.path).decodeToImageBitmap()
                }
                val current = preview
                if (current != null && current.path == entry.path) {
                    preview = FilePreview(
                        name = entry.name,
                        path = entry.path,
                        loading = false,
                        image = bitmap,
                        isImage = true,
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
                        isImage = true,
                    )
                }
            }
        }
    }

    fun dismissPreview() {
        preview = null
    }
}

/** Width at which the browser switches to the two-pane list + detail layout. */
private val DualPaneBreakpoint = 840.dp

/** Width of the directory list column in two-pane mode. */
private val ListPaneWidth = 380.dp

/** Standalone remote file browser page backed by the gateway file HTTP API. */
@Composable
fun FileBrowserScreen(
    controller: FileBrowserController,
    onBack: () -> Unit,
    onOpenApk: (FileEntry) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Tablets, foldables and desktop windows get a master-detail layout:
        // the directory stays pinned on the left and files open inline on the
        // right instead of covering the list with a sheet or dialog.
        val dualPane = maxWidth >= DualPaneBreakpoint
        Column(Modifier.fillMaxSize()) {
            FileBrowserTopBar(controller, onBack)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (dualPane) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    FileListPane(
                        controller,
                        onOpenApk,
                        Modifier.width(ListPaneWidth).fillMaxHeight(),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FilePreviewPane(
                        preview = controller.preview,
                        onClose = controller::dismissPreview,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else {
                FileListPane(controller, onOpenApk, Modifier.fillMaxWidth().weight(1f))
            }
        }

        if (!dualPane) {
            controller.preview?.let { preview ->
                if (preview.isImage) {
                    ImagePreviewDialog(
                        state = when {
                            preview.loading -> ImagePreviewState.Loading
                            preview.error != null ->
                                ImagePreviewState.Failed(S.fileOpenFailed(preview.error.orEmpty()))
                            preview.image != null -> ImagePreviewState.Ready(preview.image)
                            else -> ImagePreviewState.Failed()
                        },
                        onDismiss = controller::dismissPreview,
                        title = preview.name,
                        subtitle = preview.path,
                    )
                } else {
                    FilePreviewSheet(preview, onDismiss = controller::dismissPreview)
                }
            }
        }
    }
}

/**
 * Directory listing half of the browser. The ".." row stays pinned above the
 * content states so an empty or unreadable folder never strands the user
 * without a way back to the parent directory.
 */
@Composable
private fun FileListPane(
    controller: FileBrowserController,
    onOpenApk: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (controller.canGoUp) {
            UpDirectoryRow(onClick = controller::goUp)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
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
private fun UpDirectoryRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "..",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileList(controller: FileBrowserController, onOpenApk: (FileEntry) -> Unit) {
    val selectedPath = controller.preview?.path
    LazyColumn(Modifier.fillMaxSize()) {
        items(controller.entries, key = { it.path }) { entry ->
            FileRow(
                entry,
                selected = entry.path == selectedPath,
                onClick = {
                    if (isApk(entry)) onOpenApk(entry) else controller.openEntry(entry)
                },
            )
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            val typeVisual = if (entry.isDir) null else fileTypeVisual(entry.name)
            when {
                entry.isDir -> Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                isApk(entry) -> Icon(
                    Icons.Filled.Android,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                isImageFile(entry.name) -> Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                typeVisual != null -> FileTypeChip(typeVisual)
                else -> Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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

/**
 * Right-hand detail pane of the two-pane layout: an empty hint until a file is
 * selected, then the text/markdown detail or image viewer inline.
 */
@Composable
private fun FilePreviewPane(
    preview: FilePreview?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        when {
            preview == null ->
                Text(
                    S.fileSelectHint,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            preview.isImage -> ImagePreviewPane(preview, onClose)

            else -> FilePreviewDetail(preview, Modifier.fillMaxSize(), onClose = onClose)
        }
    }
}

/** Inline image viewer for the two-pane layout (the dialog stays for phones). */
@Composable
private fun ImagePreviewPane(preview: FilePreview, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        when {
            preview.loading ->
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(32.dp),
                )

            preview.error != null ->
                Text(
                    S.fileOpenFailed(preview.error.orEmpty()),
                    modifier = Modifier.align(Alignment.Center).padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

            preview.image != null ->
                Image(
                    bitmap = preview.image,
                    contentDescription = preview.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                preview.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = rememberCodeFontFamily()),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = S.close)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePreviewSheet(preview: FilePreview, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        FilePreviewDetail(preview, Modifier.fillMaxWidth().fillMaxHeight(0.8f))
    }
}

/**
 * Shared text/markdown detail: header with name and path above scrollable
 * rendered content. Hosts the bottom sheet on phones and fills the right
 * pane of the two-pane layout (which also passes [onClose] for a close
 * button).
 */
@Composable
private fun FilePreviewDetail(
    preview: FilePreview,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val isMarkdown = isMarkdownFile(preview.name)
    var showSource by remember(preview.path) { mutableStateOf(false) }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    preview.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = rememberCodeFontFamily()),
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
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = S.close)
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
                    // MarkdownView owns text selection; the pane scrolls it.
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
                        if (spec != null && preview.content.length <= MAX_HIGHLIGHT_LENGTH) {
                            value = withContext(Dispatchers.Default) {
                                Syntax.highlight(preview.content, spec, syntaxColors)
                            }
                        }
                    }
                    SelectionContainer(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        val style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = rememberCodeFontFamily(),
                        )
                        Text(
                            text = highlighted ?: AnnotatedString(preview.content),
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            style = style,
                            color = syntaxColors.plain,
                            softWrap = false,
                        )
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

/** Extensions decodable by Compose resources (gif/ico render as still frames). */
private val ImageExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "ico")

private fun isImageFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in ImageExtensions

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
