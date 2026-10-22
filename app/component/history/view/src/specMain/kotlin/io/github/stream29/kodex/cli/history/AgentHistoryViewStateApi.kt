@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.MutableScrollInteractionSource

/**
 * Renderer-local History position and input source, independent of business history ownership.
 *
 * A host may retain this exact instance by exact History ViewModel identity across tab unmounts.
 * It must discard it on binding replacement/close, and must not mount two lists using the same
 * instance simultaneously. Standalone [AgentHistoryView] remembers one by model identity.
 * Unmounting detaches the list's measurement resources; this object owns no coroutine, window,
 * child cache or storage resource and requires no close operation.
 */
public expect class AgentHistoryViewState() {
    /** Position, stable-key anchor and last measured geometry; never part of the ViewModel. */
    public val listState: LazyListState

    /** Genuine input, focus relocation and programmatic events are classified by the renderer. */
    public val scrollInteractionSource: MutableScrollInteractionSource
}
