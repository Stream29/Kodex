package io.github.stream29.codex.lite.agentstate.impl

import io.github.stream29.codex.lite.agentcontext.collaboration.render.render as renderCollaborationMode
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefixProvider
import io.github.stream29.codex.lite.agentcontext.prefix.render.render
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstorage.contract.CodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.contract.appendCompactionCheckpoint
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.contract.prevIndex
import io.github.stream29.codex.lite.agentstorage.contract.transaction
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Phase
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Reason
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Trigger
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.codexRequestWindowId
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Loads a state-layer implementation from [storage].
 *
 * Construction is suspend because storage reads may be asynchronous. The
 * initial phase is reconstructed from persisted history rather than assumed
 * from the newest global index, which may belong to another timeline.
 * [contextPrefixProvider] contributes transient request input and is never persisted
 * in [storage].
 */
public suspend fun CodexAgentState(
    client: OpenAiClient,
    storage: MutableCodexAgentStorage,
    contextPrefixProvider: AgentContextPrefixProvider,
): CodexAgentState {
    val loadedLatestIndex = storage.latestIndex()
    return CodexAgentStateImpl(
        client = client,
        storage = storage,
        loadedLatestIndex = loadedLatestIndex,
        initialState = storage.stateAt(loadedLatestIndex),
        contextPrefixProvider = contextPrefixProvider,
    )
}

/**
 * Atomic state-layer implementation of one Codex agent thread.
 *
 * This class projects persisted history into one Responses request and writes
 * the resulting completed output items. It deliberately performs neither
 * automatic compaction nor multi-request continuation; those are AgentRuntime
 * responsibilities.
 */
private class CodexAgentStateImpl(
    private val client: OpenAiClient,
    override val storage: MutableCodexAgentStorage,
    loadedLatestIndex: Int,
    initialState: CodexAgentStateValue,
    private val contextPrefixProvider: AgentContextPrefixProvider,
) : CodexAgentState {
    override val state: StateFlow<CodexAgentStateValue>
        field = MutableStateFlow(initialState)

    override val latestIndex: StateFlow<Int>
        field = MutableStateFlow(loadedLatestIndex)

    override fun requestResponseApi(): Flow<ResponsesStreamEvent> = flow {
        val previousState = state.value
        previousState.requireCanRequestResponseApi()
        if (!state.compareAndSet(previousState, CodexAgentStateValue.RequestResponse)) {
            throw CodexAgentStateInvalidTransitionException("start a response request", state.value)
        }

        try {
            val snapshotIndex = storage.latestIndex()
            check(snapshotIndex >= 0) { "Cannot request a response without initial state." }

            val settings = storage.settings[snapshotIndex]
            val durableInput = storage.modelInputAt(snapshotIndex)
            val collaborationContext = settings.collaborationMode.renderCollaborationMode()?.let { instructions ->
                ResponseItem.Message(
                    role = MessageRole.Developer,
                    content = listOf(ContentItem.InputText(instructions)),
                )
            }
            val requestContext = contextPrefixProvider.render()
            val checkpoint = storage.compaction[snapshotIndex]
            val windowId = checkpoint.codexRequestWindowId(storage.id)
            val turnMetadata = settings.toCodexTurnMetadata(
                threadId = storage.id,
                windowId = windowId,
                requestKind = "turn",
            )

            client.createResponse(
                request = settings.toResponsesApiRequest(
                    input = listOfNotNull(collaborationContext) + requestContext + durableInput,
                    threadId = storage.id,
                    turnMetadata = turnMetadata,
                    windowId = windowId,
                ),
                installationId = settings.installationId,
                turnMetadata = turnMetadata,
                windowId = windowId,
            ).collect { event ->
                when (event) {
                    is ResponsesStreamEvent.OutputItemDone -> {
                        val historyItem = event.item as? ResponseItem.HistoryItem
                        if (historyItem != null) {
                            appendHistoryItem(historyItem, now(), tokenCount = null)
                        }
                    }

                    is ResponsesStreamEvent.Completed -> {
                        event.response.usage?.totalTokens?.let { tokenCount ->
                            appendTimestampAndTokenCount(tokenCount)
                        }
                    }

                    else -> Unit
                }
                emit(event)
            }
        } finally {
            state.value = withContext(NonCancellable) {
                storage.stateAt(storage.latestIndex())
            }
        }
    }.buffer(Channel.UNLIMITED)

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun compact(
        trigger: RemoteCompactionV2Trigger,
        reason: RemoteCompactionV2Reason,
        phase: RemoteCompactionV2Phase,
    ): Int =
        mutate(
            validate = CodexAgentStateValue::requireCanCompact,
            inFlight = CodexAgentStateValue.Compacting,
        ) {
            val snapshotIndex = storage.latestIndex()
            check(snapshotIndex >= 0) { "Cannot compact an agent without initial state." }

            val settings = storage.settings[snapshotIndex]
            val checkpoint = storage.compaction[snapshotIndex]
            val input = storage.modelInputAt(snapshotIndex)
            val requestSettings = if (phase == RemoteCompactionV2Phase.StandaloneTurn) {
                settings.copy(turnId = Uuid.generateV7().toString())
            } else {
                settings
            }
            val windowId = checkpoint.codexRequestWindowId(storage.id)
            val turnMetadata = requestSettings.toCodexTurnMetadata(
                threadId = storage.id,
                windowId = windowId,
                requestKind = "compaction",
                compaction = buildJsonObject {
                    put("trigger", trigger.wireName)
                    put("reason", reason.wireName)
                    put("implementation", "responses_compaction_v2")
                    put("phase", phase.wireName)
                    put("strategy", "memento")
                },
            )
            val result = client.createRemoteCompactionV2Response(
                request = requestSettings.toResponsesApiRequest(
                    input = input + ResponseItem.CompactionTrigger,
                    threadId = storage.id,
                    turnMetadata = turnMetadata,
                    windowId = windowId,
                ),
                installationId = requestSettings.installationId,
                turnMetadata = turnMetadata,
                windowId = windowId,
            )
            storage.appendCompactionCheckpoint(
                prefix = buildRemoteCompactionV2Prefix(input, result.compactionOutput),
                marker = ResponseItem.ContextCompaction(
                    encryptedContent = result.compactionOutput.encryptedContent,
                ),
                timestamp = now(),
                tokenCount = result.completedResponse?.usage?.totalTokens,
                previousCheckpoint = checkpoint,
                nextWindowId = Uuid.generateV7().toString(),
                settings = requestSettings,
            ).also { latestIndex.value = it }
        }

    override suspend fun injectHistory(items: List<ResponseItem.HistoryItem>): Int {
        if (items.isEmpty()) {
            return latestIndex.value
        }
        return mutate(
            validate = {},
            inFlight = CodexAgentStateValue.ExternalWrite,
        ) {
            val timestamp = now()
            val index = storage.transaction {
                var index = storage.latestIndex()
                for (item in items) {
                    index += 1
                    storage.history[index] = item
                    storage.timestamp[index] = timestamp
                }
                index
            }
            latestIndex.value = index
            state.value = storage.stateAt(index)
            index
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun appendUserMessage(content: List<ContentItem>): Int =
        mutate(
            validate = CodexAgentStateValue::requireCanAppendUserMessage,
            inFlight = CodexAgentStateValue.ExternalWrite,
        ) {
            val snapshotIndex = storage.latestIndex()
            val currentSettings = storage.settings[snapshotIndex]
            val settings = if (storage.stateAt(snapshotIndex) == CodexAgentStateValue.Empty) {
                currentSettings
            } else {
                currentSettings.copy(turnId = Uuid.generateV7().toString())
            }
            val item = ResponseItem.Message(
                role = MessageRole.User,
                content = content,
            )
            val index = storage.transaction {
                val index = storage.latestIndex() + 1
                if (settings != currentSettings) {
                    storage.settings[index] = settings
                }
                storage.history[index] = item
                storage.timestamp[index] = now()
                index
            }
            latestIndex.value = index
            state.value = CodexAgentStateValue.UserMessage
            index
        }

    override suspend fun completeToolCall(output: ResponseItem.ToolCallOutput): Int {
        var pendingCalls = emptyList<ResponseItem.ToolCall>()
        return mutate(
            validate = { value -> pendingCalls = value.requireCanCompleteToolCalls() },
            inFlight = CodexAgentStateValue.ExternalWrite,
        ) {
            val nextState = pendingCalls.stateAfterCompleting(output.callId)
            val index = storage.transaction {
                val index = storage.latestIndex() + 1
                storage.history[index] = output
                storage.timestamp[index] = now()
                index
            }
            latestIndex.value = index
            state.value = nextState
            index
        }
    }

    override suspend fun appendPlanUpdate(
        output: ResponseItem.FunctionCallOutput,
        plan: UpdatePlanArgs,
    ): Int {
        var pendingCalls = emptyList<ResponseItem.ToolCall>()
        return mutate(
            validate = { value -> pendingCalls = value.requireCanCompleteToolCalls() },
            inFlight = CodexAgentStateValue.ExternalWrite,
        ) {
            val pendingCall = pendingCalls.requireCall(output.callId)
            require(pendingCall is ResponseItem.FunctionCall && pendingCall.name == "update_plan") {
                "Plan updates can complete only a pending update_plan function call."
            }
            val currentSettings = storage.settings[storage.latestIndex()]
            require(currentSettings.collaborationMode != ModeKind.Plan) {
                "update_plan is a TODO/checklist tool and is not allowed in Plan mode."
            }
            val nextState = pendingCalls.stateAfterCompleting(output.callId)
            val index = storage.transaction {
                val index = storage.latestIndex() + 1
                storage.settings[index] = currentSettings.copy(plan = plan)
                storage.history[index] = output
                storage.timestamp[index] = now()
                index
            }
            latestIndex.value = index
            state.value = nextState
            index
        }
    }

    override suspend fun updateSettings(settings: CodexAgentSettings): Int =
        mutate(
            validate = {},
            inFlight = CodexAgentStateValue.ExternalWrite,
        ) {
            val currentSettings = storage.settings[storage.latestIndex()]
            val index = storage.transaction {
                val index = storage.latestIndex() + 1
                require(index > 0) { "Settings updates require an existing state index." }
                storage.settings[index] = settings.copy(turnId = currentSettings.turnId)
                storage.timestamp[index] = now()
                index
            }
            latestIndex.value = index
            index
        }

    private suspend fun appendHistoryItem(
        item: ResponseItem.HistoryItem,
        timestamp: Instant,
        tokenCount: Long?,
    ): Int {
        val index = storage.transaction {
            val index = storage.latestIndex() + 1
            if (tokenCount != null) {
                storage.tokenCount[index] = tokenCount
            }
            storage.history[index] = item
            storage.timestamp[index] = timestamp
            index
        }
        latestIndex.value = index
        return index
    }

    private suspend fun appendTimestampAndTokenCount(tokenCount: Long) {
        val index = storage.transaction {
            val index = storage.latestIndex() + 1
            storage.tokenCount[index] = tokenCount
            storage.timestamp[index] = now()
            index
        }
        latestIndex.value = index
    }

    private inline fun <T> mutate(
        validate: (CodexAgentStateValue) -> Unit,
        inFlight: CodexAgentStateValue,
        block: () -> T,
    ): T {
        val currentState = state.value
        if (!currentState.isStable) {
            throw CodexAgentStateInvalidTransitionException("start an atomic operation", currentState)
        }
        validate(currentState)
        if (!state.compareAndSet(currentState, inFlight)) {
            throw CodexAgentStateInvalidTransitionException("start an atomic operation", state.value)
        }

        try {
            return block()
        } finally {
            if (state.value == inFlight) {
                state.value = currentState
            }
        }
    }
}

public class CodexAgentStateInvalidTransitionException(
    public val operation: String,
    public val currentState: CodexAgentStateValue,
) : IllegalStateException("Cannot $operation while agent state is $currentState.")

private val CodexAgentStateValue.isStable: Boolean
    get() = when (this) {
        CodexAgentStateValue.Empty,
        CodexAgentStateValue.UserMessage,
        CodexAgentStateValue.AssistantMessage,
        is CodexAgentStateValue.ToolPending,
        CodexAgentStateValue.ToolCompleted,
        -> true

        CodexAgentStateValue.ExternalWrite,
        CodexAgentStateValue.RequestResponse,
        CodexAgentStateValue.Compacting,
        -> false
    }

private fun CodexAgentStateValue.requireCanRequestResponseApi() {
    if (
        this != CodexAgentStateValue.UserMessage &&
        this != CodexAgentStateValue.AssistantMessage &&
        this != CodexAgentStateValue.ToolCompleted
    ) {
        throw CodexAgentStateInvalidTransitionException("request a response", this)
    }
}

private fun CodexAgentStateValue.requireCanCompact() {
    if (
        this != CodexAgentStateValue.UserMessage &&
        this != CodexAgentStateValue.AssistantMessage &&
        this != CodexAgentStateValue.ToolCompleted
    ) {
        throw CodexAgentStateInvalidTransitionException("compact context", this)
    }
}

private fun CodexAgentStateValue.requireCanAppendUserMessage() {
    if (
        this != CodexAgentStateValue.Empty &&
        this != CodexAgentStateValue.UserMessage &&
        this != CodexAgentStateValue.AssistantMessage
    ) {
        throw CodexAgentStateInvalidTransitionException("append a user message", this)
    }
}

private fun CodexAgentStateValue.requireCanCompleteToolCalls(): List<ResponseItem.ToolCall> =
    (this as? CodexAgentStateValue.ToolPending)?.calls
        ?: throw CodexAgentStateInvalidTransitionException("complete tool calls", this)

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

/**
 * Derives the state from the active history tail at [index].
 *
 * A user, assistant, or tool message ends the current local-tool batch. A
 * developer message is context-only, so this scans past it and returns every
 * unresolved call in chronological order.
 */
private suspend fun CodexAgentStorage.stateAt(index: Int): CodexAgentStateValue {
    if (index < 0) {
        return CodexAgentStateValue.Empty
    }

    val checkpoint = compaction[index]
    val completedToolCallIds = mutableSetOf<String>()
    val pendingCallsReversed = mutableListOf<ResponseItem.ToolCall>()
    var sawToolCallOutput = false
    fun stateAfterReading(item: ResponseItem): CodexAgentStateValue? =
        when (item) {
            is ResponseItem.ToolCallOutput -> {
                completedToolCallIds += item.callId
                sawToolCallOutput = true
                null
            }

            is ResponseItem.ToolCall -> {
                if (completedToolCallIds.remove(item.callId)) {
                    null
                } else {
                    pendingCallsReversed += item
                    null
                }
            }

            is ResponseItem.Message -> {
                if (pendingCallsReversed.isNotEmpty()) {
                    CodexAgentStateValue.ToolPending(pendingCallsReversed.asReversed().toList())
                } else if (sawToolCallOutput) {
                    CodexAgentStateValue.ToolCompleted
                } else {
                    when (item.role) {
                        MessageRole.User -> CodexAgentStateValue.UserMessage
                        MessageRole.Developer -> null
                        MessageRole.Assistant -> CodexAgentStateValue.AssistantMessage
                        MessageRole.Tool -> CodexAgentStateValue.ToolCompleted
                    }
                }
            }

            else -> null
        }

    var historyIndex = history.floorToIndex(index)
    while (historyIndex != null && historyIndex >= checkpoint.historyBaseIndex) {
        stateAfterReading(history[historyIndex])?.let { return it }
        historyIndex = history.prevIndex(historyIndex)
    }

    for (item in checkpoint.prefix.asReversed()) {
        stateAfterReading(item)?.let { return it }
    }
    return when {
        pendingCallsReversed.isNotEmpty() -> CodexAgentStateValue.ToolPending(pendingCallsReversed.asReversed().toList())
        sawToolCallOutput -> CodexAgentStateValue.ToolCompleted
        else -> CodexAgentStateValue.Empty
    }
}

private fun List<ResponseItem.ToolCall>.requireCall(callId: String): ResponseItem.ToolCall =
    firstOrNull { call -> call.callId == callId }
        ?: throw IllegalArgumentException("Tool output does not match a pending call id: $callId")

private fun List<ResponseItem.ToolCall>.stateAfterCompleting(callId: String): CodexAgentStateValue {
    requireCall(callId)
    val remainingCalls = filterNot { call -> call.callId == callId }
    return if (remainingCalls.isEmpty()) {
        CodexAgentStateValue.ToolCompleted
    } else {
        CodexAgentStateValue.ToolPending(remainingCalls)
    }
}

@OptIn(ExperimentalTime::class)
private fun now(): Instant = Clock.System.now()
