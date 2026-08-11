package io.github.yearsyan.ohpi.markdown

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdownFlow
import io.github.yearsyan.ohpi.theme.PiTheme
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MarkdownViewTest {
    @Test
    fun rendersGfmStructureAndFencedCode() = runComposeUiTest {
        setContent {
            PiTheme {
                MarkdownView(
                    markdown = """
                        # GFM heading

                        - parent
                          - nested item

                        | name | value |
                        | --- | ---: |
                        | answer | 42 |

                        Build with `assembleRelease` now.

                        - `first-adjacent-inline`
                        - `second-adjacent-inline`

                        Wrapped inline code: `alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau`.

                        ```js linenums
                        const answer = 42
                        ```
                    """.trimIndent(),
                    modifier = Modifier.width(480.dp),
                )
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("GFM heading").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("GFM heading").assertExists()
        onNodeWithText("nested item").assertExists()
        assertTrue(onAllNodesWithText("answer", substring = true).fetchSemanticsNodes().isNotEmpty())
        onNodeWithText("assembleRelease", substring = true).assertExists()
        onNodeWithText("first-adjacent-inline", substring = true).assertExists()
        onNodeWithText("second-adjacent-inline", substring = true).assertExists()
        onNodeWithText("alpha beta gamma", substring = true).assertExists()
        onNodeWithText("js").assertExists()
        onNodeWithText("const answer = 42", substring = true).assertExists()
    }

    @Test
    fun rejectsParseResultFromDifferentContent() = runTest {
        val original = "cache-guard-original"
        val different = "cache-guard-different"
        val parsed = parseMarkdownFlow(original)
            .filterIsInstance<State.Success>()
            .first()

        MarkdownParseCache.put(different, parsed)

        assertNull(MarkdownParseCache.peek(different))
    }

    @Test
    fun changingContentDoesNotCacheTheRetainedPreviousParse() = runComposeUiTest {
        val initial = "stream-cache-initial"
        val finalMarker = "stream-cache-final-tail"
        val updated = buildString {
            appendLine(initial)
            appendLine()
            repeat(250) { index -> appendLine("- streamed detail $index") }
            append(finalMarker)
        }
        var markdown by mutableStateOf(initial)
        var visible by mutableStateOf(true)

        setContent {
            PiTheme {
                if (visible) MarkdownView(markdown)
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(initial, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        runOnIdle { markdown = updated }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(finalMarker, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // Dispose and recreate the view so it must consume the cached parse.
        // A stale entry under [updated] would make the final tail disappear.
        runOnIdle { visible = false }
        waitForIdle()
        runOnIdle { visible = true }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(finalMarker, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(finalMarker, substring = true).assertExists()
    }

    @Test
    fun nonCacheableContentIsNotPersisted() = runComposeUiTest {
        val markdown = "stream-cache-disabled-marker"

        setContent {
            PiTheme {
                MarkdownView(markdown = markdown, cacheable = false)
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(markdown, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        runOnIdle {
            assertNull(MarkdownParseCache.peek(markdown))
        }
    }
}
