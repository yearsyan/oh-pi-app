package io.github.yearsyan.ohpi.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Regression tests for the chat "scroll to bottom" behavior: the last render
 * group can be a single item taller than the viewport (a long assistant run),
 * and index-based scrolling must still reach the true end of the content.
 */
@OptIn(ExperimentalTestApi::class)
class ScrollToBottomTest {
    private fun LazyListState.tailGapToContentEnd(): Int {
        val info = layoutInfo
        val last = info.visibleItemsInfo.last()
        return info.viewportEndOffset - info.afterContentPadding - (last.offset + last.size)
    }

    @Composable
    private fun ProbeList(
        state: LazyListState,
        tallLastItem: Boolean,
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 10.dp, bottom = 210.dp),
        ) {
            items(49) { Text("item $it", Modifier.height(48.dp)) }
            item { Text("tail", Modifier.height(if (tallLastItem) 2000.dp else 48.dp)) }
        }
    }

    @Test
    fun shortLastItemScrollToBottomReachesContentEnd() = runComposeUiTest {
        lateinit var state: LazyListState
        setContent {
            state = rememberLazyListState()
            ProbeList(state, tallLastItem = false)
        }
        waitForIdle()
        runBlocking { state.scrollToBottom(49) }
        runOnIdle {
            assertFalse(state.canScrollForward, "list must not be scrollable further down")
            assertEquals(0, state.tailGapToContentEnd())
        }
    }

    @Test
    fun tallLastItemScrollToBottomReachesContentEnd() = runComposeUiTest {
        lateinit var state: LazyListState
        setContent {
            state = rememberLazyListState()
            ProbeList(state, tallLastItem = true)
        }
        waitForIdle()
        runBlocking {
            // documents the pitfall: plain scrollToItem stops at the item's top
            state.scrollToItem(49)
        }
        runOnIdle {
            assertTrue(state.canScrollForward, "plain scrollToItem leaves the tail below the fold")
        }
        runBlocking { state.scrollToBottom(49) }
        runOnIdle {
            assertFalse(state.canScrollForward, "list must not be scrollable further down")
            assertEquals(0, state.tailGapToContentEnd())
        }
    }

    @Test
    fun tallLastItemAnimateScrollToBottomReachesContentEnd() = runComposeUiTest {
        lateinit var state: LazyListState
        var animationFinished by mutableStateOf(false)
        setContent {
            state = rememberLazyListState()
            LaunchedEffect(Unit) {
                state.animateScrollToBottom(49)
                animationFinished = true
            }
            ProbeList(state, tallLastItem = true)
        }
        waitUntil(timeoutMillis = 15_000) { animationFinished }
        runOnIdle {
            assertFalse(state.canScrollForward, "list must not be scrollable further down")
            assertEquals(0, state.tailGapToContentEnd())
        }
    }

    @Test
    fun negativeIndexIsANoOp() = runComposeUiTest {
        lateinit var state: LazyListState
        setContent {
            state = rememberLazyListState()
            ProbeList(state, tallLastItem = false)
        }
        waitForIdle()
        runBlocking { state.scrollToBottom(-1) }
        runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
        }
    }
}
