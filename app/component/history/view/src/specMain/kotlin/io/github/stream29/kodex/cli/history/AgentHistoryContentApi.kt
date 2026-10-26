package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.*
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel

/** Declares lazy rows, not composed/flattened content. Keys and reuse classes are opaque renderer tokens. */
public interface HistoryListScope {
    public fun Row(key: Any, contentType: Any, content: @Composable () -> Unit)
    /** The renderer calls window[index] only while composing that lazy row; keys use peek. */
    public fun Committed(
        window: HistoryItemWindow, contentType: (HistoryItemViewModel) -> Any,
        content: @Composable (HistoryItemViewModel) -> Unit,
    )
}

public interface HistoryListRenderer : HistoryContentRenderer {
    /** Owns widget state, input classification, real viewport reporting and exact scroll acknowledgment. */
    @Composable public fun List(
        model: AgentHistoryViewModel, window: HistoryItemWindow, followsLatest: Boolean,
        effect: HistoryScrollEffect?, firstCommittedIndex: Int, latestKey: Any?,
        content: (HistoryListScope) -> Unit,
    )
    @Composable public fun StoredItem(
        item: HistoryItemViewModel, generation: Long, shellSessions: AgentShellSessionRegistry?,
    )
    @Composable public fun Marker(value: String, failed: Boolean)
    public fun streamingContentChanged(model: AgentHistoryViewModel)
}

/** Shared row order, stable keys, structural markers and pending/streaming/committed content wiring. */
@Composable
public expect fun AgentHistoryContent(
    model: AgentHistoryViewModel, renderer: HistoryListRenderer, shellSessions: AgentShellSessionRegistry? = null,
)
