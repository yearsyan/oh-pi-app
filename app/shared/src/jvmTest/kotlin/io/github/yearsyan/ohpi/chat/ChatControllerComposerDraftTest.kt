package io.github.yearsyan.ohpi.chat

import io.github.yearsyan.ohpi.net.GatewayCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ChatControllerComposerDraftTest {
    private val image = PromptImage(
        data = "aGVsbG8=",
        mimeType = "image/png",
        name = "screen.png",
    )

    @Test
    fun newChatRestoresComposerByWorkspace() {
        val cache = ComposerDraftCache()
        val first = testController(cache)
        first.prepareCreate("workspace-a", "/workspace-a")
        first.updateComposerText("unfinished")
        first.updateComposerImages(listOf(image))

        val restored = testController(cache)
        restored.prepareCreate("workspace-a", "/workspace-a")
        val otherWorkspace = testController(cache)
        otherWorkspace.prepareCreate("workspace-b", "/workspace-b")

        assertEquals("unfinished", restored.composerText)
        assertEquals(listOf(image), restored.composerImages)
        assertEquals("", otherWorkspace.composerText)
        assertTrue(otherWorkspace.composerImages.isEmpty())
    }

    @Test
    fun historicalChatRestoresComposerBySessionAfterReconnect() {
        val cache = ComposerDraftCache()
        val first = testController(cache)
        first.connect("attach", "session-a")
        first.updateComposerText("session draft")
        first.updateComposerImages(listOf(image))
        first.disconnect()

        first.connect("attach", "session-a")
        assertEquals("session draft", first.composerText)
        assertEquals(listOf(image), first.composerImages)

        val restored = testController(cache)
        restored.connect("attach", "session-a")
        val otherSession = testController(cache)
        otherSession.connect("attach", "session-b")

        assertEquals("session draft", restored.composerText)
        assertEquals(listOf(image), restored.composerImages)
        assertEquals("", otherSession.composerText)
        assertTrue(otherSession.composerImages.isEmpty())
    }

    private fun testController(cache: ComposerDraftCache): ChatController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.cancel()
        return ChatController(
            scope = scope,
            gateway = "ws://localhost",
            token = "",
            onToast = { _, _ -> },
            onSessionReady = { _, _, _, _ -> },
            onSessionNameChanged = { _, _ -> },
            onStreamingChanged = { _, _ -> },
            strings = {
                ChatController.ChatStrings(
                    commandRejected = "",
                    abortSent = "",
                    compacting = "",
                    compacted = "",
                    retryOk = "",
                    retryFailed = "",
                    agentDone = "",
                    retrying = "",
                    notify = "",
                    modelOptionsFailed = { "" },
                )
            },
            loadCapabilities = {
                GatewayCapabilities(
                    workspaceId = it,
                    directory = "/$it",
                    models = emptyList(),
                )
            },
            cacheNamespace = "server",
            composerDraftCache = cache,
        )
    }
}
