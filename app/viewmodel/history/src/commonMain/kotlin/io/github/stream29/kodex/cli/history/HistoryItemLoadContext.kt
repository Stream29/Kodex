package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableReasoning
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
        is StablePatchToolEvent -> HistoryItemKind.Patch
        is StablePlanUpdate -> HistoryItemKind.PlanUpdate
        is StableCleanEvent.CompletedTool -> HistoryItemKind.Tool
        else -> error("Unsupported stable history event: ${this::class.simpleName}")
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
    HistoryItemKind.PlanUpdate,
    HistoryItemKind.ContextCompaction,
        -> false
}
