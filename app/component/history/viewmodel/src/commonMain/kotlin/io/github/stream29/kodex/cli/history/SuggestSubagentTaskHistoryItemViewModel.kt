package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.app.history.contract.item.SuggestSubagentTaskHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.SuggestSubagentTaskHistoryItemViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class SuggestSubagentTaskHistoryItemViewModelImpl(
    override val index: Int,
    private val descriptor: HistoryItemDescriptor,
    private val context: HistoryItemLoadContext,
) : SuggestSubagentTaskHistoryItemViewModel, LoadableHistoryItem, ReleasableHistoryItem {
    private val mutableState: MutableStateFlow<SuggestSubagentTaskHistoryItemState> = MutableStateFlow(
        try {
            val event = descriptor.event as? StableSuggestSubagentTaskToolEvent
                ?: error("History item $index is not a Session suggestion.")
            SuggestSubagentTaskHistoryItemState.Ready(event, descriptor.elapsed)
        } catch (failure: Throwable) {
            context.logFailure(descriptor, failure)
            SuggestSubagentTaskHistoryItemState.Failed
        },
    )

    override val state: StateFlow<SuggestSubagentTaskHistoryItemState> = mutableState.asStateFlow()

    override fun ensureLoaded() = Unit

    override fun release() = Unit
}
