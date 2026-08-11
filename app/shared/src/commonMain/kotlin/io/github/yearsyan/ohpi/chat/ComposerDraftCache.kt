package io.github.yearsyan.ohpi.chat

internal sealed interface ComposerDraftKey {
    val namespace: String

    data class Workspace(
        override val namespace: String,
        val workspaceId: String,
    ) : ComposerDraftKey

    data class Session(
        override val namespace: String,
        val sessionId: String,
    ) : ComposerDraftKey
}

internal data class ComposerDraft(
    val text: String = "",
    val images: List<PromptImage> = emptyList(),
) {
    val isEmpty: Boolean
        get() = text.isEmpty() && images.isEmpty()

    fun snapshot(): ComposerDraft = copy(images = images.toList())
}

/** Process-memory-only storage for unsent composer contents. */
class ComposerDraftCache {
    private val drafts = mutableMapOf<ComposerDraftKey, ComposerDraft>()

    internal fun read(key: ComposerDraftKey): ComposerDraft =
        drafts[key]?.snapshot() ?: ComposerDraft()

    internal fun write(key: ComposerDraftKey, draft: ComposerDraft) {
        if (draft.isEmpty) {
            drafts.remove(key)
        } else {
            drafts[key] = draft.snapshot()
        }
    }

    internal fun move(
        from: ComposerDraftKey?,
        to: ComposerDraftKey,
        draft: ComposerDraft,
    ) {
        if (from != to) from?.let(drafts::remove)
        write(to, draft)
    }

    internal fun remove(key: ComposerDraftKey) {
        drafts.remove(key)
    }

    internal fun clear() {
        drafts.clear()
    }

    internal val size: Int
        get() = drafts.size
}
