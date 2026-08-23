package io.github.stream29.kodex.app.history.contract.item

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/** One completed ordinary tool call which can reveal its complete stable event on demand. */
public interface ToolHistoryItemViewModel : WorkGroupChildHistoryItemViewModel {
    public override val index: Int
    public val state: StateFlow<ToolHistoryItemState>

    /** Requests `Collapsed -> Expanding -> Expanded`; all other states are unchanged. */
    public fun expand()

    /**
     * Requests `Expanding|Expanded -> Collapsed` and releases the detail; other states are
     * unchanged. Collapsing [ToolHistoryItemState.Expanding] also cancels its loading Job.
     */
    public fun collapse()
}

/**
 * Complete state machine for an ordinary tool row.
 *
 * Viewport removal and session switching preserve the current state. Only an explicit collapse
 * releases [Expanded.event].
 */
public sealed interface ToolHistoryItemState {
    /** The one-line header is not available yet. */
    public data class Loading(
        public val loadingJob: Job,
    ) : ToolHistoryItemState

    /** The header is ready and no complete tool payload is retained by this item. */
    public data class Collapsed(
        public val header: ToolHistoryItemHeader,
    ) : ToolHistoryItemState

    /** Detail loading is active while the already-loaded header remains renderable. */
    public data class Expanding(
        public val header: ToolHistoryItemHeader,
        public val loadingJob: Job,
    ) : ToolHistoryItemState

    /** The complete tool event is retained and can be rendered without another storage read. */
    public data class Expanded(
        public val header: ToolHistoryItemHeader,
        public val event: StableCleanEvent.CompletedTool,
    ) : ToolHistoryItemState {
        init {
            require(event.isOrdinaryHistoryToolEvent) {
                "An ordinary tool history item cannot contain a specialized breaker event."
            }
        }
    }

    /** Initial or detail loading failed; the View renders the red error fallback. */
    public data object Failed : ToolHistoryItemState
}

/** Complete one-line header retained in every loaded ordinary-tool state. */
public data class ToolHistoryItemHeader(
    public val summary: String,
    public val status: String,
    public val elapsed: Duration,
) {
    init {
        require(summary.isNotBlank()) { "A tool history summary must not be blank." }
        require(status.isNotBlank()) { "A tool history status must not be blank." }
        requireHistoryItemElapsed(elapsed)
    }
}

private val StableCleanEvent.CompletedTool.isOrdinaryHistoryToolEvent: Boolean
    get() = when (this) {
        is StablePatchToolEvent,
        is StablePlanUpdate,
        is StableRequestUserInputToolEvent,
            -> false

        else -> true
    }
