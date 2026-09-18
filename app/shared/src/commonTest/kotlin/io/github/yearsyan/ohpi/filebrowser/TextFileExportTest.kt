package io.github.yearsyan.ohpi.filebrowser

import io.github.yearsyan.ohpi.net.FileListResponse
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class TextFileExportTest {
    @Test
    fun exportDownloadsOriginalBytesInsteadOfTruncatedPreview() = runTest {
        val original = "\uFEFF# 标题\r\n完整内容\r\n".encodeToByteArray()
        val paths = mutableListOf<String>()
        val controller = FileBrowserController(
            this, "/workspace", { FileListResponse(it) }, { error("Unexpected preview read") },
            { path -> paths += path; original },
        )
        val preview = FilePreview("说明.md", "/workspace/说明.md", false, "# 标题", truncated = true)

        assertContentEquals(original, controller.downloadTextFile(preview))
        assertEquals(original.decodeToString(), controller.copyText(preview))
        assertEquals(listOf(preview.path, preview.path), paths)
    }

    @Test
    fun copyKeepsMarkdownSourceAndSupportsEmptyFilesWithoutDownloading() = runTest {
        val controller = FileBrowserController(
            this, "/", { FileListResponse(it) }, { error("Unexpected preview read") },
            { error("Complete previews should copy without a download") },
        )
        val source = "# 标题\n\n**bold** and `code`"
        assertEquals(source, controller.copyText(FilePreview("note.md", "/note.md", false, source)))
        assertEquals("", controller.copyText(FilePreview("empty.txt", "/empty.txt", false)))
    }

    @Test
    fun failedFullDownloadNeverFallsBackToPartialContent() = runTest {
        val controller = FileBrowserController(
            this, "/", { FileListResponse(it) }, { error("Unexpected preview read") },
            { error("Download failed") },
        )
        val preview = FilePreview("large.txt", "/large.txt", false, "partial", truncated = true)
        assertFailsWith<IllegalStateException> { controller.copyText(preview) }
        assertFailsWith<IllegalStateException> { controller.downloadTextFile(preview) }
    }

    @Test
    fun fileNameCannotEscapeExportDirectoryAndKeepsUnicodeExtension() {
        assertEquals("说明.md", safeTextFileName("../../说明.md"))
        assertEquals("file.txt", safeTextFileName("C:\\folder\\file.txt"))
        assertEquals("document.txt", safeTextFileName(".."))
        assertEquals("document.txt", safeTextFileName("  "))
        assertEquals("bad_name_.json", safeTextFileName("bad\u0000name?.json"))
        assertEquals(".env", safeTextFileName(".env"))
    }
}
