package io.github.stream29.kodex.app.history.contract.item

import kotlin.time.Duration

/**
 * One stable Reasoning row.
 *
 * Reasoning has no expandable readable detail or loading transition.
 */
public data class ReasoningHistoryItemViewModel(
    public override val index: Int,
    public val elapsed: Duration,
) : WorkGroupChildHistoryItemViewModel {
    init {
        requireHistoryItemIndex(index)
        requireHistoryItemElapsed(elapsed)
    }
}
