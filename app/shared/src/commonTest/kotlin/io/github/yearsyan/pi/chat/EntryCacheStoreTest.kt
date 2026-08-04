package io.github.yearsyan.pi.chat

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    private fun entry(id: String, content: String): ByteArray =
        ("""{"type":"message","id":"$id","message":{"role":"user","content":"$content"}}""" + "\n")
            .encodeToByteArray()
}
