package io.github.stream29.kodex.app.history.contract.item

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * One answered `ask_user` event (`StableAskUserEvent`) which always renders in full and breaks
 * automatic work folding.
 */
public interface RequestUserInputHistoryItemViewModel : HistoryItemViewModel {
    public val index: Int
    public val state: StateFlow<RequestUserInputHistoryItemState>
}

/**
 * Complete state machine for one answered `ask_user` row.
 *
 * Viewport removal and session switching preserve the current state.
 */
public sealed interface RequestUserInputHistoryItemState {
    public data class Loading(
        public val loadingJob: Job,
    ) : RequestUserInputHistoryItemState

    /** @throws IllegalArgumentException if [elapsed] is negative or infinite. */
    public data class Ready(
        public val event: StableAskUserEvent,
        public val elapsed: Duration,
    ) : RequestUserInputHistoryItemState {
        init {
            requireHistoryItemElapsed(elapsed)
        }
    }

    public data object Failed : RequestUserInputHistoryItemState
}
