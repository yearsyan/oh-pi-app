package io.github.yearsyan.ohpi.chat

import io.github.yearsyan.ohpi.net.PiJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionStatsTest {
    @Test
    fun parsesRpcSessionStatsAndComputesCumulativeCacheHitRate() {
        val data = PiJson.parseToJsonElement(
            """
            {
              "userMessages": 3,
              "assistantMessages": 4,
              "toolCalls": 5,
              "toolResults": 5,
              "totalMessages": 12,
              "tokens": {
                "input": 100,
                "output": 20,
                "cacheRead": 300,
                "cacheWrite": 100,
                "total": 520
              },
              "cost": 1.25,
              "contextUsage": {
                "tokens": 64000,
                "contextWindow": 200000,
                "percent": 32.0
              }
            }
            """.trimIndent(),
        ).jsonObject

        val stats = parseSessionStats(data)

        assertEquals(3L, stats.userMessages)
        assertEquals(520L, stats.tokens.total)
        assertEquals(60.0, stats.cacheHitPercent)
        assertEquals(64_000L, stats.contextUsage?.tokens)
        assertEquals(200_000L, stats.contextUsage?.contextWindow)
        assertEquals(32.0, stats.contextUsage?.percent)
        assertEquals(1.25, stats.cost)
    }

    @Test
    fun preservesUnknownContextAfterCompaction() {
        val data = PiJson.parseToJsonElement(
            """
            {
              "tokens": {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0},
              "contextUsage": {"tokens": null, "contextWindow": 200000, "percent": null}
            }
            """.trimIndent(),
        ).jsonObject

        val stats = parseSessionStats(data)

        assertNull(stats.contextUsage?.tokens)
        assertNull(stats.contextUsage?.percent)
        assertNull(stats.cacheHitPercent)
        assertEquals(0L, stats.tokens.total)
    }
}
