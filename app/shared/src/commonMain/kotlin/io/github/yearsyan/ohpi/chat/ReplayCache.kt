package io.github.yearsyan.ohpi.chat

import io.github.yearsyan.ohpi.net.PiJson
import io.github.yearsyan.ohpi.net.long
import io.github.yearsyan.ohpi.net.obj
import io.github.yearsyan.ohpi.net.strOrEmpty
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Buffer
import okio.BufferedSource

private const val ReplayCacheVersion = 2L
private const val ReplayCacheHeader = "header"
private const val ReplayCacheEvent = "event"
private const val ReplayCacheCheckpoint = "checkpoint"
private const val ReplayCacheFrameHeaderBytes = 8L
private const val Crc32Polynomial = -306674912
private val crc32Table = IntArray(256) { value ->
    var checksum = value
    repeat(8) {
        checksum = if (checksum and 1 != 0) {
            (checksum ushr 1) xor Crc32Polynomial
        } else {
            checksum ushr 1
        }
    }
    checksum
}

internal data class CachedReplayRecord(
    val seq: Long,
    val payload: JsonObject,
)

internal data class DecodedReplayCache(
    val baseEntryId: String,
    val baseThroughSeq: Long,
    val throughSeq: Long,
    val records: List<CachedReplayRecord>,
)

internal data class ReplayCacheDecodeResult(
    val cache: DecodedReplayCache?,
    val validBytes: Long,
)

/**
 * Streams a framed replay cache one record at a time. A malformed first frame
 * invalidates the cache; a partial or corrupt later frame leaves the validated
 * prefix resumable at [ReplayCacheDecodeResult.validBytes].
 */
internal fun decodeReplayCache(
    source: BufferedSource,
    maxFrameBytes: Long = DefaultMaxReplayCacheBytes,
): ReplayCacheDecodeResult {
    require(maxFrameBytes > ReplayCacheFrameHeaderBytes) { "maximum replay frame size is too small" }
    var validBytes = 0L
    var baseEntryId: String? = null
    var baseThroughSeq = 0L
    var throughSeq = 0L
    val records = mutableListOf<CachedReplayRecord>()

    while (source.request(1L)) {
        if (!source.request(ReplayCacheFrameHeaderBytes)) break
        val payloadSize = source.readInt().toLong()
        val expectedChecksum = source.readInt()
        if (payloadSize <= 0L || payloadSize > maxFrameBytes - ReplayCacheFrameHeaderBytes) break
        if (!source.request(payloadSize)) break

        val payloadBytes = source.readByteArray(payloadSize)
        if (crc32(payloadBytes) != expectedChecksum) break
        val record = parseReplayCacheRecord(payloadBytes) ?: break

        if (baseEntryId == null) {
            if (record.strOrEmpty("kind") != ReplayCacheHeader ||
                record.long("version") != ReplayCacheVersion
            ) {
                break
            }
            val headerThroughSeq = record.long("through_seq")?.takeIf { it >= 0L } ?: break
            baseEntryId = record.strOrEmpty("entry_id")
            baseThroughSeq = headerThroughSeq
            throughSeq = headerThroughSeq
        } else {
            when (record.strOrEmpty("kind")) {
                ReplayCacheEvent -> {
                    val seq = record.long("seq")?.takeIf { it > throughSeq } ?: break
                    val payload = record.obj("payload") ?: break
                    records += CachedReplayRecord(seq, payload)
                    throughSeq = seq
                }
                ReplayCacheCheckpoint -> {
                    val checkpoint = record.long("through_seq")?.takeIf { it >= throughSeq } ?: break
                    throughSeq = checkpoint
                }
                else -> break
            }
        }
        validBytes += ReplayCacheFrameHeaderBytes + payloadSize
    }

    val entryId = baseEntryId
    return ReplayCacheDecodeResult(
        cache = entryId?.let {
            DecodedReplayCache(
                baseEntryId = it,
                baseThroughSeq = baseThroughSeq,
                throughSeq = throughSeq,
                records = records,
            )
        },
        validBytes = validBytes,
    )
}

internal fun decodeReplayCache(bytes: ByteArray): DecodedReplayCache? =
    decodeReplayCache(Buffer().write(bytes)).cache

internal fun encodeReplayCacheHeader(entryId: String, throughSeq: Long): ByteArray =
    replayCacheFrame {
        put("kind", ReplayCacheHeader)
        put("version", ReplayCacheVersion)
        put("entry_id", entryId)
        put("through_seq", throughSeq.coerceAtLeast(0L))
    }

internal fun encodeReplayCacheRecord(seq: Long, payload: JsonObject): ByteArray =
    replayCacheFrame {
        put("kind", ReplayCacheEvent)
        put("seq", seq)
        put("payload", payload)
    }

internal fun encodeReplayCacheCheckpoint(throughSeq: Long): ByteArray =
    replayCacheFrame {
        put("kind", ReplayCacheCheckpoint)
        put("through_seq", throughSeq)
    }

/**
 * Drops replay states made redundant by authoritative terminal events. Sparse
 * sequence numbers are intentional because the checkpoint is the resume cursor.
 */
internal fun compactReplayRecords(records: List<CachedReplayRecord>): List<CachedReplayRecord> {
    val compacted = mutableListOf<CachedReplayRecord?>()
    val lastToolUpdateIndex = mutableMapOf<String, Int>()
    val lastSingletonIndex = mutableMapOf<String, Int>()
    var assistantStartIndex: Int? = null

    for (record in records) {
        val outputType = record.payload.strOrEmpty("type")
        when (outputType) {
            "message_start" -> {
                if (record.payload.obj("message")?.strOrEmpty("role") == "assistant") {
                    assistantStartIndex = compacted.size
                }
            }
            "message_end" -> {
                if (record.payload.obj("message")?.strOrEmpty("role") == "assistant") {
                    assistantStartIndex?.let { startIndex ->
                        for (index in startIndex until compacted.size) {
                            val existing = compacted[index] ?: continue
                            if (existing.payload.strOrEmpty("type") == "message_update" ||
                                existing.payload.strOrEmpty("type") == "message_start" &&
                                existing.payload.obj("message")?.strOrEmpty("role") == "assistant"
                            ) {
                                compacted[index] = null
                            }
                        }
                    }
                    assistantStartIndex = null
                }
            }
            "tool_execution_update" -> {
                val toolCallId = record.payload.strOrEmpty("toolCallId")
                if (toolCallId.isNotEmpty()) {
                    lastToolUpdateIndex.put(toolCallId, compacted.size)?.let { compacted[it] = null }
                }
            }
            "tool_execution_end" -> {
                val toolCallId = record.payload.strOrEmpty("toolCallId")
                if (toolCallId.isNotEmpty()) {
                    lastToolUpdateIndex.remove(toolCallId)?.let { compacted[it] = null }
                }
            }
            "queue_update", "session_info_changed" -> {
                lastSingletonIndex.put(outputType, compacted.size)?.let { compacted[it] = null }
            }
        }
        compacted += record
    }
    return compacted.filterNotNull()
}

private fun replayCacheFrame(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ByteArray {
    val payload = buildJsonObject(build).toString().encodeToByteArray()
    return Buffer()
        .writeInt(payload.size)
        .writeInt(crc32(payload))
        .write(payload)
        .readByteArray()
}

private fun parseReplayCacheRecord(bytes: ByteArray): JsonObject? =
    runCatching { PiJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull()

private fun crc32(bytes: ByteArray): Int {
    var checksum = -1
    for (byte in bytes) {
        val index = (checksum xor byte.toInt()) and 0xff
        checksum = crc32Table[index] xor (checksum ushr 8)
    }
    return checksum xor -1
}
