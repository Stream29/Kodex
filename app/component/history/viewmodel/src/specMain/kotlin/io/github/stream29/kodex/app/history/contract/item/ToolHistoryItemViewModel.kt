package io.github.stream29.kodex.app.history.contract.item

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecution
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCustomTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableJsonTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMedia
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchCall
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/** One completed ordinary tool call which can reveal its complete stable event on demand. */
public interface ToolHistoryItemViewModel : WorkGroupChildHistoryItemViewModel {
    public override val index: Int
    public val state: StateFlow<ToolHistoryItemState>

    /** Requests `Collapsed -> Expanded`; all other states are unchanged. */
    public fun expand()

    /** Requests `Expanded -> Collapsed` and releases the detail; other states are unchanged. */
    public fun collapse()
}

/**
 * Complete state machine for an ordinary tool row.
 *
 * Viewport removal and session switching preserve the current state. Only an explicit collapse
 * releases [Expanded.event].
 */
public sealed interface ToolHistoryItemState {
    /** The header is ready and no complete tool payload is retained by this item. */
    public data class Collapsed(
        public val header: ToolHistoryItemHeader,
    ) : ToolHistoryItemState

    /**
     * The complete tool event is retained and can be rendered without another storage read.
     * @throws IllegalArgumentException if [event] belongs to a specialized item instead of an
     * ordinary tool (for example Patch or Plan Update).
     */
    public data class Expanded(
        public val header: ToolHistoryItemHeader,
        public val event: AgentCommit.CompletedTool,
    ) : ToolHistoryItemState {
        init {
            require(event.isOrdinaryHistoryToolEvent) {
                "An ordinary tool history item cannot contain a specialized breaker event."
            }
        }
    }

    /** Header decoding failed; the View renders the red error fallback. */
    public data object Failed : ToolHistoryItemState
}

/** Lightweight one-line presentation retained in every loaded ordinary-tool state. */
public sealed interface ToolHistoryItemHeader {
    public val elapsed: Duration

    /**
     * A tool whose collapsed presentation is independent of external live state.
     * @throws IllegalArgumentException if [summary]/[status] is blank or [elapsed] is negative/infinite.
     */
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
     * A recorded command interaction whose title and color are independent of live processes.
     *
     * Only the command/session identity and small outcome discriminators are retained. Process
     * output remains exclusive to [ToolHistoryItemState.Expanded]. Session ids are retained
     * as recorded identity only, not shown or used to supplement the completed row.
     * @throws IllegalArgumentException if [elapsed] is negative or infinite.
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
    /** Single-line display name from this call only; empty for an unnamed historical call. */
    public data class Run(
        public val intentName: String,
    ) : CommandExecutionHistoryAction

    /** @throws IllegalArgumentException if [sessionId] is negative. */
    public data class Interact(
        public val sessionId: Int,
    ) : CommandExecutionHistoryAction {
        init {
            require(sessionId >= 0) { "A terminal session id must be non-negative." }
        }
    }

    /** @throws IllegalArgumentException if [sessionId] is negative. */
    public data class Wait(
        public val sessionId: Int,
    ) : CommandExecutionHistoryAction {
        init {
            require(sessionId >= 0) { "A terminal session id must be non-negative." }
        }
    }
}

public sealed interface CommandExecutionHistoryResult {
    /** @throws IllegalArgumentException if [sessionId] is non-null and negative. */
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

/** Exhaustive classification shared by the loader and its expanded-state invariant. */
public val AgentCommit.CompletedTool.isOrdinaryHistoryToolEvent: Boolean
    get() = when (this) {
        is StablePatch,
        is StablePlanUpdate,
            -> false

        is StableAwait,
        is StableCommandExecution,
        is StableCustomTool,
        is StableImageGenerationCall,
        is StableInvalidToolCall,
        is StableJsonTool,
        is StableReadMedia,
        is StableServerToolSearch,
        is StableTextTool,
        is StableWebSearchCall -> true
    }
