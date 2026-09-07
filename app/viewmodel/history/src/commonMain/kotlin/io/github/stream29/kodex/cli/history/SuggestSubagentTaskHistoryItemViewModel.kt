package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.app.history.contract.item.SuggestSubagentTaskHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.SuggestSubagentTaskHistoryItemViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class SuggestSubagentTaskHistoryItemViewModelImpl(
    override val index: Int,
    private val descriptor: HistoryItemDescriptor,
    private val context: HistoryItemLoadContext,
) : SuggestSubagentTaskHistoryItemViewModel, LoadableHistoryItem {
    private val mutableState: MutableStateFlow<SuggestSubagentTaskHistoryItemState>
    private val loadingJob = context.launch(start = CoroutineStart.LAZY) {
        try {
            val event = context.read(descriptor) as? StableSuggestSubagentTaskToolEvent
                ?: error("History item $index is not a Session suggestion.")
            if (context.isCurrent()) {
                mutableState.value = SuggestSubagentTaskHistoryItemState.Ready(event, descriptor.elapsed)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            context.logFailure(descriptor, failure)
            if (context.isCurrent()) mutableState.value = SuggestSubagentTaskHistoryItemState.Failed
        }
    }

    init {
        mutableState = MutableStateFlow(SuggestSubagentTaskHistoryItemState.Loading(loadingJob))
    }

    override val state: StateFlow<SuggestSubagentTaskHistoryItemState> = mutableState.asStateFlow()
    override fun ensureLoaded() { loadingJob.start() }
}
