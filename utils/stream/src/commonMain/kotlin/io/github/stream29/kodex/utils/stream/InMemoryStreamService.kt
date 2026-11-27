package io.github.stream29.kodex.utils.stream

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public actual fun InMemoryStreamService(): StreamService = InMemoryStreamServiceImpl()

private class InMemoryStreamServiceImpl : StreamService {
    private val mutex = Mutex()
    private val streams = mutableMapOf<String, Stream>()
    private var lastId = 0uL

    override suspend fun create(key: String) {
        mutex.withLock {
            streams.put(key, Stream())?.delete()
        }
    }

    override suspend fun append(key: String, payload: ByteArray, maxLen: Int?): String {
        validateLimit(maxLen)
        return mutex.withLock {
            val stream = streams.getOrPut(key) { Stream() }
            check(stream.status == Status.Open) { "Stream is complete: $key" }
            check(lastId < ULong.MAX_VALUE) { "Stream entry IDs exhausted." }
            val entry = StoredEntry(++lastId, payload.copyOf())
            stream.entries.addLast(entry)
            if (maxLen != null) {
                while (stream.entries.size > maxLen) stream.entries.removeFirst()
            }
            stream.notifyReaders()
            entry.formattedId
        }
    }

    override suspend fun complete(key: String) {
        mutex.withLock {
            val stream = streams[key] ?: return
            if (stream.status == Status.Open) {
                stream.status = Status.Completed
                stream.notifyReaders()
            }
        }
    }

    override suspend fun delete(key: String) {
        mutex.withLock {
            streams.remove(key)?.delete()
        }
    }

    override suspend fun exists(key: String): Boolean = mutex.withLock { key in streams }

    override suspend fun latest(key: String): StreamEntry? {
        val entry = mutex.withLock { streams[key]?.entries?.lastOrNull() }
        return entry?.snapshot()
    }

    override fun read(key: String, afterId: String?): Flow<StreamEntry> = flow {
        val (stream, startId) = mutex.withLock {
            val stream = streams[key] ?: throw StreamNotFoundException(key)
            val cursor = when (afterId) {
                null -> 0uL
                "$" -> stream.entries.lastOrNull()?.id ?: 0uL
                else -> parseCursor(afterId)
            }
            stream to cursor
        }
        var cursor = startId
        while (true) {
            val revision = stream.revision.value
            val entry = mutex.withLock {
                if (stream.status == Status.Deleted) return@flow
                stream.entryAfter(cursor) ?: run {
                    if (stream.status == Status.Completed) return@flow
                    null
                }
            }
            if (entry == null) {
                // A revision captured before the lookup also observes writes before first() starts.
                stream.revision.first { it !== revision }
            } else {
                cursor = entry.id
                emit(entry.snapshot())
            }
        }
    }
}

private class Stream {
    val entries = ArrayDeque<StoredEntry>()
    val revision = MutableStateFlow(Any())
    var status = Status.Open

    fun entryAfter(cursor: ULong): StoredEntry? {
        val index = entries.binarySearchBy(cursor) { it.id }
        return entries.getOrNull(if (index >= 0) index + 1 else -index - 1)
    }

    fun notifyReaders() {
        revision.value = Any()
    }

    fun delete() {
        status = Status.Deleted
        entries.clear()
        notifyReaders()
    }
}

private enum class Status { Open, Completed, Deleted }

private class StoredEntry(val id: ULong, val payload: ByteArray) {
    val formattedId: String get() = "$id-0"

    fun snapshot(): StreamEntry = StreamEntry(formattedId, payload.copyOf())
}

private fun validateLimit(maxLen: Int?) {
    require(maxLen == null || maxLen > 0) { "Stream maxLen must be positive, got $maxLen." }
}

private fun parseCursor(cursor: String): ULong {
    val time = parseIdPart(cursor.substringBefore('-'))
    val sequence = parseIdPart(cursor.substringAfter('-', "0"))
    require(time != null && sequence != null) { "Invalid stream cursor: $cursor" }
    // All generated IDs have sequence zero, so none can be strictly after a cursor with equal time.
    return time
}

private fun parseIdPart(value: String): ULong? =
    if (value.all { it in '0'..'9' }) value.toULongOrNull() else null
