package io.github.yearsyan.ohpi.chat

/** App-owned prompt that has been submitted but has not entered the transcript yet. */
data class PendingSubmission(
    val sourceId: String,
    val text: String,
    val images: List<PromptImage>,
    val streaming: Boolean,
    val accepted: Boolean,
)

internal enum class QueuedPromptKind {
    Submission,
    Steering,
    FollowUp,
}

internal enum class QueuedPromptState {
    Submitting,
    AwaitingConsumption,
}

internal data class QueuedPromptItem(
    val kind: QueuedPromptKind,
    val state: QueuedPromptState,
    val text: String,
    val imageCount: Int = 0,
)

/**
 * Merges the local in-flight submission with pi's replayable queues. Exact
 * matches are coalesced so the composer never renders the same prompt twice.
 */
internal fun queuedPromptItems(
    pending: PendingSubmission?,
    steering: List<String>,
    followUp: List<String>,
): List<QueuedPromptItem> {
    val items = mutableListOf<QueuedPromptItem>()
    val pendingSteeringIndex =
        pending
            ?.takeIf { it.streaming }
            ?.let { local -> steering.indexOf(local.text).takeIf { it >= 0 } }

    if (pending != null && pendingSteeringIndex == null) {
        items +=
            QueuedPromptItem(
                kind = if (pending.streaming) QueuedPromptKind.Steering else QueuedPromptKind.Submission,
                state =
                    if (pending.accepted) {
                        QueuedPromptState.AwaitingConsumption
                    } else {
                        QueuedPromptState.Submitting
                    },
                text = pending.text,
                imageCount = pending.images.size,
            )
    }

    steering.forEachIndexed { index, text ->
        items +=
            QueuedPromptItem(
                kind = QueuedPromptKind.Steering,
                state = QueuedPromptState.AwaitingConsumption,
                text = text,
                imageCount = if (index == pendingSteeringIndex) pending.images.size else 0,
            )
    }
    followUp.forEach { text ->
        items +=
            QueuedPromptItem(
                kind = QueuedPromptKind.FollowUp,
                state = QueuedPromptState.AwaitingConsumption,
                text = text,
            )
    }
    return items
}
