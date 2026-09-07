package io.github.stream29.kodex.app.history.contract.item

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/** Completed suggestion: an independent, read-only Index item, never a work-group child. */
public interface SuggestSubagentTaskHistoryItemViewModel : HistoryItemViewModel {
    public val index: Int
    public val state: StateFlow<SuggestSubagentTaskHistoryItemState>
}

public sealed interface SuggestSubagentTaskHistoryItemState {
    public data class Loading(public val loadingJob: Job) : SuggestSubagentTaskHistoryItemState
    public data class Ready(
        public val event: StableSuggestSubagentTaskToolEvent,
        public val elapsed: Duration,
    ) : SuggestSubagentTaskHistoryItemState {
        init {
            requireHistoryItemElapsed(elapsed)
        }
    }
    public data object Failed : SuggestSubagentTaskHistoryItemState
}
