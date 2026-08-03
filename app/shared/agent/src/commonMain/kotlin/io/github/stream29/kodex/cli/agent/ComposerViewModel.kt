package io.github.stream29.kodex.cli.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Editable, non-persisted draft state owned by one UI target. */
public data class ComposerViewState(
    public val text: String = "",
    public val cursorOffset: Int = text.length,
    public val revision: Long = 0,
) {
    init {
        require(cursorOffset in 0..text.length) { "Cursor offset must be within the composer text." }
    }
}

/**
 * Holds one target's editable composer draft independently from its persisted Agent history.
 *
 * A real Agent runtime and the virtual new-session target each own one instance. The caller must
 * route a taken submission to that same target rather than resolving the currently selected one.
 */
public class ComposerViewModel {
    private val mutableState: MutableStateFlow<ComposerViewState> = MutableStateFlow(ComposerViewState())

    public val state: StateFlow<ComposerViewState> = mutableState.asStateFlow()

    /** Replaces the editable value and advances the draft revision when it actually changes. */
    public fun update(text: String, cursorOffset: Int) {
        require(cursorOffset in 0..text.length) { "Cursor offset must be within the composer text." }
        mutableState.update { current ->
            if (current.text == text && current.cursorOffset == cursorOffset) {
                current
            } else {
                ComposerViewState(
                    text = text,
                    cursorOffset = cursorOffset,
                    revision = current.revision + 1,
                )
            }
        }
    }

    /** Atomically consumes a non-blank text draft and resets the editor for its owner. */
    public fun takeText(): String? {
        while (true) {
            val current = mutableState.value
            val text = current.text.trim()
            if (text.isEmpty()) return null
            val cleared = ComposerViewState(revision = current.revision + 1)
            if (mutableState.compareAndSet(current, cleared)) return text
        }
    }
}
