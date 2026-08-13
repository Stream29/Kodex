package io.github.stream29.kodex.app.agent.contract

import kotlinx.coroutines.flow.StateFlow

/** Atomic editable value owned by one Agent or New Session target. */
public data class ComposerState(
    public val text: String = "",
    public val cursorOffset: Int = text.length,
    public val revision: Long = 0,
) {
    init {
        require(cursorOffset in 0..text.length) {
            "Cursor offset must be within the composer text."
        }
        require(revision >= 0) { "A composer revision must not be negative." }
    }
}

/**
 * Editable, non-persisted composer draft for one stable owner.
 *
 * Consuming a draft is deliberately absent from this child contract. The
 * owning Agent or New Session command must bind owner identity and revision to
 * the submission atomically.
 */
public interface ComposerViewModel : AutoCloseable {
    public val state: StateFlow<ComposerState>

    /**
     * Replaces text and cursor, returning the resulting revision.
     *
     * An unchanged value retains its current revision.
     */
    public fun update(
        text: String,
        cursorOffset: Int,
    ): Long

    /** Clears the draft only when [expectedRevision] is still current. */
    public fun clear(expectedRevision: Long): Boolean

    override fun close(): Unit
}

/** Creates an independently owned composer child. */
public fun interface ComposerViewModelFactory {
    public fun create(): ComposerViewModel
}
