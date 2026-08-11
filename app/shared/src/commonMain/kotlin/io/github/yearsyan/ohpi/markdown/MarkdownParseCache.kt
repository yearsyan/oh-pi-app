package io.github.yearsyan.ohpi.markdown

import androidx.compose.runtime.Stable
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdownFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Screen-wide LRU cache of parsed markdown trees.
 *
 * The mikepenz renderer parses content asynchronously: every fresh
 * composition starts as a zero-height `Loading` box and jumps to full height
 * once parsing completes on a background dispatcher. Inside the chat
 * `LazyColumn` that jump is visible every time a recycled item re-enters the
 * viewport (`retainState` only survives content changes within one
 * composition, not item disposal). Caching the finished [State.Success] lets
 * [MarkdownView] render synchronously on a hit, so an item measures at its
 * final height on the very first frame.
 *
 * All members are confined to the main thread: reads happen during
 * composition, and [warm] resumes on the caller's context after its
 * background parse. No locking is required.
 */
object MarkdownParseCache {
    private const val MAX_ENTRIES = 256

    // Insertion-ordered map; recency is maintained manually by re-inserting on
    // access because common Kotlin's LinkedHashMap has no access-order mode.
    private val entries = LinkedHashMap<String, State.Success>()
    private val inFlight = mutableSetOf<String>()

    /** Returns the parsed state for [content], refreshing its recency. */
    fun peek(content: String): State.Success? {
        val state = entries.remove(content) ?: return null
        // A retained MarkdownState can still expose the previous successful
        // parse while its new input is being parsed. Never let such a stale
        // result survive under the new content key.
        if (state.content != content) return null
        entries[content] = state
        return state
    }

    /** Caches a finished parse result, e.g. one observed from an async render. */
    fun put(content: String, state: State.Success) {
        if (state.content != content) return
        entries.remove(content)
        entries[content] = state
        while (entries.size > MAX_ENTRIES) {
            entries.remove(entries.keys.first())
        }
    }

    /**
     * Parses [content] off the main thread and caches the result. Repeated or
     * concurrent calls for the same content are coalesced. Parsing uses the
     * same GFM flavour and reference-link handling as the renderer defaults,
     * so the cached state is interchangeable with a freshly parsed one.
     */
    suspend fun warm(content: String) {
        if (content.isBlank() || peek(content) != null || !inFlight.add(content)) return
        try {
            val parsed = withContext(Dispatchers.Default) {
                runCatching {
                    parseMarkdownFlow(content).filterIsInstance<State.Success>().first()
                }.getOrNull()
            }
            if (parsed != null) put(content, parsed)
        } finally {
            inFlight.remove(content)
        }
    }
}

/** A [MarkdownState] that is already parsed, rendering content synchronously. */
@Stable
internal class PreparsedMarkdownState(
    private val success: State.Success,
) : MarkdownState {
    override val state: StateFlow<State> = MutableStateFlow(success)
    override val links: StateFlow<Map<String, String?>> = MutableStateFlow(emptyMap())

    override suspend fun parse(): State = success
}
