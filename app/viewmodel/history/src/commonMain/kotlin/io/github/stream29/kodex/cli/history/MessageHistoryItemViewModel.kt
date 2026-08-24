package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class MessageHistoryItemViewModelImpl(
    override val index: Int,
    private val descriptor: HistoryItemDescriptor,
    private val context: HistoryItemLoadContext,
) : MessageHistoryItemViewModel, LoadableHistoryItem {
    private val mutableState: MutableStateFlow<MessageHistoryItemState>
    private val loadingJob: kotlinx.coroutines.Job

    init {
        loadingJob = context.launch(start = CoroutineStart.LAZY) {
            try {
                val event = context.read(index)
                val message = event as? StableCleanEvent.Steerable
                    ?: error("History item $index is not a steerable message.")
                if (context.isCurrent()) {
                    mutableState.value = MessageHistoryItemState.Ready(
                        event = message,
                        elapsed = descriptor.elapsed,
                    )
                }
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (_: Throwable) {
                if (context.isCurrent()) mutableState.value = MessageHistoryItemState.Failed
            }
        }
        mutableState = MutableStateFlow(MessageHistoryItemState.Loading(loadingJob))
    }

    override val state: StateFlow<MessageHistoryItemState> = mutableState.asStateFlow()

    override fun ensureLoaded() {
        loadingJob.start()
    }
}
