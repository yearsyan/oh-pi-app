package io.github.yearsyan.ohpi.chat

import io.github.yearsyan.ohpi.net.long
import io.github.yearsyan.ohpi.net.obj
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

data class SessionTokenUsage(
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val total: Long,
)

data class SessionContextUsage(
    val tokens: Long?,
    val contextWindow: Long,
    val percent: Double?,
)

data class SessionStats(
    val userMessages: Long,
    val assistantMessages: Long,
    val toolCalls: Long,
    val toolResults: Long,
    val totalMessages: Long,
    val tokens: SessionTokenUsage,
    val cost: Double,
    val contextUsage: SessionContextUsage?,
) {
    /** Weighted cache-read share across all prompt tokens billed in this session. */
    val cacheHitPercent: Double?
        get() {
            val promptTokens = tokens.input + tokens.cacheRead + tokens.cacheWrite
            if (promptTokens <= 0L) return null
            return tokens.cacheRead.toDouble() / promptTokens.toDouble() * 100.0
        }
}

internal fun parseSessionStats(data: JsonObject): SessionStats {
    val tokenData = data.obj("tokens")
    val input = tokenData?.long("input") ?: 0L
    val output = tokenData?.long("output") ?: 0L
    val cacheRead = tokenData?.long("cacheRead") ?: 0L
    val cacheWrite = tokenData?.long("cacheWrite") ?: 0L
    val contextData = data.obj("contextUsage")
    return SessionStats(
        userMessages = data.long("userMessages") ?: 0L,
        assistantMessages = data.long("assistantMessages") ?: 0L,
        toolCalls = data.long("toolCalls") ?: 0L,
        toolResults = data.long("toolResults") ?: 0L,
        totalMessages = data.long("totalMessages") ?: 0L,
        tokens = SessionTokenUsage(
            input = input,
            output = output,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
            total = tokenData?.long("total") ?: (input + output + cacheRead + cacheWrite),
        ),
        cost = (data["cost"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
        contextUsage = contextData?.let {
            SessionContextUsage(
                tokens = it.long("tokens"),
                contextWindow = it.long("contextWindow") ?: 0L,
                percent = (it["percent"] as? JsonPrimitive)?.doubleOrNull,
            )
        },
    )
}
