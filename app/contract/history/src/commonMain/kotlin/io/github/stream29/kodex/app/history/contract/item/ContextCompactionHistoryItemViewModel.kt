package io.github.stream29.kodex.app.history.contract.item

import kotlin.time.Duration

/** One context-compaction boundary which always breaks work folding. */
public data class ContextCompactionHistoryItemViewModel(
    public val index: Int,
    public val elapsed: Duration,
) : HistoryItemViewModel {
    init {
        requireHistoryItemIndex(index)
        requireHistoryItemElapsed(elapsed)
    }
}
