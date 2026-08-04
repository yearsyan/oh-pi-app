package io.github.yearsyan.pi.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantEventTest {
    @Test
    fun onlyMessageEndFinalizesAssistantContent() {
        assertTrue(shouldFinalizeAssistant("message_end"))
        assertFalse(shouldFinalizeAssistant("turn_end"))
    }
}
