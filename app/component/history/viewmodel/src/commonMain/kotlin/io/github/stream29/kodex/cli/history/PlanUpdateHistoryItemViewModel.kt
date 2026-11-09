package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.app.history.contract.item.PlanUpdateHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.PlanUpdateHistoryItemViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class PlanUpdateHistoryItemViewModelImpl(
    override val index: Int,
    private val descriptor: HistoryItemDescriptor,
    private val context: HistoryItemLoadContext,
) : PlanUpdateHistoryItemViewModel, LoadableHistoryItem, ReleasableHistoryItem {
    private val mutableState: MutableStateFlow<PlanUpdateHistoryItemState> = MutableStateFlow(
        try {
            val event = descriptor.event as? StablePlanUpdate
                ?: error("History item $index is not a plan update.")
            PlanUpdateHistoryItemState.Ready(
                event = event,
                elapsed = descriptor.elapsed,
            )
        } catch (failure: Throwable) {
            context.logFailure(descriptor, failure)
            PlanUpdateHistoryItemState.Failed
        },
    )

    override val state: StateFlow<PlanUpdateHistoryItemState> = mutableState.asStateFlow()

    override fun ensureLoaded() = Unit

    override fun release() = Unit
}
