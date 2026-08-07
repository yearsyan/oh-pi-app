package io.github.yearsyan.ohpi.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class QueuedPromptTest {
    @Test
    fun localSteerAndServerQueueAreRenderedOnce() {
        val pending =
            PendingSubmission(
                sourceId = "local-1",
                text = "change direction",
                images = listOf(PromptImage("image", "image/png", "screen.png")),
                streaming = true,
                accepted = true,
            )

        val items = queuedPromptItems(pending, listOf("change direction"), emptyList())

        assertEquals(1, items.size)
        assertEquals(QueuedPromptKind.Steering, items.single().kind)
        assertEquals(QueuedPromptState.AwaitingConsumption, items.single().state)
        assertEquals(1, items.single().imageCount)
    }

    @Test
    fun acceptedPromptRemainsVisibleWhileQueueIsBeingConsumed() {
        val pending =
            PendingSubmission(
                sourceId = "local-2",
                text = "use the new approach",
                images = emptyList(),
                streaming = true,
                accepted = true,
            )

        val item = queuedPromptItems(pending, emptyList(), emptyList()).single()

        assertEquals(QueuedPromptKind.Steering, item.kind)
        assertEquals(QueuedPromptState.AwaitingConsumption, item.state)
    }

    @Test
    fun replayedQueuesRenderWithoutLocalComposerState() {
        val items =
            queuedPromptItems(
                pending = null,
                steering = listOf("steer from another client"),
                followUp = listOf("summarize when done"),
            )

        assertEquals(
            listOf(QueuedPromptKind.Steering, QueuedPromptKind.FollowUp),
            items.map { it.kind },
        )
        assertEquals(List(2) { QueuedPromptState.AwaitingConsumption }, items.map { it.state })
    }

    @Test
    fun unacceptedSubmissionShowsSubmittingState() {
        val pending =
            PendingSubmission(
                sourceId = "local-3",
                text = "hello",
                images = emptyList(),
                streaming = false,
                accepted = false,
            )

        val item = queuedPromptItems(pending, emptyList(), emptyList()).single()

        assertEquals(QueuedPromptKind.Submission, item.kind)
        assertEquals(QueuedPromptState.Submitting, item.state)
    }
}
