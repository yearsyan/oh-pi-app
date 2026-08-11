package io.github.yearsyan.ohpi.ui.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import io.github.yearsyan.ohpi.chat.ChatController
import io.github.yearsyan.ohpi.net.GatewayCapabilities
import io.github.yearsyan.ohpi.net.GatewayModelCapability
import io.github.yearsyan.ohpi.theme.PiTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

@OptIn(ExperimentalTestApi::class)
class ComposerKeyboardTest {
    @Test
    fun desktopCtrlEnterInsertsLineBreakAndEnterSubmits() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = testController(scope)
        controller.prepareCreate("workspace", "/workspace")
        assertTrue(controller.canSendPrompt)
        var submittedText: String? = null

        setContent {
            PiTheme {
                Composer(
                    controller = controller,
                    onSubmitInput = { text, _ -> submittedText = text },
                )
            }
        }

        val input = onNode(hasSetTextAction())
        input.performTextInput("hello")
        input.performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) }
        }
        input.assertTextEquals("hello\n")
        runOnIdle { assertNull(submittedText) }

        input.performTextInput("world")
        input.performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals("hello\nworld", submittedText) }

        scope.cancel()
    }

    private fun testController(scope: CoroutineScope): ChatController =
        ChatController(
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
                    models =
                        listOf(
                            GatewayModelCapability(
                                id = "model",
                                name = "Model",
                                provider = "provider",
                            ),
                        ),
                )
            },
        )
}
