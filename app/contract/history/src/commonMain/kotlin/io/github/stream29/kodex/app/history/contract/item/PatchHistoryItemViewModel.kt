package io.github.stream29.kodex.app.history.contract.item

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/** One completed apply-patch interaction. */
public interface PatchHistoryItemViewModel : WorkGroupChildHistoryItemViewModel {
    public override val index: Int
    public val state: StateFlow<PatchHistoryItemState>

    /** Requests `Collapsed -> Expanding -> Expanded`; all other states are unchanged. */
    public fun expand()

    /**
     * Requests `Expanding|Expanded -> Collapsed` and releases the detail; other states are
     * unchanged. Collapsing [PatchHistoryItemState.Expanding] also cancels its loading Job.
     */
    public fun collapse()
}

/**
 * Complete state machine for one patch row.
 *
 * Viewport removal and session switching preserve the current state. Only an explicit collapse
 * releases [Expanded.event].
 */
public sealed interface PatchHistoryItemState {
    public data class Loading(
        public val loadingJob: Job,
    ) : PatchHistoryItemState

    public data class Collapsed(
        public val header: PatchHistoryItemHeader,
    ) : PatchHistoryItemState

    public data class Expanding(
        public val header: PatchHistoryItemHeader,
        public val loadingJob: Job,
    ) : PatchHistoryItemState

    public data class Expanded(
        public val header: PatchHistoryItemHeader,
        public val event: StablePatchToolEvent,
    ) : PatchHistoryItemState

    public data object Failed : PatchHistoryItemState
}

/** Complete one-line header retained in every loaded patch state. */
public data class PatchHistoryItemHeader(
    public val summary: String,
    public val status: PatchHistoryItemStatus,
    public val elapsed: Duration,
) {
    init {
        require(summary.isNotBlank()) { "A patch history summary must not be blank." }
        requireHistoryItemElapsed(elapsed)
    }
}

/** Stable patch completion status used by the collapsed-row renderer. */
public enum class PatchHistoryItemStatus {
    Completed,
    Failed,
}
