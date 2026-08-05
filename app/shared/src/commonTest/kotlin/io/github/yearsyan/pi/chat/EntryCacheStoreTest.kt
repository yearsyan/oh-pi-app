package io.github.yearsyan.pi.chat

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem

class EntryCacheStoreTest {
    @Test
    fun commitsEntryDeltasAndDiscardsAnInterruptedUpdate() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "pi-entry-cache-test-${Random.nextLong().toString(16)}"
        val store = EntryCacheStore(rootPath = root.toString())
        val key = "server_session"
        try {
            store.begin(key, reset = true)
            store.append(entry("entry-1", "first"))
            store.commit("entry-1")

            store.begin(key, reset = false)
            store.append(entry("entry-2", "second"))
            val committed = store.commit("entry-2")
            val decoded = assertNotNull(decodeEntryCache(committed.entries))
            assertEquals("entry-2", committed.cursor)
            assertEquals(listOf("entry-1", "entry-2"), decoded.entries.map { it.toString().substringAfter("\"id\":\"").substringBefore('"') })

            store.begin(key, reset = false)
            store.append("not-json\n".encodeToByteArray())
            store.abort()

            val recovered = store.snapshot(key)
            assertEquals("entry-2", recovered.cursor)
            assertEquals("entry-2", assertNotNull(decodeEntryCache(recovered.entries)).lastEntryId)
        } finally {
            store.abort()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun decodeDeduplicatesEntriesByPersistentId() {
        val bytes = entry("entry-1", "old") + entry("entry-1", "new") + entry("entry-2", "last")
        val decoded = assertNotNull(decodeEntryCache(bytes))

        assertEquals(2, decoded.entries.size)
        assertEquals("entry-2", decoded.lastEntryId)
        assertEquals(true, decoded.entries.first().toString().contains("new"))
    }

    @Test
    fun persistsReplayEventsAndTheirIndependentHighWaterCursor() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "pi-replay-cache-test-${Random.nextLong().toString(16)}"
        val store = EntryCacheStore(rootPath = root.toString())
        val key = "server_session"
        try {
            store.resetReplay(key, encodeReplayCacheHeader("entry-7", 40L))
            store.appendReplay(
                key,
                encodeReplayCacheRecord(
                    42L,
                    buildJsonObject { put("type", "message_update"); put("delta", "new") },
                ),
            )
            store.appendReplay(key, encodeReplayCacheCheckpoint(45L))

            val replay = assertNotNull(store.loadReplay(key))
            assertEquals("entry-7", replay.baseEntryId)
            assertEquals(40L, replay.baseThroughSeq)
            assertEquals(45L, replay.throughSeq)
            assertEquals(listOf(42L), replay.records.map { it.seq })
            assertEquals("new", replay.records.single().payload["delta"]?.toString()?.trim('"'))

            store.resetReplay(key, encodeReplayCacheHeader("entry-8", 50L))
            val reset = assertNotNull(store.loadReplay(key))
            assertEquals("entry-8", reset.baseEntryId)
            assertEquals(50L, reset.throughSeq)
            assertEquals(emptyList(), reset.records)
        } finally {
            store.abort()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun truncatesAnIncompleteReplayTailAndContinuesAppending() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "pi-replay-tail-test-${Random.nextLong().toString(16)}"
        val key = "server_session"
        val store = EntryCacheStore(rootPath = root.toString())
        val header = encodeReplayCacheHeader("entry-1", 3L)
        val first = encodeReplayCacheRecord(4L, replayPayload("message_update"))
        val partial = encodeReplayCacheRecord(5L, replayPayload("message_update")).copyOf(11)
        val replayPath = root / "$key.replay.jsonl"
        try {
            store.resetReplay(key, header)
            store.appendReplay(key, first)
            store.appendReplay(key, partial)
            store.closeReplay()

            val recovered = assertNotNull(store.loadReplay(key))
            assertEquals(listOf(4L), recovered.records.map { it.seq })
            assertEquals((header.size + first.size).toLong(), FileSystem.SYSTEM.metadata(replayPath).size)

            store.appendReplay(key, encodeReplayCacheRecord(5L, replayPayload("message_end", role = "assistant")))
            store.closeReplay()
            assertEquals(listOf(4L, 5L), assertNotNull(store.loadReplay(key)).records.map { it.seq })
        } finally {
            store.closeReplay()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun truncatesACorruptReplayTailButRejectsACorruptHeader() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "pi-replay-crc-test-${Random.nextLong().toString(16)}"
        val key = "server_session"
        val store = EntryCacheStore(rootPath = root.toString())
        val header = encodeReplayCacheHeader("entry-1", 3L)
        val first = encodeReplayCacheRecord(4L, replayPayload("agent_start"))
        val corruptTail = encodeReplayCacheRecord(5L, replayPayload("message_update")).also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        val replayPath = root / "$key.replay.jsonl"
        try {
            store.resetReplay(key, header)
            store.appendReplay(key, first)
            store.appendReplay(key, corruptTail)
            store.closeReplay()

            val recovered = assertNotNull(store.loadReplay(key))
            assertEquals(listOf(4L), recovered.records.map { it.seq })
            assertEquals((header.size + first.size).toLong(), FileSystem.SYSTEM.metadata(replayPath).size)

            val corruptHeader = header.copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }
            store.rewriteReplay(key, listOf(corruptHeader))
            store.closeReplay()
            assertNull(store.loadReplay(key))
            assertFalse(FileSystem.SYSTEM.exists(replayPath))
        } finally {
            store.closeReplay()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun compactsTerminalReplayStatesAndKeepsAnIndependentHighWater() {
        val records = listOf(
            replayRecord(1L, "agent_start"),
            replayRecord(2L, "message_start", role = "assistant"),
            replayRecord(3L, "message_update"),
            replayRecord(4L, "message_update"),
            replayRecord(5L, "message_end", role = "assistant"),
            replayRecord(6L, "tool_execution_start", toolCallId = "call-1"),
            replayRecord(7L, "tool_execution_update", toolCallId = "call-1"),
            replayRecord(8L, "tool_execution_update", toolCallId = "call-1"),
            replayRecord(9L, "tool_execution_end", toolCallId = "call-1"),
            replayRecord(10L, "queue_update"),
            replayRecord(11L, "queue_update"),
            replayRecord(12L, "message_start", role = "assistant"),
            replayRecord(13L, "message_update"),
        )

        val compacted = compactReplayRecords(records)
        assertEquals(listOf(1L, 5L, 6L, 9L, 11L, 12L, 13L), compacted.map { it.seq })

        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "pi-replay-compact-test-${Random.nextLong().toString(16)}"
        val key = "server_session"
        val store = EntryCacheStore(rootPath = root.toString())
        try {
            store.rewriteReplay(
                key,
                buildList {
                    add(encodeReplayCacheHeader("entry-1", 0L))
                    compacted.forEach { add(encodeReplayCacheRecord(it.seq, it.payload)) }
                    add(encodeReplayCacheCheckpoint(20L))
                },
            )
            store.closeReplay()

            val replay = assertNotNull(store.loadReplay(key))
            assertEquals(20L, replay.throughSeq)
            assertEquals(compacted.map { it.seq }, replay.records.map { it.seq })
        } finally {
            store.closeReplay()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun boundsReplayCacheBeforeReadingOrAppendingIt() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "pi-replay-limit-test-${Random.nextLong().toString(16)}"
        val key = "server_session"
        val store = EntryCacheStore(rootPath = root.toString(), maxReplayBytes = 128L)
        try {
            store.resetReplay(key, encodeReplayCacheHeader("entry-1", 3L))
            assertFailsWith<ReplayCacheCapacityExceededException> {
                store.appendReplay(key, ByteArray(128) { 'x'.code.toByte() })
            }
            store.closeReplay()

            val replayPath = root / "$key.replay.jsonl"
            FileSystem.SYSTEM.write(replayPath) { write(ByteArray(129) { 'x'.code.toByte() }) }
            assertNull(store.loadReplay(key))
            assertFalse(FileSystem.SYSTEM.exists(replayPath))
        } finally {
            store.closeReplay()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun stagesOversizedReplayPayloadFramesTransactionally() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "pi-replay-payload-test-${Random.nextLong().toString(16)}"
        val key = "server_session"
        val store = EntryCacheStore(rootPath = root.toString())
        try {
            store.beginReplayPayload(key, 5L)
            assertEquals(2L, store.appendReplayPayload("he".encodeToByteArray()))
            assertEquals(5L, store.appendReplayPayload("llo".encodeToByteArray()))
            assertContentEquals("hello".encodeToByteArray(), store.finishReplayPayload())
            assertFalse(FileSystem.SYSTEM.exists(root / "$key.replay-payload.stage"))

            store.beginReplayPayload(key, 3L)
            store.appendReplayPayload("ab".encodeToByteArray())
            assertFailsWith<IllegalStateException> { store.finishReplayPayload() }
            store.abortReplayPayload()
            assertFalse(FileSystem.SYSTEM.exists(root / "$key.replay-payload.stage"))

            val staleStage = root / "$key.replay-payload.stage"
            FileSystem.SYSTEM.write(staleStage) { writeUtf8("interrupted") }
            assertNull(store.loadReplay(key))
            assertFalse(FileSystem.SYSTEM.exists(staleStage))
        } finally {
            store.abortReplayPayload()
            store.closeReplay()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    private fun entry(id: String, content: String): ByteArray =
        ("""{"type":"message","id":"$id","message":{"role":"user","content":"$content"}}""" + "\n")
            .encodeToByteArray()

    private fun replayPayload(
        type: String,
        role: String? = null,
        toolCallId: String? = null,
    ) = buildJsonObject {
        put("type", type)
        role?.let { put("message", buildJsonObject { put("role", it) }) }
        toolCallId?.let { put("toolCallId", it) }
    }

    private fun replayRecord(
        seq: Long,
        type: String,
        role: String? = null,
        toolCallId: String? = null,
    ) = CachedReplayRecord(seq, replayPayload(type, role, toolCallId))
}
