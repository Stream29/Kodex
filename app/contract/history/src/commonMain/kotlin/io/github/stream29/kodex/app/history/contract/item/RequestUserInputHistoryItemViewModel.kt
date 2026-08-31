package io.github.stream29.kodex.app.history.contract.item

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputToolEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * One completed request-user-input interaction which always renders in full and breaks automatic
 * work folding.
 */
public interface RequestUserInputHistoryItemViewModel : HistoryItemViewModel {
    public val index: Int
    public val state: StateFlow<RequestUserInputHistoryItemState>
}

/**
 * Complete state machine for one completed request-user-input row.
 *
 * Viewport removal and session switching preserve the current state.
 */
public sealed interface RequestUserInputHistoryItemState {
    public data class Loading(
        public val loadingJob: Job,
    ) : RequestUserInputHistoryItemState

    public data class Ready(
        public val event: StableRequestUserInputToolEvent,
        public val elapsed: Duration,
    ) : RequestUserInputHistoryItemState {
        init {
            requireHistoryItemElapsed(elapsed)
        }
    }

    public data object Failed : RequestUserInputHistoryItemState
}
