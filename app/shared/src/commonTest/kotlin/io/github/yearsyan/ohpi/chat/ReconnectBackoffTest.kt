package io.github.yearsyan.ohpi.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReconnectBackoffTest {
    @Test
    fun delayDoublesUntilCap() {
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)

        assertEquals(expected, expected.indices.map { reconnectDelayMillis(it + 1) })
    }

    @Test
    fun delayRejectsInvalidAttempt() {
        assertFailsWith<IllegalArgumentException> { reconnectDelayMillis(0) }
    }

    @Test
    fun delayRemainsCappedForLargeAttempts() {
        assertEquals(MaxReconnectDelayMillis, reconnectDelayMillis(Int.MAX_VALUE))
    }
}
