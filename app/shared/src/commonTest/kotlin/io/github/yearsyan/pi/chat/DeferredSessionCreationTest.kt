package io.github.yearsyan.pi.chat

import io.github.yearsyan.pi.data.ConnState
import kotlin.test.Test
import kotlin.test.assertEquals

class DeferredSessionCreationTest {
    @Test
    fun firstDraftPromptCreatesSession() {
        assertEquals(
            PromptDispatch.CreateSession,
            promptDispatch(ConnState.Disconnected, isDraft = true, hasPendingCreatePrompt = false),
        )
    }

    @Test
    fun connectedPromptUsesExistingSession() {
        assertEquals(
            PromptDispatch.ExistingSession,
            promptDispatch(ConnState.Ready, isDraft = false, hasPendingCreatePrompt = false),
        )
    }

    @Test
    fun draftCannotSubmitAgainWhileCreateIsPending() {
        assertEquals(
            PromptDispatch.Unavailable,
            promptDispatch(ConnState.Connecting, isDraft = true, hasPendingCreatePrompt = true),
        )
        assertEquals(
            PromptDispatch.Unavailable,
            promptDispatch(ConnState.Error, isDraft = true, hasPendingCreatePrompt = true),
        )
    }

    @Test
    fun unattachedSavedSessionCannotSend() {
        assertEquals(
            PromptDispatch.Unavailable,
            promptDispatch(ConnState.Disconnected, isDraft = false, hasPendingCreatePrompt = false),
        )
    }
}
