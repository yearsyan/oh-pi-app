package io.github.yearsyan.ohpi.chat

import io.github.yearsyan.ohpi.net.GatewayCapabilities
import io.github.yearsyan.ohpi.net.GatewayModelCapability
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * A gateway with no signed-in provider reports zero models; submitting there
 * can only produce pi's "No API key" error, so the controller must report the
 * missing-model state and refuse to send.
 */
class ChatControllerMissingModelTest {

    private fun testController(
        scope: CoroutineScope,
        capabilities: suspend () -> GatewayCapabilities,
    ): ChatController =
        ChatController(
            scope = scope,
            gateway = "ws://localhost",
            token = "",
            onToast = { _, _ -> },
            onSessionReady = { _, _, _ -> },
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
            loadCapabilities = { capabilities() },
        )

    private fun awaitCapabilities(controller: ChatController) {
        val deadline = System.currentTimeMillis() + 5_000
        while (controller.capabilitiesLoading && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertFalse(controller.capabilitiesLoading, "capabilities load timed out")
    }

    @Test
    fun draftWithoutModelsReportsMissingAndBlocksSubmit() {
        val controller = testController(CoroutineScope(Dispatchers.Default)) {
            GatewayCapabilities(workDir = "", models = emptyList())
        }
        controller.prepareCreate("")
        awaitCapabilities(controller)

        assertTrue(controller.missingModel)
        assertFalse(controller.canSendPrompt)
        assertFalse(controller.canSubmitInput("hello", hasImage = false))
    }

    @Test
    fun draftWithModelsAllowsSubmit() {
        val controller = testController(CoroutineScope(Dispatchers.Default)) {
            GatewayCapabilities(
                workDir = "",
                models = listOf(GatewayModelCapability(id = "m1", name = "M1", provider = "p1")),
            )
        }
        controller.prepareCreate("")
        awaitCapabilities(controller)

        assertFalse(controller.missingModel)
        assertTrue(controller.canSendPrompt)
        assertTrue(controller.canSubmitInput("hello", hasImage = false))
    }

    @Test
    fun capabilitiesFailureDoesNotReportMissingModel() {
        val controller = testController(CoroutineScope(Dispatchers.Default)) {
            throw IllegalStateException("gateway unreachable")
        }
        controller.prepareCreate("")
        awaitCapabilities(controller)

        assertFalse(controller.missingModel)
        assertTrue(controller.canSendPrompt)
    }

    @Test
    fun providerSetupNavigationIsTrackedOncePerDraft() {
        val controller = testController(CoroutineScope(Dispatchers.Default)) {
            GatewayCapabilities(workDir = "", models = emptyList())
        }
        controller.prepareCreate("")
        awaitCapabilities(controller)
        assertTrue(controller.missingModel)
        assertFalse(controller.providerSetupNavigated)

        controller.markProviderSetupNavigated()
        assertTrue(controller.providerSetupNavigated)

        // the next new chat gets its own auto-navigation
        controller.prepareCreate("")
        assertFalse(controller.providerSetupNavigated)
    }
}
