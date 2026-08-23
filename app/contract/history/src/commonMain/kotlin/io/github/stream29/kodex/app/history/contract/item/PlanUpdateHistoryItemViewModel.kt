package io.github.stream29.kodex.app.history.contract.item

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/** One completed plan update which always renders its full plan and breaks work folding. */
public interface PlanUpdateHistoryItemViewModel : HistoryItemViewModel {
    public val index: Int
    public val state: StateFlow<PlanUpdateHistoryItemState>
}

/** Complete state machine for one plan-update row. */
public sealed interface PlanUpdateHistoryItemState {
    public data class Loading(
        public val loadingJob: Job,
    ) : PlanUpdateHistoryItemState

    public data class Ready(
        public val event: StablePlanUpdate,
        public val elapsed: Duration,
    ) : PlanUpdateHistoryItemState {
        init {
            requireHistoryItemElapsed(elapsed)
        }
    }

    public data object Failed : PlanUpdateHistoryItemState
}
