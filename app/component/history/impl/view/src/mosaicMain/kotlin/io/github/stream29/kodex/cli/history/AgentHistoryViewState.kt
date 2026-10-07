package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.MutableScrollInteractionSource
import io.github.stream29.kodex.cli.components.ScrollInteraction

/**
 * Renderer-local History position and input source, independent of business history ownership.
 *
 * A host may retain this exact instance by exact History ViewModel identity across tab unmounts.
 * It must discard it on binding replacement/close, and must not mount two lists using the same
 * instance simultaneously. Standalone [AgentHistoryView] remembers one by model identity.
 * Unmounting detaches the list's measurement resources; this object owns no coroutine, window,
 * child cache or storage resource and requires no close operation.
 */
public class AgentHistoryViewState {
    // A mount-scoped UI input listener, not retained business state. Disposal removes it before
    // this state can be retained by a tab cache; no model/window remains reachable through it.
    private var inputListener: ((ScrollInteraction) -> Unit)? = null

    /** Position, stable-key anchor and last measured geometry; never part of the ViewModel. */
    public val listState: LazyListState = LazyListState()

    /** Genuine input, focus relocation and programmatic events are classified by the renderer. */
    public val scrollInteractionSource: MutableScrollInteractionSource =
        MutableScrollInteractionSource { interaction -> inputListener?.invoke(interaction) }

    /** Installs only for one exact renderer mount; returns exact-listener disposal. */
    internal fun bindInput(listener: (ScrollInteraction) -> Unit): () -> Unit {
        check(inputListener == null) { "History View state cannot be mounted twice." }
        inputListener = listener
        return { if (inputListener === listener) inputListener = null }
    }
}
