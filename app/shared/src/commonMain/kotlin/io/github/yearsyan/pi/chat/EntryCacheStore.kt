package io.github.yearsyan.pi.chat

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

internal expect fun entryCacheRootPath(): String

internal data class EntryCacheSnapshot(
    val cursor: String,
    val entries: ByteArray,
)

/** Transactional, file-backed stable-entry cache for one app installation. */
internal class EntryCacheStore(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    rootPath: String = entryCacheRootPath(),
) {
    private val root = rootPath.toPath()
    private var activeKey: String? = null
    private var activeStage: Path? = null

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
        listOf(
            dataPath(key),
            backupPath(key),
            cursorPath(key),
            cursorBackupPath(key),
            stagePath(key),
            cursorStagePath(key),
        ).forEach { fileSystem.delete(it, mustExist = false) }
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
