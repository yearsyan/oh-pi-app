package io.github.yearsyan.ohpi.markdown

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.yearsyan.ohpi.theme.PiTheme
import kotlin.test.Test
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
}
