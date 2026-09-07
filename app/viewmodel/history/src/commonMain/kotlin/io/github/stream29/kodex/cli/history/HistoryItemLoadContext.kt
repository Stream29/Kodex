package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableReasoning
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCustomToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageGenerationToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableImageViewToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableJsonToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableMcpToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableToolSearchEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchToolEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/**
 * Stable metadata needed to construct one history item without retaining its decoded payload.
 *
 * A descriptor is deliberately smaller than a stable event. It is retained by the history
 * projection and by collapsed work groups; concrete event payloads are owned only by item states.
 */
internal data class HistoryItemDescriptor(
    val index: Int,
    val source: HistoryItemSource,
    val kind: HistoryItemKind,
    val elapsed: Duration,
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
    SuggestSubagentTask,
    PlanUpdate,
    ContextCompaction,
}

internal fun StableCleanEvent.toHistoryItemDescriptor(
    index: Int,
    source: HistoryItemSource,
    elapsed: Duration,
): HistoryItemDescriptor = HistoryItemDescriptor(
    index = index,
    source = source,
    elapsed = elapsed,
    kind = when (this) {
        is StableUserMessage,
        is StableAssistantMessage,
        is StableDeveloperMessage,
        is StableAgentMessage,
            -> HistoryItemKind.Message

        is StableReasoning -> HistoryItemKind.Reasoning
        is StableContextCompaction -> HistoryItemKind.ContextCompaction
        is StableRequestUserInputToolEvent -> HistoryItemKind.RequestUserInput
        is StableSuggestSubagentTaskToolEvent -> HistoryItemKind.SuggestSubagentTask
        is StablePatchToolEvent -> HistoryItemKind.Patch
        is StablePlanUpdate -> HistoryItemKind.PlanUpdate
        is StableCommandExecutionToolEvent,
        is StableCustomToolEvent,
        is StableImageGenerationCall,
        is StableImageGenerationToolEvent,
        is StableImageViewToolEvent,
        is StableInvalidToolCall,
        is StableJsonToolEvent,
        is StableMcpToolEvent,
        is StableServerToolSearch,
        is StableTextToolEvent,
        is StableToolSearchEvent,
        is StableWebSearchCall,
        is StableWebSearchToolEvent -> HistoryItemKind.Tool
    },
)

/**
 * Storage and lifecycle boundary shared by every item VM owned by one history VM.
 *
 * There is intentionally no semaphore here. Each item has one persistent state machine, so a
 * second viewport entry cannot create a duplicate read. The storage implementation already owns
 * its value cache and all reads are dispatched away from the UI thread.
 */
internal class HistoryItemLoadContext(
    private val agentState: KodexAgentState,
    private val scope: CoroutineScope,
    private val isGenerationCurrent: () -> Boolean,
    private val turnDurationResolver: HistoryTurnDurationResolver,
) {
    fun launch(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend () -> Unit,
    ): Job = scope.launch(start = start) { block() }

    fun isCurrent(): Boolean = isGenerationCurrent()

    fun logFailure(descriptor: HistoryItemDescriptor, failure: Throwable) {
        historyLogger.error(failure) {
            "Failed to load History ${descriptor.source} entry ${descriptor.index} from ${agentState.storage.uri}."
        }
    }

    suspend fun read(descriptor: HistoryItemDescriptor): StableCleanEvent =
        withContext(Dispatchers.Default) {
            (when (descriptor.source) {
                HistoryItemSource.Index -> agentState.storage.index.getExact(descriptor.index)
                HistoryItemSource.Work -> agentState.storage.work.getExact(descriptor.index)
            } as? StableCleanEvent) ?: error(
                "History item ${descriptor.source} entry ${descriptor.index} is missing.",
            )
        }

    suspend fun finalTurnDuration(index: Int): Duration? =
        withContext(Dispatchers.Default) {
            turnDurationResolver.finalDuration(index)
        }
}

/**
 * Internal loading hook used by [HistoryItemWindow]. It is not part of the public contract because
 * the View should not know how a particular item starts its work.
 */
internal interface LoadableHistoryItem {
    fun ensureLoaded()
}

/** Cancels work and releases detail held by a group-local child that is about to be dropped. */
internal interface ReleasableHistoryItem {
    fun release()
}

internal fun HistoryItemViewModel.ensureLoaded() {
    (this as? LoadableHistoryItem)?.ensureLoaded()
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
    HistoryItemKind.SuggestSubagentTask,
    HistoryItemKind.PlanUpdate,
    HistoryItemKind.ContextCompaction,
        -> false
}

private val historyLogger = KotlinLogging.logger("History")
