package io.github.yearsyan.ohpi.net

import kotlin.test.Test
import kotlin.test.assertEquals

class GatewaySessionMetricsTest {
    @Test
    fun decodesNullableSessionPerformanceAverages() {
        val measured = PiJson.decodeFromString<GatewaySessionMetrics>(
            """
            {
              "sample_count": 4,
              "average_tps": 23.75,
              "average_ttft_ms": 812.5
            }
            """.trimIndent(),
        )
        assertEquals(4L, measured.sampleCount)
        assertEquals(23.75, measured.averageTps)
        assertEquals(812.5, measured.averageTtftMs)

        val empty = PiJson.decodeFromString<GatewaySessionMetrics>(
            """{"sample_count":0,"average_tps":null,"average_ttft_ms":null}""",
        )
        assertEquals(0L, empty.sampleCount)
        assertEquals(null, empty.averageTps)
        assertEquals(null, empty.averageTtftMs)
    }
}
