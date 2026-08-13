package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.app.agent.contract.ComposerState
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerViewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds one target's editable composer draft independently from its persisted Agent history.
 *
 * Consuming text is internal because only the owning Agent or New Session may bind
 * the captured revision to a materialization or submission command.
 */
private class ComposerViewModelImpl : ComposerViewModel {
    private val mutableState: MutableStateFlow<ComposerState> = MutableStateFlow(ComposerState())

    override val state: StateFlow<ComposerState> = mutableState.asStateFlow()

    /** Replaces the editable value and advances the draft revision when it actually changes. */
    override fun update(text: String, cursorOffset: Int): Long {
        require(cursorOffset in 0..text.length) { "Cursor offset must be within the composer text." }
        while (true) {
            val current = mutableState.value
            if (current.text == text && current.cursorOffset == cursorOffset) return current.revision
            val updated = ComposerState(
                text = text,
                cursorOffset = cursorOffset,
                revision = current.revision + 1,
            )
            if (mutableState.compareAndSet(current, updated)) return updated.revision
        }
    }

    override fun clear(expectedRevision: Long): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.revision != expectedRevision) return false
            if (current.text.isEmpty() && current.cursorOffset == 0) return true
            val cleared = ComposerState(revision = current.revision + 1)
            if (mutableState.compareAndSet(current, cleared)) return true
        }
    }

    override fun close() {
        // This child owns no coroutine or external resource.
    }
}

public object DefaultComposerViewModelFactory : ComposerViewModelFactory {
    override fun create(): ComposerViewModel = ComposerViewModelImpl()
}
