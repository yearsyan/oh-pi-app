package io.github.yearsyan.ohpi.chat

import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

internal expect fun entryCacheRootPath(): String

internal const val DefaultMaxReplayCacheBytes = 4L * 1024L * 1024L

internal class ReplayCacheCapacityExceededException(maxBytes: Long) :
    IllegalStateException("replay cache exceeds $maxBytes bytes")

internal data class EntryCacheSnapshot(
    val cursor: String,
    val entries: ByteArray,
)

/** Transactional stable-entry cache plus a recoverable active replay WAL. */
internal class EntryCacheStore(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    rootPath: String = entryCacheRootPath(),
    private val maxReplayBytes: Long = DefaultMaxReplayCacheBytes,
) {
    private val root = rootPath.toPath()
    private var activeKey: String? = null
    private var activeStage: Path? = null
    private var replaySinkKey: String? = null
    private var replaySink: BufferedSink? = null
    private var replaySinkBytes = 0L
    private var replayPayloadKey: String? = null
    private var replayPayloadStage: Path? = null
    private var replayPayloadSink: BufferedSink? = null
    private var replayPayloadExpectedBytes = 0L
    private var replayPayloadReceivedBytes = 0L

    init {
        require(maxReplayBytes > 0L) { "maximum replay cache size must be positive" }
    }

    fun snapshot(key: String): EntryCacheSnapshot {
        requireValidKey(key)
        fileSystem.createDirectories(root)
        recoverFile(dataPath(key), backupPath(key))
        val entries = dataPath(key).takeIf(fileSystem::exists)?.let { path ->
            fileSystem.read(path) { readByteArray() }
        } ?: ByteArray(0)
        return EntryCacheSnapshot(cursor(key), entries)
    }

    fun cursor(key: String): String {
        requireValidKey(key)
        fileSystem.createDirectories(root)
        recoverFile(cursorPath(key), cursorBackupPath(key))
        return cursorPath(key).takeIf(fileSystem::exists)?.let { path ->
            fileSystem.read(path) { readUtf8().trim() }
        }.orEmpty()
    }

    /** Streams and validates the locally committed active-turn replay log for [key]. */
    fun loadReplay(key: String): DecodedReplayCache? {
        requireValidKey(key)
        closeReplaySink()
        fileSystem.createDirectories(root)
        if (replayPayloadKey != key) {
            // A process death may leave an uncommitted oversized payload. It
            // has no cursor checkpoint and is never safe to resume.
            fileSystem.delete(replayPayloadStagePath(key), mustExist = false)
        }
        recoverFile(replayPath(key), replayBackupPath(key))
        val path = replayPath(key)
        if (!fileSystem.exists(path)) return null
        val size = fileSystem.metadata(path).size ?: 0L
        if (size > maxReplayBytes) {
            clearReplay(key)
            return null
        }
        val decoded = fileSystem.read(path) {
            decodeReplayCache(this, maxFrameBytes = maxReplayBytes)
        }
        val cache = decoded.cache
        if (cache == null) {
            clearReplay(key)
            return null
        }
        if (decoded.validBytes < size) {
            val handle = fileSystem.openReadWrite(path)
            try {
                handle.resize(decoded.validBytes)
            } finally {
                handle.close()
            }
        }
        return cache
    }

    /** Replaces the replay log with a new stable-history base and opens it for appends. */
    fun resetReplay(key: String, header: ByteArray) {
        require(header.isNotEmpty()) { "replay cache header is empty" }
        rewriteReplay(key, listOf(header))
    }

    /** Atomically replaces the replay log with already framed [records]. */
    fun rewriteReplay(key: String, records: List<ByteArray>) {
        requireValidKey(key)
        require(records.isNotEmpty()) { "replay cache is empty" }
        check(replayPayloadKey != key) { "cannot rewrite replay cache while a payload is active" }
        closeReplaySink()
        fileSystem.createDirectories(root)
        recoverFile(replayPath(key), replayBackupPath(key))
        val stage = replayStagePath(key)
        fileSystem.delete(stage, mustExist = false)
        try {
            var writtenBytes = 0L
            fileSystem.write(stage) {
                for (record in records) {
                    if (record.isEmpty()) continue
                    if (record.size.toLong() > maxReplayBytes - writtenBytes) {
                        throw ReplayCacheCapacityExceededException(maxReplayBytes)
                    }
                    write(record)
                    writtenBytes += record.size
                }
            }
            replaceWithBackup(stage, replayPath(key), replayBackupPath(key))
        } catch (failure: Throwable) {
            fileSystem.delete(stage, mustExist = false)
            throw failure
        }
        openReplaySink(key)
    }

    /** Appends one complete replay record to the current log. */
    fun appendReplay(key: String, record: ByteArray) {
        requireValidKey(key)
        if (record.isEmpty()) return
        if (replaySinkKey != key || replaySink == null) {
            closeReplaySink()
            check(fileSystem.exists(replayPath(key))) { "replay cache has no base" }
            openReplaySink(key)
        }
        if (record.size.toLong() > maxReplayBytes - replaySinkBytes) {
            throw ReplayCacheCapacityExceededException(maxReplayBytes)
        }
        replaySink?.write(record)
        replaySinkBytes += record.size
    }

    /** Starts a disk-backed transaction for one oversized replay JSON payload. */
    fun beginReplayPayload(key: String, totalBytes: Long) {
        requireValidKey(key)
        require(totalBytes in 1L..Int.MAX_VALUE.toLong()) { "invalid replay payload size" }
        abortReplayPayload()
        fileSystem.createDirectories(root)
        val stage = replayPayloadStagePath(key)
        fileSystem.delete(stage, mustExist = false)
        replayPayloadKey = key
        replayPayloadStage = stage
        replayPayloadExpectedBytes = totalBytes
        replayPayloadReceivedBytes = 0L
        replayPayloadSink = fileSystem.sink(stage).buffer()
    }

    /** Appends one WebSocket Binary frame and returns the cumulative byte count. */
    fun appendReplayPayload(bytes: ByteArray): Long {
        val sink = replayPayloadSink ?: error("replay payload transaction has not begun")
        if (bytes.isEmpty()) return replayPayloadReceivedBytes
        check(bytes.size.toLong() <= replayPayloadExpectedBytes - replayPayloadReceivedBytes) {
            "replay payload exceeds its declared size"
        }
        sink.write(bytes)
        replayPayloadReceivedBytes += bytes.size
        return replayPayloadReceivedBytes
    }

    /** Commits a complete payload into memory for JSON parsing and removes its staging file. */
    fun finishReplayPayload(): ByteArray {
        val stage = replayPayloadStage ?: error("replay payload transaction has not begun")
        val sink = replayPayloadSink ?: error("replay payload transaction has no sink")
        check(replayPayloadReceivedBytes == replayPayloadExpectedBytes) {
            "replay payload ended before its declared size"
        }
        replayPayloadSink = null
        try {
            sink.close()
        } catch (failure: Throwable) {
            clearReplayPayloadState(deleteStage = true)
            throw failure
        }
        return try {
            fileSystem.read(stage) { readByteArray() }
        } finally {
            clearReplayPayloadState(deleteStage = true)
        }
    }

    /** Rolls back an interrupted oversized replay payload. */
    fun abortReplayPayload() {
        runCatching { replayPayloadSink?.close() }
        replayPayloadSink = null
        clearReplayPayloadState(deleteStage = true)
    }

    /** Flushes buffered replay records without closing the active log. */
    fun flushReplay() {
        replaySink?.flush()
    }

    fun closeReplay() {
        closeReplaySink()
    }

    fun begin(key: String, reset: Boolean) {
        requireValidKey(key)
        abort()
        fileSystem.createDirectories(root)
        recoverFile(dataPath(key), backupPath(key))
        val stage = stagePath(key)
        fileSystem.delete(stage, mustExist = false)
        if (!reset && fileSystem.exists(dataPath(key))) {
            fileSystem.copy(dataPath(key), stage)
        } else {
            fileSystem.write(stage) {}
        }
        activeKey = key
        activeStage = stage
    }

    fun append(bytes: ByteArray) {
        val stage = activeStage ?: error("entry cache update has not begun")
        if (bytes.isEmpty()) return
        val sink = fileSystem.appendingSink(stage).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
    }

    fun commit(cursor: String): EntryCacheSnapshot {
        val key = activeKey ?: error("entry cache update has not begun")
        val stage = activeStage ?: error("entry cache update has not begun")
        replaceWithBackup(stage, dataPath(key), backupPath(key))

        val cursorStage = cursorStagePath(key)
        fileSystem.delete(cursorStage, mustExist = false)
        fileSystem.write(cursorStage) { writeUtf8(cursor).writeByte('\n'.code) }
        replaceWithBackup(cursorStage, cursorPath(key), cursorBackupPath(key))

        activeKey = null
        activeStage = null
        return snapshot(key)
    }

    fun abort() {
        activeStage?.let { fileSystem.delete(it, mustExist = false) }
        activeKey = null
        activeStage = null
    }

    fun clear(key: String) {
        requireValidKey(key)
        if (activeKey == key) abort()
        clearReplay(key)
        listOf(
            dataPath(key),
            backupPath(key),
            cursorPath(key),
            cursorBackupPath(key),
            stagePath(key),
            cursorStagePath(key),
        ).forEach { fileSystem.delete(it, mustExist = false) }
    }

    fun clearReplay(key: String) {
        requireValidKey(key)
        if (replaySinkKey == key) closeReplaySink()
        if (replayPayloadKey == key) abortReplayPayload()
        listOf(
            replayPath(key),
            replayBackupPath(key),
            replayStagePath(key),
            replayPayloadStagePath(key),
        ).forEach { fileSystem.delete(it, mustExist = false) }
    }

    private fun openReplaySink(key: String) {
        replaySinkKey = key
        replaySinkBytes = fileSystem.metadata(replayPath(key)).size ?: 0L
        replaySink = fileSystem.appendingSink(replayPath(key)).buffer()
    }

    private fun closeReplaySink() {
        replaySink?.close()
        replaySink = null
        replaySinkKey = null
        replaySinkBytes = 0L
    }

    private fun clearReplayPayloadState(deleteStage: Boolean) {
        val stage = replayPayloadStage
        replayPayloadKey = null
        replayPayloadStage = null
        replayPayloadExpectedBytes = 0L
        replayPayloadReceivedBytes = 0L
        if (deleteStage) stage?.let { fileSystem.delete(it, mustExist = false) }
    }

    private fun replaceWithBackup(source: Path, target: Path, backup: Path) {
        fileSystem.delete(backup, mustExist = false)
        if (fileSystem.exists(target)) fileSystem.atomicMove(target, backup)
        try {
            fileSystem.atomicMove(source, target)
            fileSystem.delete(backup, mustExist = false)
        } catch (failure: Throwable) {
            if (!fileSystem.exists(target) && fileSystem.exists(backup)) {
                fileSystem.atomicMove(backup, target)
            }
            throw failure
        }
    }

    private fun recoverFile(target: Path, backup: Path) {
        if (!fileSystem.exists(target) && fileSystem.exists(backup)) {
            fileSystem.atomicMove(backup, target)
        } else if (fileSystem.exists(target)) {
            fileSystem.delete(backup, mustExist = false)
        }
    }

    private fun requireValidKey(key: String) {
        require(key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "invalid entry cache key"
        }
    }

    private fun dataPath(key: String): Path = root / "$key.entries.jsonl"
    private fun backupPath(key: String): Path = root / "$key.entries.backup"
    private fun stagePath(key: String): Path = root / "$key.entries.stage"
    private fun cursorPath(key: String): Path = root / "$key.cursor"
    private fun cursorBackupPath(key: String): Path = root / "$key.cursor.backup"
    private fun cursorStagePath(key: String): Path = root / "$key.cursor.stage"
    private fun replayPath(key: String): Path = root / "$key.replay.jsonl"
    private fun replayBackupPath(key: String): Path = root / "$key.replay.backup"
    private fun replayStagePath(key: String): Path = root / "$key.replay.stage"
    private fun replayPayloadStagePath(key: String): Path = root / "$key.replay-payload.stage"
}

internal fun entryCacheKey(gateway: String, sessionId: String): String {
    var hash = 0xcbf29ce484222325uL
    for (byte in "$gateway\u0000$sessionId".encodeToByteArray()) {
        hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
    }
    return hash.toString(16)
}

internal fun clearEntryCache(namespace: String, sessionId: String) {
    EntryCacheStore().clear(entryCacheKey(namespace, sessionId))
}
