package io.github.stream29.kodex.app.history.contract.item

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * One folded run of Reasoning, ordinary-tool, and patch items.
 *
 * The collapsed form retains only its sparse storage range, count, and collapsed state. Concrete
 * nested child ViewModels exist only in [WorkGroupHistoryItemState.Expanded].
 */
public interface WorkGroupHistoryItemViewModel : HistoryItemViewModel {
    /** Non-empty sparse storage span covered by this group, with a non-negative first index. */
    public val indexRange: IntRange

    /** Number of stable items represented by this group; always greater than one. */
    public val itemCount: Int

    public val state: StateFlow<WorkGroupHistoryItemState>

    /** Requests `Collapsed -> Expanding -> Expanded`; all other states are unchanged. */
    public fun expand()

    /**
     * Releases nested child ViewModels via `Expanding|Expanded -> Collapsed`. Collapsing
     * [WorkGroupHistoryItemState.Expanding] also cancels its loading Job.
     */
    public fun collapse()
}

/** Complete state machine for one folded work group. */
public sealed interface WorkGroupHistoryItemState {
    /** The group header is not ready yet. */
    public data class Loading(
        public val loadingJob: Job,
    ) : WorkGroupHistoryItemState

    /** Only [WorkGroupHistoryItemViewModel.indexRange], item count, and this state are retained. */
    public data class Collapsed(
        public val elapsed: Duration,
    ) : WorkGroupHistoryItemState {
        init {
            requireHistoryItemElapsed(elapsed)
        }
    }

    /** Nested child ViewModels are being created while the collapsed header remains renderable. */
    public data class Expanding(
        public val elapsed: Duration,
        public val loadingJob: Job,
    ) : WorkGroupHistoryItemState {
        init {
            requireHistoryItemElapsed(elapsed)
        }
    }

    /**
     * Exact newest-first child ViewModels are published atomically and retained until explicit
     * collapse. Their count and boundary indices match the owning group's public properties.
     */
    public data class Expanded(
        public val children: List<WorkGroupChildHistoryItemViewModel>,
        public val elapsed: Duration,
    ) : WorkGroupHistoryItemState {
        init {
            require(children.size > 1) {
                "An expanded history work group must contain at least two children."
            }
            require(children.zipWithNext().all { (newer, older) ->
                newer.index > older.index
            }) {
                "History work group children must be strictly newest-first."
            }
            requireHistoryItemElapsed(elapsed)
        }
    }

    /** Group-header or nested-child loading failed; the View renders the red error fallback. */
    public data object Failed : WorkGroupHistoryItemState
}
