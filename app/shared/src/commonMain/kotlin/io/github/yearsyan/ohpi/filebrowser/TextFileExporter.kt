package io.github.yearsyan.ohpi.filebrowser

import androidx.compose.runtime.Composable

internal enum class FileExportResult {
    Completed,
    Cancelled,
    CopiedForSharing,
}

/** Platform file export UI. Failures propagate to the preview's status message. */
internal interface TextFileExporter {
    suspend fun save(bytes: ByteArray, fileName: String): FileExportResult

    suspend fun share(bytes: ByteArray, fileName: String): FileExportResult
}

@Composable
internal expect fun rememberTextFileExporter(): TextFileExporter

/** Keep Unicode names and extensions, without allowing paths or platform control characters. */
internal fun safeTextFileName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\')
        .map { if (it < ' ' || it in "<>:\"|?*" || it == '\u007f') '_' else it }
        .joinToString("")
        .trim().trimEnd('.')
        .ifBlank { "document.txt" }

internal fun textFileMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "md", "markdown" -> "text/markdown"
    "json" -> "application/json"
    "xml" -> "application/xml"
    "html", "htm" -> "text/html"
    "csv" -> "text/csv"
    "css" -> "text/css"
    "js", "mjs" -> "text/javascript"
    else -> "text/plain"
}
