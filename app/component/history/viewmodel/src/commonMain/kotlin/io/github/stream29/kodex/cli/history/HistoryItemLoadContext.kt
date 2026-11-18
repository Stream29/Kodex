package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReadMedia
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableScheduleEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTerminalExitedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCompletedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCreatedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReasoning
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecution
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCustomTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableJsonTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextTool
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
    val event: AgentCommit,
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

internal fun AgentCommit.toHistoryItemDescriptor(
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
        is StableSubagentCreatedEvent,
        is StableSubagentCompletedEvent,
            -> HistoryItemKind.Message

        is StableAskUserEvent -> HistoryItemKind.RequestUserInput
        is StableReasoning -> HistoryItemKind.Reasoning
        is StableContextCompaction -> HistoryItemKind.ContextCompaction
        is StablePatch -> HistoryItemKind.Patch
        is StablePlanUpdate -> HistoryItemKind.PlanUpdate
        is StableAwait,
        is StableCommandExecution,
        is StableCustomTool,
        is StableImageGenerationCall,
        is StableInvalidToolCall,
        is StableJsonTool,
        is StableReadMedia,
        is StableServerToolSearch,
        is StableTextTool,
        is StableWebSearchCall -> HistoryItemKind.Tool

        else -> throw IllegalArgumentException("Commit ${commitType.wireName} is not a conversation record.")
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

internal fun HistoryItemDescriptor.isFoldable(): Boolean = source == HistoryItemSource.Work

private val historyLogger = KotlinLogging.logger("History")
