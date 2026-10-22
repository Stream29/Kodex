@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.MutableScrollInteractionSource
import io.github.stream29.kodex.cli.components.ScrollInteraction

public actual class AgentHistoryViewState actual constructor() {
    // A mount-scoped UI input listener, not retained business state. Disposal removes it before
    // this state can be retained by a tab cache; no model/window remains reachable through it.
    private var inputListener: ((ScrollInteraction) -> Unit)? = null

    public actual val listState: LazyListState = LazyListState()

    public actual val scrollInteractionSource: MutableScrollInteractionSource =
        MutableScrollInteractionSource { interaction -> inputListener?.invoke(interaction) }

    /** Installs only for one exact renderer mount; returns exact-listener disposal. */
    internal fun bindInput(listener: (ScrollInteraction) -> Unit): () -> Unit {
        check(inputListener == null) { "History View state cannot be mounted twice." }
        inputListener = listener
        return { if (inputListener === listener) inputListener = null }
    }
}
