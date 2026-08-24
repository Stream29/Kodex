package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.contract.IndexVersioned
import io.github.stream29.kodex.agentstorage.contract.prevIndex
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Stable metadata needed to construct one history item without retaining its decoded payload.
 *
 * A descriptor is deliberately smaller than a stable event. It is retained by the history
 * projection and by collapsed work groups; concrete event payloads are owned only by item states.
 */
internal data class HistoryItemDescriptor(
    val index: Int,
    val kind: HistoryItemKind,
    val elapsed: Duration,
)

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
    elapsed: Duration,
): HistoryItemDescriptor = HistoryItemDescriptor(
    index = index,
    elapsed = elapsed,
    kind = when (this) {
        is StableCleanEvent.UserMessage,
        is StableCleanEvent.AssistantMessage,
        is StableCleanEvent.DeveloperMessage,
        is StableCleanEvent.AgentMessage,
            -> HistoryItemKind.Message

        is StableCleanEvent.Reasoning -> HistoryItemKind.Reasoning
        StableCleanEvent.ContextCompaction -> HistoryItemKind.ContextCompaction
        is StableRequestUserInputToolEvent -> HistoryItemKind.RequestUserInput
        is StablePatchToolEvent -> HistoryItemKind.Patch
        is StablePlanUpdate -> HistoryItemKind.PlanUpdate
        is StableCleanEvent.CompletedTool -> HistoryItemKind.Tool
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
) {
    fun launch(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend () -> Unit,
    ): Job = scope.launch(start = start) { block() }

    fun isCurrent(): Boolean = isGenerationCurrent()

    suspend fun read(index: Int): StableCleanEvent = withContext(Dispatchers.Default) {
        agentState.storage.stable[index]
    }

    suspend fun elapsed(index: Int): Duration = withContext(Dispatchers.Default) {
        elapsedBetween(
            previousIndex = agentState.storage.stable.prevIndex(index),
            endIndex = index,
        )
    }

    private suspend fun elapsedBetween(
        previousIndex: Int?,
        endIndex: Int,
    ): Duration {
        val timestamp = agentState.storage.timestamp
        val end = previousIndex?.let { exactTimestamp(timestamp, it) }
            ?.let { start -> exactTimestamp(timestamp, endIndex)?.minus(start) }
            ?: return Duration.ZERO
        return end.takeIf { it >= Duration.ZERO && it.isFinite() } ?: Duration.ZERO
    }

    private suspend fun exactTimestamp(
        timestamp: IndexVersioned<Instant>,
        index: Int,
    ): Instant? {
        if (timestamp.floorToIndex(index) != index) return null
        return timestamp[index]
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
