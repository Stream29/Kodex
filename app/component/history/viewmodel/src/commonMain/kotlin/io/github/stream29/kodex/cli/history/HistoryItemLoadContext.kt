package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMediaToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableScheduleEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTerminalExitedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReasoning
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableJsonToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchCall
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Stable metadata and already-fetched commit payload needed to construct one history item
 * without a second storage round-trip.
 */
internal data class HistoryItemDescriptor(
    val index: Int,
    val source: HistoryItemSource,
    val kind: HistoryItemKind,
    val elapsed: Duration,
    val event: StableCleanEvent,
    val createdAt: Instant = Instant.DISTANT_PAST,
    val turnDuration: Duration? = null,
)

internal enum class HistoryItemSource {
    Index,
    Work,
}

internal enum class HistoryItemKind {
    Message,
    Reasoning,
    Tool,
    Patch,
    RequestUserInput,
    PlanUpdate,
    ContextCompaction,
}

internal fun StableCleanEvent.toHistoryItemDescriptor(
    index: Int,
    source: HistoryItemSource,
    elapsed: Duration,
    createdAt: Instant = Instant.DISTANT_PAST,
    turnDuration: Duration? = null,
): HistoryItemDescriptor = HistoryItemDescriptor(
    index = index,
    source = source,
    elapsed = elapsed,
    event = this,
    createdAt = createdAt,
    turnDuration = turnDuration,
    kind = when (this) {
        is StableUserMessage,
        is StableAssistantMessage,
        is StableDeveloperMessage,
        is StableAgentMessage,
        is StableScheduleEvent,
        is StableTerminalExitedEvent,
            -> HistoryItemKind.Message

        is StableAskUserEvent -> HistoryItemKind.RequestUserInput
        is StableReasoning -> HistoryItemKind.Reasoning
        is StableContextCompaction -> HistoryItemKind.ContextCompaction
        is StablePatchToolEvent -> HistoryItemKind.Patch
        is StablePlanUpdate -> HistoryItemKind.PlanUpdate
        is StableAwaitEventToolEvent,
        is StableCommandExecutionToolEvent,
        is StableCustomToolEvent,
        is StableImageGenerationCall,
        is StableInvalidToolCall,
        is StableJsonToolEvent,
        is StableReadMediaToolEvent,
        is StableServerToolSearch,
        is StableTextToolEvent,
        is StableWebSearchCall -> HistoryItemKind.Tool
    },
)

/**
 * Storage and lifecycle boundary shared by every item VM owned by one history VM.
 */
internal class HistoryItemLoadContext(
    private val agentState: AgentHistorySource,
    private val scope: CoroutineScope,
    private val isGenerationCurrent: () -> Boolean,
) {
    fun launch(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend () -> Unit,
    ): Job = scope.launch(start = start) { block() }

    fun isCurrent(): Boolean = isGenerationCurrent()

    fun logFailure(descriptor: HistoryItemDescriptor, failure: Throwable) {
        historyLogger.error(failure) {
            "Failed to load History ${descriptor.source} entry ${descriptor.index} from agent ${agentState.storage.agentId}."
        }
    }
}

/** Cancels work and releases detail held by a group-local child that is about to be dropped. */
internal interface ReleasableHistoryItem {
    fun release()
}

internal fun HistoryItemViewModel.release() {
    (this as? ReleasableHistoryItem)?.release()
}

internal fun HistoryItemDescriptor.isFoldable(): Boolean = when (kind) {
    HistoryItemKind.Reasoning,
    HistoryItemKind.Tool,
    HistoryItemKind.Patch,
        -> true

    HistoryItemKind.Message,
    HistoryItemKind.RequestUserInput,
    HistoryItemKind.PlanUpdate,
    HistoryItemKind.ContextCompaction,
        -> false
}

private val historyLogger = KotlinLogging.logger("History")
