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

/** Lightweight one-line presentation retained in every loaded ordinary-tool state. */
public sealed interface ToolHistoryItemHeader {
    public val elapsed: Duration

    /** A tool whose collapsed presentation is independent of external live state. */
    public data class Summary(
        public val summary: String,
        public val status: String,
        override val elapsed: Duration,
    ) : ToolHistoryItemHeader {
        init {
            require(summary.isNotBlank()) { "A tool history summary must not be blank." }
            require(status.isNotBlank()) { "A tool history status must not be blank." }
            requireHistoryItemElapsed(elapsed)
        }
    }

    /**
     * A command interaction whose title and color may still depend on a live terminal session.
     *
     * Only the command/session identity and small outcome discriminators are retained. Process
     * output remains exclusive to [ToolHistoryItemState.Expanded].
     */
    public data class CommandExecution(
        public val action: CommandExecutionHistoryAction,
        public val result: CommandExecutionHistoryResult,
        override val elapsed: Duration,
    ) : ToolHistoryItemHeader {
        init {
            requireHistoryItemElapsed(elapsed)
        }
    }
}

public sealed interface CommandExecutionHistoryAction {
    public data class Run(
        public val command: String,
    ) : CommandExecutionHistoryAction

    public data class Interact(
        public val sessionId: Int,
    ) : CommandExecutionHistoryAction {
        init {
            require(sessionId >= 0) { "A terminal session id must be non-negative." }
        }
    }

    public data class Wait(
        public val sessionId: Int,
    ) : CommandExecutionHistoryAction {
        init {
            require(sessionId >= 0) { "A terminal session id must be non-negative." }
        }
    }
}

public sealed interface CommandExecutionHistoryResult {
    public data class Output(
        public val exitCode: Int?,
        public val sessionId: Int?,
    ) : CommandExecutionHistoryResult {
        init {
            require(sessionId == null || sessionId >= 0) {
                "A terminal session id must be null or non-negative."
            }
        }
    }

    public data object Failure : CommandExecutionHistoryResult
}

private val StableCleanEvent.CompletedTool.isOrdinaryHistoryToolEvent: Boolean
    get() = when (this) {
        is StablePatchToolEvent,
        is StablePlanUpdate,
        is StableRequestUserInputToolEvent,
            -> false

        else -> true
    }
