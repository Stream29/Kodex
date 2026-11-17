package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.app.history.contract.item.RequestUserInputHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.RequestUserInputHistoryItemViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class RequestUserInputHistoryItemViewModelImpl(
    override val index: Int,
    private val descriptor: HistoryItemDescriptor,
    private val context: HistoryItemLoadContext,
) : RequestUserInputHistoryItemViewModel, ReleasableHistoryItem {
    private val mutableState: MutableStateFlow<RequestUserInputHistoryItemState> = MutableStateFlow(
        try {
            val event = descriptor.event as? StableAskUserEvent
                ?: error("History item $index is not an ask_user event.")
            RequestUserInputHistoryItemState.Ready(
                event = event,
                elapsed = descriptor.elapsed,
            )
        } catch (failure: Throwable) {
            context.logFailure(descriptor, failure)
            RequestUserInputHistoryItemState.Failed
        },
    )

    override val state: StateFlow<RequestUserInputHistoryItemState> = mutableState.asStateFlow()

    override fun release() = Unit
}
