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

            val replay = assertNotNull(decodeReplayCache(store.replaySnapshot(key)))
            assertEquals("entry-7", replay.baseEntryId)
            assertEquals(40L, replay.baseThroughSeq)
            assertEquals(45L, replay.throughSeq)
            assertEquals(listOf(42L), replay.records.map { it.seq })
            assertEquals("new", replay.records.single().payload["delta"]?.toString()?.trim('"'))

            store.resetReplay(key, encodeReplayCacheHeader("entry-8", 50L))
            val reset = assertNotNull(decodeReplayCache(store.replaySnapshot(key)))
            assertEquals("entry-8", reset.baseEntryId)
            assertEquals(50L, reset.throughSeq)
            assertEquals(emptyList(), reset.records)
        } finally {
            store.abort()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun rejectsIncompleteReplayCacheTail() {
        val valid = encodeReplayCacheHeader("entry-1", 3L)
        assertNull(decodeReplayCache(valid + "{\"kind\":\"event\"".encodeToByteArray()))
    }

    @Test
    fun boundsReplayCacheBeforeReadingOrAppendingIt() {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "pi-replay-limit-test-${Random.nextLong().toString(16)}"
        val key = "server_session"
        val store = EntryCacheStore(rootPath = root.toString(), maxReplayBytes = 128L)
        try {
            store.resetReplay(key, encodeReplayCacheHeader("entry-1", 3L))
            assertFalse(store.canAppendReplayPayload(key, 1L))
            assertFailsWith<ReplayCacheCapacityExceededException> {
                store.appendReplay(key, ByteArray(128) { 'x'.code.toByte() })
            }
            store.closeReplay()

            val replayPath = root / "$key.replay.jsonl"
            FileSystem.SYSTEM.write(replayPath) { write(ByteArray(129) { 'x'.code.toByte() }) }
            assertEquals(0, store.replaySnapshot(key).size)
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
            assertEquals(0, store.replaySnapshot(key).size)
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
}
