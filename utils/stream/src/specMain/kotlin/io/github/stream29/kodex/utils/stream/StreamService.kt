package io.github.stream29.kodex.utils.stream

import kotlinx.coroutines.flow.Flow

/** One entry in a stream, with a Redis Streams ID and a binary payload. */
public data class StreamEntry(public val id: String, public val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is StreamEntry && id == other.id && payload.contentEquals(other.payload))

    override fun hashCode(): Int = 31 * id.hashCode() + payload.contentHashCode()
}

/** The stream did not exist when collection began. */
public class StreamNotFoundException(public val key: String) : IllegalStateException("Stream does not exist: $key")

/**
 * Keyed, replayable binary streams with Redis Streams-style IDs, cursors, and trimming.
 * Payloads are copied on append and on delivery to each reader.
 */
public interface StreamService {
    /**
     * Creates an empty open stream. Replaces any existing stream at [key], ending its existing
     * readers as in [delete].
     */
    public suspend fun create(key: String)

    /**
     * Appends to [key], creating it if absent, and returns a strictly increasing entry ID.
     * Trims the oldest entries to [maxLen] for this write when non-null.
     *
     * @throws IllegalArgumentException If [maxLen] is not positive when supplied.
     * @throws IllegalStateException If the stream is complete.
     */
    public suspend fun append(key: String, payload: ByteArray, maxLen: Int? = null): String

    /**
     * Ends a stream without deleting it. Existing and future readers drain retained entries
     * and then finish normally. No effect if the stream is already complete or absent.
     */
    public suspend fun complete(key: String)

    /**
     * Deletes the stream if present. Active readers finish normally without draining unread
     * entries, even if another stream is subsequently created at [key].
     */
    public suspend fun delete(key: String)

    /** Returns whether [key] exists, including completed streams. */
    public suspend fun exists(key: String): Boolean

    /** Returns a copy of the latest entry, or `null` if the stream is absent or empty. */
    public suspend fun latest(key: String): StreamEntry?

    /**
     * Returns a cold flow. A `null` [afterId] replays all retained entries; `"$"` tails entries
     * appended after collection begins; a Redis Streams ID starts strictly after that ID.
     * Each collector follows the stream at [key] when it starts and skips trimmed entries.
     *
     * @throws StreamNotFoundException If [key] is absent when collection begins.
     * @throws IllegalArgumentException If [afterId] is not a valid cursor.
     */
    public fun read(key: String, afterId: String? = null): Flow<StreamEntry>
}

/** Creates a process-local, concurrent [StreamService]. Entry IDs use a logical clock. */
public expect fun InMemoryStreamService(): StreamService
