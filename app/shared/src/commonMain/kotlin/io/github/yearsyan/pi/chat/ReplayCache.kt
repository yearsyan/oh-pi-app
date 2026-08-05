package io.github.yearsyan.pi.chat

import io.github.yearsyan.pi.net.PiJson
import io.github.yearsyan.pi.net.long
import io.github.yearsyan.pi.net.strOrEmpty
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val ReplayCacheVersion = 1L
private const val ReplayCacheHeader = "header"
private const val ReplayCacheEvent = "event"
private const val ReplayCacheCheckpoint = "checkpoint"

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

/** Decodes an append-only replay cache and rejects partial or reordered tails. */
internal fun decodeReplayCache(bytes: ByteArray): DecodedReplayCache? {
    if (bytes.isEmpty()) return null
    val lines = bytes.decodeToString().lineSequence().filter(String::isNotBlank).iterator()
    if (!lines.hasNext()) return null
    val header = parseReplayCacheLine(lines.next()) ?: return null
    if (header.strOrEmpty("kind") != ReplayCacheHeader ||
        header.long("version") != ReplayCacheVersion
    ) {
        return null
    }
    val baseEntryId = header.strOrEmpty("entry_id")
    val baseThroughSeq = header.long("through_seq")?.takeIf { it >= 0L } ?: return null
    var throughSeq = baseThroughSeq
    val records = mutableListOf<CachedReplayRecord>()
    while (lines.hasNext()) {
        val record = parseReplayCacheLine(lines.next()) ?: return null
        when (record.strOrEmpty("kind")) {
            ReplayCacheEvent -> {
                val seq = record.long("seq")?.takeIf { it > throughSeq } ?: return null
                val payload = record["payload"] as? JsonObject ?: return null
                records += CachedReplayRecord(seq, payload)
                throughSeq = seq
            }
            ReplayCacheCheckpoint -> {
                val checkpoint = record.long("through_seq")?.takeIf { it >= throughSeq } ?: return null
                throughSeq = checkpoint
            }
            else -> return null
        }
    }
    return DecodedReplayCache(baseEntryId, baseThroughSeq, throughSeq, records)
}

internal fun encodeReplayCacheHeader(entryId: String, throughSeq: Long): ByteArray =
    replayCacheLine {
        put("kind", ReplayCacheHeader)
        put("version", ReplayCacheVersion)
        put("entry_id", entryId)
        put("through_seq", throughSeq.coerceAtLeast(0L))
    }

internal fun encodeReplayCacheRecord(seq: Long, payload: JsonObject): ByteArray =
    replayCacheLine {
        put("kind", ReplayCacheEvent)
        put("seq", seq)
        put("payload", payload)
    }

internal fun encodeReplayCacheCheckpoint(throughSeq: Long): ByteArray =
    replayCacheLine {
        put("kind", ReplayCacheCheckpoint)
        put("through_seq", throughSeq)
    }

private fun replayCacheLine(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ByteArray =
    (buildJsonObject(build).toString() + "\n").encodeToByteArray()

private fun parseReplayCacheLine(line: String): JsonObject? =
    runCatching { PiJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
