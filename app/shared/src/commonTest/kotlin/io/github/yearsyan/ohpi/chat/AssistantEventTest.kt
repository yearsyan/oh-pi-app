package io.github.yearsyan.ohpi.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantEventTest {
    @Test
    fun onlyMessageEndFinalizesAssistantContent() {
        assertTrue(shouldFinalizeAssistant("message_end"))
        assertFalse(shouldFinalizeAssistant("turn_end"))
    }

    @Test
    fun extractsFriendlyProviderFailure() {
        val message =
            buildJsonObject {
                put("stopReason", "error")
                put("errorMessage", "401 {\"error\":{\"message\":\"Invalid API key\"}}")
            }

        assertEquals("401: Invalid API key", assistantFailureMessage(message))
    }

    @Test
    fun ignoresSuccessfulAndAbortedAssistantMessages() {
        assertNull(
            assistantFailureMessage(
                buildJsonObject {
                    put("stopReason", "stop")
                    put("errorMessage", "not an error")
                },
            ),
        )
        assertNull(
            assistantFailureMessage(
                buildJsonObject {
                    put("stopReason", "aborted")
                    put("errorMessage", "cancelled")
                },
            ),
        )
    }
}
