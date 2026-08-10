package io.github.yearsyan.ohpi.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiDialogQueueTest {
    @Test
    fun duplicateRequestIdIsQueuedOnlyOnce() {
        val queue = UiDialogQueue()
        val request = request("dialog-1")

        assertTrue(queue.offer(request))
        assertFalse(queue.offer(request.copy(title = "duplicate")))
        assertEquals(request, queue.current)
    }

    @Test
    fun resolutionRemovesTheMatchingRequestAndAdvancesTheQueue() {
        val queue = UiDialogQueue()
        queue.offer(request("dialog-1"))
        queue.offer(request("dialog-2"))

        assertTrue(queue.resolve("dialog-1"))
        assertEquals("dialog-2", queue.current?.id)
        assertFalse(queue.offer(request("dialog-1")))
    }

    @Test
    fun resolvedIdCanBeReusedInALaterAgentTurn() {
        val queue = UiDialogQueue()
        queue.offer(request("dialog-1"))
        queue.resolve("dialog-1")

        queue.beginAgentTurn()

        assertTrue(queue.offer(request("dialog-1")))
    }

    @Test
    fun resolutionTombstoneSuppressesALateCopyOfTheRequest() {
        val queue = UiDialogQueue()

        assertFalse(queue.resolve("dialog-1"))
        assertFalse(queue.offer(request("dialog-1")))
        assertNull(queue.current)
    }

    @Test
    fun resetClearsPendingAndResolvedState() {
        val queue = UiDialogQueue()
        queue.offer(request("dialog-1"))
        queue.resolve("dialog-1")

        queue.reset()

        assertNull(queue.current)
        assertTrue(queue.offer(request("dialog-1")))
    }

    private fun request(id: String) =
        UiDialogRequest(
            id = id,
            method = "confirm",
            title = "Continue?",
            message = "",
            options = emptyList(),
            placeholder = "",
            prefill = "",
        )
}
