package io.github.yearsyan.ohpi.filebrowser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.net.FileListResponse
import io.github.yearsyan.ohpi.theme.PiTheme
import java.awt.datatransfer.DataFlavor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred

@OptIn(ExperimentalTestApi::class)
class TextFileActionsTest {
    @Test
    fun actionsWaitForCompleteDownloadAndHandleCancelSaveAndShare() = runComposeUiTest {
        val download = CompletableDeferred<ByteArray>()
        val bytes = "# 完整原文\r\n尾部".encodeToByteArray()
        val saved = mutableListOf<ByteArray>()
        val shared = mutableListOf<ByteArray>()
        val exporter = object : TextFileExporter {
            override suspend fun save(bytes: ByteArray, fileName: String): FileExportResult {
                assertEquals("说明.md", fileName)
                saved += bytes
                return if (saved.size == 1) FileExportResult.Cancelled else FileExportResult.Completed
            }

            override suspend fun share(bytes: ByteArray, fileName: String): FileExportResult {
                assertEquals("说明.md", fileName)
                shared += bytes
                return FileExportResult.CopiedForSharing
            }
        }
        setContent {
            val scope = rememberCoroutineScope()
            val controller = remember {
                FileBrowserController(scope, "/", { FileListResponse(it) }, { error("Unexpected read") }) {
                    assertEquals("/说明.md", it)
                    download.await()
                }
            }
            PiTheme {
                Box(Modifier.width(360.dp)) {
                    TextFileActions(
                        controller,
                        FilePreview("说明.md", "/说明.md", false, "partial", truncated = true),
                        exporter,
                    )
                }
            }
        }

        onNodeWithText("Save file").performClick()
        onNodeWithText("Save file").assertIsNotEnabled()
        onNodeWithText("Copy text").assertIsNotEnabled()
        onNodeWithText("Share file").assertIsNotEnabled()
        runOnIdle { download.complete(bytes) }
        waitForIdle()
        onNodeWithText("File saved").assertDoesNotExist()
        onNodeWithText("Save file").assertIsEnabled().performClick()
        onNodeWithText("File saved").assertExists()
        onNodeWithText("Share file").performClick()
        onNodeWithText("File copied. Paste it into another app to share.").assertExists()
        runOnIdle {
            assertEquals(2, saved.size)
            saved.forEach { assertContentEquals(bytes, it) }
            assertEquals(1, shared.size)
            assertContentEquals(bytes, shared.single())
        }
    }

    @Test
    fun downloadFailureShowsErrorAndAllowsRetryWithoutExportingPartialText() = runComposeUiTest {
        var attempts = 0
        var exports = 0
        val exporter = object : TextFileExporter {
            override suspend fun save(bytes: ByteArray, fileName: String): FileExportResult {
                exports++
                return FileExportResult.Completed
            }

            override suspend fun share(bytes: ByteArray, fileName: String) = error("Unexpected share")
        }
        setContent {
            val scope = rememberCoroutineScope()
            val controller = remember {
                FileBrowserController(scope, "/", { FileListResponse(it) }, { error("Unexpected read") }) {
                    if (++attempts == 1) error("network unavailable")
                    "complete".encodeToByteArray()
                }
            }
            PiTheme {
                TextFileActions(controller, FilePreview("note.txt", "/note.txt", false, "partial", true), exporter)
            }
        }

        onNodeWithText("Save file").performClick()
        onNodeWithText("File action failed: network unavailable").assertExists()
        runOnIdle { assertEquals(0, exports) }
        onNodeWithText("Save file").assertIsEnabled().performClick()
        onNodeWithText("File action failed: network unavailable").assertDoesNotExist()
        onNodeWithText("File saved").assertExists()
        runOnIdle { assertEquals(1, exports) }
    }

    @Test
    fun desktopSharingProvidesAFileAttachmentToNativeClipboardConsumers() {
        val file = File("说明.md")
        val transferable = SharedFile(file)
        assertEquals(listOf(file), transferable.getTransferData(DataFlavor.javaFileListFlavor))
    }
}
