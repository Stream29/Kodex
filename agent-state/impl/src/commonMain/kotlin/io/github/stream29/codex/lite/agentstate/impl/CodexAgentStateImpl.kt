package io.github.stream29.codex.lite.agentstate.impl

import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateEnum
import io.github.stream29.codex.lite.agentstate.contract.MutableCodexAgentState
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.agentstorage.contract.CodexAgentStorage
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.openai.CompactionInput
import io.github.stream29.codex.lite.openai.OpenAiErrorResponse
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Phase
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Reason
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Request
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Trigger
import io.github.stream29.codex.lite.openai.ResponseError
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Loads a state-layer implementation from [storage].
 *
 * Construction is suspend because [CodexAgentStorage.latestIndex] may require
 * asynchronous storage access.
 */
public suspend fun CodexAgentStateImpl(
    client: OpenAiClient,
    storage: MutableCodexAgentStorage,
): CodexAgentStateImpl =
    CodexAgentStateImpl(
        client = client,
        storage = storage,
        loadedLatestIndex = storage.latestIndex(),
    )

/**
 * Minimal state-layer implementation of a Codex agent thread.
 *
 * This class owns model-request projection, context compaction, and storage
 * maintenance. It records model-emitted tool calls, but it does not execute
 * tool effects; that belongs to AgentRuntime.
 */
public class CodexAgentStateImpl internal constructor(
    private val client: OpenAiClient,
    override val storage: MutableCodexAgentStorage,
    loadedLatestIndex: Int,
) : MutableCodexAgentState {
    private val mutableState = MutableStateFlow<CodexAgentStateEnum>(CodexAgentStateEnum.Idle)
    private var nextTurnOrdinal: Long = 0
    public override val state: StateFlow<CodexAgentStateEnum> = mutableState
    public override val latestIndex: StateFlow<Int>
        field = MutableStateFlow(loadedLatestIndex)

    public override fun resume(): Flow<ResponsesStreamEvent> = flow {
        mutate(CodexAgentStateEnum.LlmRequest.Response) {
            check(storage.latestIndex() >= 0) { "Cannot resume an agent without initial state." }
            if (shouldAutoCompact(storage.latestIndex())) {
                runAutoCompact(
                    trigger = RemoteCompactionV2Trigger.Auto,
                    reason = RemoteCompactionV2Reason.ContextLimit,
                    phase = RemoteCompactionV2Phase.PreTurn,
                )
            }

            while (true) {
                val snapshotIndex = storage.latestIndex()
                val settings = storage.settings[snapshotIndex]
                val input = storage.modelInputAt(snapshotIndex)
                var needsFollowUp = false

                client.createResponse(settings.toRequest(input)).collect { event ->
                    when (event) {
                        is ResponsesStreamEvent.OutputItemDone -> {
                            val historyItem = event.item as? ResponseItem.HistoryItem
                            if (historyItem != null) {
                                appendHistoryItem(historyItem, now(), tokenCount = null)
                            }
                        }

                        is ResponsesStreamEvent.Completed -> {
                            event.response.usage?.totalTokens?.let { tokenCount ->
                                publishStorageTransition {
                                    val index = storage.latestIndex() + 1
                                    storage.tokenCount[index] = tokenCount
                                    storage.timestamp[index] = now()
                                    latestIndex.value = index
                                }
                            }
                            if (event.response.endTurn == false) {
                                needsFollowUp = true
                            }
                        }

                        is ResponsesStreamEvent.Failed -> {
                            emit(event)
                            throw CodexResponseStreamFailureException(event.response.error)
                        }

                        else -> Unit
                    }
                    emit(event)
                }

                if (!needsFollowUp) {
                    break
                }
                if (shouldAutoCompact(storage.latestIndex())) {
                    runAutoCompact(
                        trigger = RemoteCompactionV2Trigger.Auto,
                        reason = RemoteCompactionV2Reason.ContextLimit,
                        phase = RemoteCompactionV2Phase.MidTurn,
                    )
                }
            }
        }
    }

    public override suspend fun forcedCompact(): Int =
        mutate(CodexAgentStateEnum.LlmRequest.Compact) {
            runAutoCompact(
                trigger = RemoteCompactionV2Trigger.Manual,
                reason = RemoteCompactionV2Reason.UserRequested,
                phase = RemoteCompactionV2Phase.StandaloneTurn,
            )
        }

    public override suspend fun appendResponseItem(
        item: ResponseItem.HistoryItem,
        timestamp: Instant,
        tokenCount: Long?,
    ): Int =
        mutate(CodexAgentStateEnum.ExternalWrite) {
            appendHistoryItem(item, timestamp, tokenCount)
        }

    public override suspend fun appendPlanUpdate(
        item: ResponseItem.FunctionCall,
        plan: UpdatePlanArgs,
    ): Int =
        mutate(CodexAgentStateEnum.ExternalWrite) {
            publishStorageTransition {
                val index = storage.latestIndex() + 1
                storage.plan[index] = plan
                storage.history[index] = item
                storage.timestamp[index] = now()
                latestIndex.value = index
                index
            }
        }

    public override suspend fun updateSetting(
        settings: CodexAgentSettings,
        timestamp: Instant,
        tokenCount: Long?,
    ): Int =
        mutate(CodexAgentStateEnum.ExternalWrite) {
            publishStorageTransition {
                val index = storage.latestIndex() + 1
                require(index > 0) { "Settings updates require an existing state index." }
                storage.settings[index] = settings
                if (tokenCount != null) {
                    storage.tokenCount[index] = tokenCount
                }
                storage.timestamp[index] = timestamp
                latestIndex.value = index
                index
            }
        }

    private suspend fun runAutoCompact(
        trigger: RemoteCompactionV2Trigger,
        reason: RemoteCompactionV2Reason,
        phase: RemoteCompactionV2Phase,
    ): Int =
        withInternalState(CodexAgentStateEnum.LlmRequest.Compact) {
            val snapshotIndex = storage.latestIndex()
            check(snapshotIndex >= 0) { "Cannot compact an agent without initial state." }

            val settings = storage.settings[snapshotIndex]
            val checkpoint = storage.compaction[snapshotIndex]
            val input = storage.modelInputAt(snapshotIndex)
            if (settings.remoteCompactionV2) {
                val result = requestRemoteCompactionV2(
                    settings = settings,
                    checkpoint = checkpoint,
                    input = input,
                    trigger = trigger,
                    reason = reason,
                    phase = phase,
                )
                publishCompactionCheckpoint(
                    prefix = input
                        .filterIsInstance<ResponseItem.Message>()
                        .filter { it.role == MessageRole.User }
                        .plus(result.compactionOutput),
                    marker = ResponseItem.ContextCompaction(
                        encryptedContent = result.compactionOutput.encryptedContent,
                    ),
                    tokenCount = result.completedResponse?.usage?.totalTokens,
                    windowId = checkpoint.windowId + 1,
                )
            } else {
                val response = when (
                    val result = client.compactResponse(
                        settings.toCompactionInput(input + ResponseItem.CompactionTrigger),
                    )
                ) {
                    is OpenAiResult.Success -> result.value
                    is OpenAiResult.Failure -> throw CodexCompactionFailureException(result.error)
                }
                publishCompactionCheckpoint(
                    prefix = response.output,
                    marker = ResponseItem.ContextCompaction(),
                    tokenCount = null,
                    windowId = checkpoint.windowId + 1,
                )
            }
        }

    private suspend fun requestRemoteCompactionV2(
        settings: CodexAgentSettings,
        checkpoint: CompactionCheckpoint,
        input: List<ResponseItem>,
        trigger: RemoteCompactionV2Trigger,
        reason: RemoteCompactionV2Reason,
        phase: RemoteCompactionV2Phase,
    ): RemoteCompactionV2Response {
        val turnId = "turn_${nextTurnOrdinal++}"
        val request = RemoteCompactionV2Request(
            history = input,
            checkpoint = checkpoint,
            settings = settings,
            turnId = turnId,
            trigger = trigger,
            reason = reason,
            phase = phase,
        )
        return client.createRemoteCompactionV2Response(request)
    }

    private suspend fun publishCompactionCheckpoint(
        prefix: List<ResponseItem.HistoryItem>,
        marker: ResponseItem.ContextCompaction,
        tokenCount: Long?,
        windowId: Long,
    ): Int = publishStorageTransition {
        val index = storage.latestIndex() + 1
        storage.compaction[index] = CompactionCheckpoint(
            prefix = prefix,
            historyBaseIndex = index + 1,
            windowId = windowId,
        )
        storage.history[index] = marker
        if (tokenCount != null) {
            storage.tokenCount[index] = tokenCount
        }
        storage.timestamp[index] = now()
        latestIndex.value = index
        index
    }

    private suspend fun appendHistoryItem(
        item: ResponseItem.HistoryItem,
        timestamp: Instant,
        tokenCount: Long?,
    ): Int = publishStorageTransition {
        val index = storage.latestIndex() + 1
        if (tokenCount != null) {
            storage.tokenCount[index] = tokenCount
        }
        storage.history[index] = item
        storage.timestamp[index] = timestamp
        latestIndex.value = index
        index
    }

    private suspend fun shouldAutoCompact(snapshotIndex: Int): Boolean {
        if (snapshotIndex < 0 || storage.tokenCount.latestIndex() < 0) {
            return false
        }
        val settings = storage.settings[snapshotIndex]
        val tokenCount = storage.tokenCount[snapshotIndex]
        val limit = settings.autoCompactionTokenLimit ?: return false
        return tokenCount >= limit
    }

    private suspend inline fun <T> mutate(
        newState: CodexAgentStateEnum,
        block: () -> T,
    ): T {
        if (!mutableState.compareAndSet(CodexAgentStateEnum.Idle, newState)) {
            throw CodexAgentStateConcurrentModificationException(mutableState.value)
        }
        return try {
            block()
        } finally {
            withContext(NonCancellable) {
                latestIndex.value = storage.latestIndex()
                mutableState.value = CodexAgentStateEnum.Idle
            }
        }
    }

    private suspend inline fun <T> withInternalState(
        newState: CodexAgentStateEnum,
        block: suspend () -> T,
    ): T {
        val previousState = mutableState.value
        mutableState.value = newState
        return try {
            block()
        } finally {
            mutableState.value = previousState
        }
    }

}

private suspend inline fun <T> publishStorageTransition(
    crossinline block: suspend () -> T,
): T =
    withContext(NonCancellable) {
        block()
    }

public class CodexResponseStreamFailureException(
    public val error: ResponseError?,
) : IllegalStateException(
    error?.message ?: "Response failed without structured error.",
)

public class CodexCompactionFailureException(
    public val error: OpenAiErrorResponse,
) : IllegalStateException(
    "Compaction failed: ${error.messageText ?: error}",
)

public class CodexAgentStateConcurrentModificationException(
    public val currentState: CodexAgentStateEnum,
) : ConcurrentModificationException("Agent state was modified concurrently: $currentState")

private fun CodexAgentSettings.toRequest(input: List<ResponseItem>): ResponsesApiRequest =
    ResponsesApiRequest(
        model = model,
        input = input,
        instructions = instructions,
        store = store,
        previousResponseId = previousResponseId,
        tools = tools,
        toolChoice = toolChoice,
        parallelToolCalls = parallelToolCalls,
        reasoning = reasoning,
        include = include,
        serviceTier = serviceTier,
        promptCacheKey = promptCacheKey,
        text = text,
        clientMetadata = clientMetadata,
    )

private fun CodexAgentSettings.toCompactionInput(input: List<ResponseItem>): CompactionInput =
    CompactionInput(
        model = model,
        input = input,
        instructions = instructions,
        tools = tools,
        parallelToolCalls = parallelToolCalls,
        reasoning = reasoning,
        serviceTier = serviceTier,
        promptCacheKey = promptCacheKey,
        text = text,
    )

private suspend fun CodexAgentStorage.modelInputAt(index: Int): List<ResponseItem> {
    val checkpoint = compaction[index]
    val items = checkpoint.prefix.toMutableList()
    history.indexes(checkpoint.historyBaseIndex).collect { itemIndex ->
        if (itemIndex <= index) {
            items += history[itemIndex]
        }
    }
    return items
}

@OptIn(ExperimentalTime::class)
private fun now(): Instant = Clock.System.now()
