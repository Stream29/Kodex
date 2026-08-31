package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StablePlanUpdate
import io.github.stream29.kodex.app.history.contract.item.PlanUpdateHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.PlanUpdateHistoryItemViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class PlanUpdateHistoryItemViewModelImpl(
    override val index: Int,
    private val descriptor: HistoryItemDescriptor,
    private val context: HistoryItemLoadContext,
) : PlanUpdateHistoryItemViewModel, LoadableHistoryItem {
    private val mutableState: MutableStateFlow<PlanUpdateHistoryItemState>
    private val loadingJob: kotlinx.coroutines.Job

    init {
        loadingJob = context.launch(start = CoroutineStart.LAZY) {
            try {
                val event = context.read(descriptor) as? StablePlanUpdate
                    ?: error("History item $index is not a plan update.")
                if (context.isCurrent()) {
                    mutableState.value = PlanUpdateHistoryItemState.Ready(
                        event = event,
                        elapsed = descriptor.elapsed,
                    )
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                if (context.isCurrent()) mutableState.value = PlanUpdateHistoryItemState.Failed
            }
        }
        mutableState = MutableStateFlow(PlanUpdateHistoryItemState.Loading(loadingJob))
    }

    override val state: StateFlow<PlanUpdateHistoryItemState> = mutableState.asStateFlow()

    override fun ensureLoaded() {
        loadingJob.start()
    }
}
