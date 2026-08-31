package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputToolEvent
import io.github.stream29.kodex.app.history.contract.item.RequestUserInputHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.RequestUserInputHistoryItemViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class RequestUserInputHistoryItemViewModelImpl(
    override val index: Int,
    private val descriptor: HistoryItemDescriptor,
    private val context: HistoryItemLoadContext,
) : RequestUserInputHistoryItemViewModel, LoadableHistoryItem {
    private val mutableState: MutableStateFlow<RequestUserInputHistoryItemState>
    private val loadingJob: kotlinx.coroutines.Job

    init {
        loadingJob = context.launch(start = CoroutineStart.LAZY) {
            try {
                val event = context.read(descriptor) as? StableRequestUserInputToolEvent
                    ?: error("History item $index is not a request-user-input tool.")
                if (context.isCurrent()) {
                    mutableState.value = RequestUserInputHistoryItemState.Ready(
                        event = event,
                        elapsed = descriptor.elapsed,
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                if (context.isCurrent()) mutableState.value =
                    RequestUserInputHistoryItemState.Failed
            }
        }
        mutableState = MutableStateFlow(RequestUserInputHistoryItemState.Loading(loadingJob))
    }

    override val state: StateFlow<RequestUserInputHistoryItemState> = mutableState.asStateFlow()

    override fun ensureLoaded() {
        loadingJob.start()
    }
}
