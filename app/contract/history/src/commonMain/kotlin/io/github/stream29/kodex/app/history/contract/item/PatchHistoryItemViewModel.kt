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
    public val target: PatchHistoryItemTarget,
    public val status: PatchHistoryItemStatus,
    public val elapsed: Duration,
) {
    init {
        requireHistoryItemElapsed(elapsed)
    }
}

/** File-count information sufficient for a patch's collapsed title. */
public sealed interface PatchHistoryItemTarget {
    public data class SingleFile(
        public val filename: String,
    ) : PatchHistoryItemTarget {
        init {
            require(filename.isNotBlank()) { "A patch filename must not be blank." }
        }
    }

    public data class FileCount(
        public val count: Int,
    ) : PatchHistoryItemTarget {
        init {
            require(count >= 0) { "A patch file count must not be negative." }
        }
    }
}

/** Stable patch completion status used by the collapsed-row renderer. */
public enum class PatchHistoryItemStatus {
    Completed,
    Failed,
}
