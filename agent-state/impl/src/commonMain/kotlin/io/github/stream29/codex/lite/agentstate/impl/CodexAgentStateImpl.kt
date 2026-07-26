package io.github.stream29.codex.lite.agentstate.impl

import io.github.stream29.codex.lite.agentcontext.prefix.render.render as renderCollaborationMode
import io.github.stream29.codex.lite.agentcontext.prefix.render.renderMultiAgentMode
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
import io.github.stream29.codex.lite.agentstorage.contract.revert
import io.github.stream29.codex.lite.agentstorage.contract.revertWithTransaction
import io.github.stream29.codex.lite.agentstorage.contract.setWithTransaction
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CodexResponsesMetadata
import io.github.stream29.codex.lite.openai.CodexResponsesRequestKind
import io.github.stream29.codex.lite.openai.CompactionImplementation
import io.github.stream29.codex.lite.openai.CompactionStrategy
import io.github.stream29.codex.lite.openai.CompactionTurnMetadata
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.CompactionPhase
import io.github.stream29.codex.lite.openai.CompactionReason
import io.github.stream29.codex.lite.openai.CompactionTrigger
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.ToolSpec
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
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Loads a state-layer implementation from [storage].
 *
 * Construction is suspend because storage reads may be asynchronous. The
 * initial phase is reconstructed from persisted history rather than assumed
 * from the newest global index, which may belong to another timeline.
 *
 * [contextPrefixProvider] supplies the complete structured request prefix.
 * It is resolved for every normal Responses request and is never persisted or
 * included in remote compaction input.
 *
 * [toolSearchToolSpec] is required and evaluated once for every normal
 * Responses request and remote compaction request. This lets dynamic MCP
 * catalogs change without moving complete request-tool assembly outside the
 * state layer.
 */
public suspend fun CodexAgentState(
    client: OpenAiClient,
    storage: MutableCodexAgentStorage,
    contextPrefixProvider: AgentContextPrefixProvider,
    toolSearchToolSpec: suspend () -> ToolSpec.ToolSearch,
): CodexAgentState {
    val loadedLatestIndex = storage.latestIndex()
    return CodexAgentStateImpl(
        client = client,
        storage = storage,
        contextPrefixProvider = contextPrefixProvider,
        toolSearchToolSpec = toolSearchToolSpec,
        loadedLatestIndex = loadedLatestIndex,
        initialState = storage.stateAt(loadedLatestIndex),
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
    private val contextPrefixProvider: AgentContextPrefixProvider,
    private val toolSearchToolSpec: suspend () -> ToolSpec.ToolSearch,
    loadedLatestIndex: Int,
    initialState: CodexAgentStateValue,
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
            val collaborationContext = ResponseItem.Message(
                role = MessageRole.Developer,
                content = listOf(ContentItem.InputText(settings.collaborationMode.renderCollaborationMode())),
            )
            val multiAgentContext = ResponseItem.Message(
                role = MessageRole.Developer,
                content = listOf(ContentItem.InputText(settings.reasoning.effort.renderMultiAgentMode())),
            )
            val contextPrefix = contextPrefixProvider(settings).render()
            val checkpoint = storage.compaction[snapshotIndex]
            val threadId = storage.id.toCodexThreadId()
            val windowId = checkpoint.codexRequestWindowId(threadId)
            val metadata = CodexResponsesMetadata(
                installationId = settings.installationId,
                sessionId = settings.sessionId,
                threadId = threadId,
                turnId = settings.turnId,
                windowId = windowId,
                requestKind = CodexResponsesRequestKind.Turn,
            )
            val clientMetadata = metadata.toCodexClientMetadata()

            client.createResponse(
                request = settings.toResponsesApiRequest(
                    input = listOf(collaborationContext, multiAgentContext) + contextPrefix + durableInput,
                    clientMetadata = clientMetadata,
                    tools = codexRequestToolSpecs(settings, toolSearchToolSpec()),
                ),
                installationId = clientMetadata.installationId,
                turnMetadata = clientMetadata.turnMetadata,
                windowId = clientMetadata.windowId,
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
        trigger: CompactionTrigger,
        reason: CompactionReason,
        phase: CompactionPhase,
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
            val threadId = storage.id.toCodexThreadId()
            val windowId = checkpoint.codexRequestWindowId(threadId)
            val metadata = CodexResponsesMetadata(
                installationId = settings.installationId,
                sessionId = settings.sessionId,
                threadId = threadId,
                turnId = settings.turnId,
                windowId = windowId,
                requestKind = CodexResponsesRequestKind.Compaction(
                    metadata = CompactionTurnMetadata(
                        trigger = trigger,
                        reason = reason,
                        implementation = CompactionImplementation.ResponsesCompactionV2,
                        phase = phase,
                        strategy = CompactionStrategy.Memento,
                    ),
                ),
            )
            val clientMetadata = metadata.toCodexClientMetadata()
            val result = client.createRemoteCompactionV2Response(
                request = settings.toResponsesApiRequest(
                    input = input + ResponseItem.CompactionTrigger,
                    clientMetadata = clientMetadata,
                    tools = codexRequestToolSpecs(settings, toolSearchToolSpec()),
                ),
                installationId = clientMetadata.installationId,
                turnMetadata = clientMetadata.turnMetadata,
                windowId = clientMetadata.windowId,
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
                settings = settings,
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
            val firstIndex = storage.latestIndex() + 1
            val index = storage.history.revertWithTransaction(firstIndex) {
                storage.timestamp.revertWithTransaction(firstIndex) {
                    var index = storage.latestIndex()
                    for (item in items) {
                        index += 1
                        storage.history[index] = item
                        storage.timestamp[index] = timestamp
                    }
                    index
                }
            }
            latestIndex.value = index
            state.value = storage.stateAt(index)
            index
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun appendUserMessage(content: List<ContentItem>): Int =
        appendUserMessage(content) { snapshotIndex, currentSettings ->
            if (storage.stateAt(snapshotIndex) == CodexAgentStateValue.Empty) {
                currentSettings.turnId
            } else {
                Uuid.generateV7().toString()
            }
        }

    override suspend fun appendUserMessage(
        content: List<ContentItem>,
        turnId: String,
    ): Int {
        require(turnId.isNotBlank()) { "Turn id must not be blank." }
        return appendUserMessage(content) { _, _ -> turnId }
    }

    private suspend inline fun appendUserMessage(
        content: List<ContentItem>,
        turnId: (snapshotIndex: Int, currentSettings: CodexAgentSettings) -> String,
    ): Int =
        mutate(
            validate = CodexAgentStateValue::requireCanAppendUserMessage,
            inFlight = CodexAgentStateValue.ExternalWrite,
        ) {
            val snapshotIndex = storage.latestIndex()
            val currentSettings = storage.settings[snapshotIndex]
            val settings = currentSettings.copy(turnId = turnId(snapshotIndex, currentSettings))
            val item = ResponseItem.Message(
                role = MessageRole.User,
                content = content,
            )
            val firstIndex = snapshotIndex + 1
            val timestamp = now()
            val index = storage.settings.revertWithTransaction(firstIndex) {
                if (settings != currentSettings) {
                    storage.settings[firstIndex] = settings
                }
                storage.history.revertWithTransaction(firstIndex) {
                    storage.timestamp.revertWithTransaction(firstIndex) {
                        storage.history[firstIndex] = item
                        storage.timestamp[firstIndex] = timestamp
                        firstIndex
                    }
                }
            }
            latestIndex.value = index
            state.value = storage.stateAt(index)
            index
        }

    override suspend fun completeToolCall(output: ResponseItem.ToolCallOutput): Int {
        var pendingCalls = emptyList<ResponseItem.ToolCall>()
        return mutate(
            validate = { value ->
                val pending = value.requireToolPending()
                pendingCalls = pending.calls
            },
            inFlight = CodexAgentStateValue.ExternalWrite,
        ) {
            pendingCalls.requireCall(output.callId)
            val nextState = toolPendingState(pendingCalls.filterNot { call -> call.callId == output.callId })
            val index = storage.latestIndex() + 1
            storage.history.setWithTransaction(index, output) {
                storage.timestamp.setWithTransaction(index, now()) { index }
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
            validate = { value ->
                val pending = value.requireToolPending()
                pendingCalls = pending.calls
            },
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
            val nextState = toolPendingState(pendingCalls.filterNot { call -> call.callId == output.callId })
            val index = storage.latestIndex() + 1
            storage.settings.setWithTransaction(index, currentSettings.copy(plan = plan)) {
                storage.history.setWithTransaction(index, output) {
                    storage.timestamp.setWithTransaction(index, now()) { index }
                }
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
            val index = storage.latestIndex() + 1
            require(index > 0) { "Settings updates require an existing state index." }
            storage.settings.setWithTransaction(index, settings.copy(turnId = currentSettings.turnId)) {
                storage.timestamp.setWithTransaction(index, now()) { index }
            }
            latestIndex.value = index
            index
        }

    override suspend fun revert(untilExclusive: Int): Int =
        mutate(
            validate = CodexAgentStateValue::requireCanRevert,
            inFlight = CodexAgentStateValue.ExternalWrite,
        ) {
            val currentLatestIndex = storage.latestIndex()
            require(untilExclusive in 1..(currentLatestIndex + 1)) {
                "Revert boundary $untilExclusive must retain a visible agent snapshot."
            }
            val targetState = storage.stateAt(untilExclusive - 1)
            targetState.requireRevertTarget()

            storage.revert(untilExclusive)
            val revertedIndex = storage.latestIndex()
            latestIndex.value = revertedIndex
            state.value = storage.stateAt(revertedIndex)
            revertedIndex
        }

    private suspend fun appendHistoryItem(
        item: ResponseItem.HistoryItem,
        timestamp: Instant,
        tokenCount: Long?,
    ): Int {
        val index = storage.latestIndex() + 1
        if (tokenCount == null) {
            storage.history.setWithTransaction(index, item) {
                storage.timestamp.setWithTransaction(index, timestamp) { index }
            }
        } else {
            storage.tokenCount.setWithTransaction(index, tokenCount) {
                storage.history.setWithTransaction(index, item) {
                    storage.timestamp.setWithTransaction(index, timestamp) { index }
                }
            }
        }
        latestIndex.value = index
        return index
    }

    private suspend fun appendTimestampAndTokenCount(tokenCount: Long) {
        val index = storage.latestIndex() + 1
        storage.tokenCount.setWithTransaction(index, tokenCount) {
            storage.timestamp.setWithTransaction(index, now()) { index }
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
    operation: String,
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
        this != CodexAgentStateValue.AssistantMessage &&
        this != CodexAgentStateValue.ToolCompleted
    ) {
        throw CodexAgentStateInvalidTransitionException("append a user message", this)
    }
}

private fun CodexAgentStateValue.requireCanRevert() {
    if (this != CodexAgentStateValue.Empty && this != CodexAgentStateValue.AssistantMessage) {
        throw CodexAgentStateInvalidTransitionException("revert history", this)
    }
}

private fun CodexAgentStateValue.requireRevertTarget() {
    if (this != CodexAgentStateValue.Empty && this != CodexAgentStateValue.AssistantMessage) {
        throw IllegalArgumentException("Revert target must be an empty or completed assistant snapshot, got $this.")
    }
}

private fun CodexAgentStateValue.requireToolPending(): CodexAgentStateValue.ToolPending =
    this as? CodexAgentStateValue.ToolPending
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
 * A user, inter-Agent, Hook, assistant, or tool message ends the current
 * local-tool batch. A developer message is context-only, so this scans past it
 * and returns every unresolved call in chronological order.
 */
private suspend fun CodexAgentStorage.stateAt(index: Int): CodexAgentStateValue {
    if (index < 0) {
        return CodexAgentStateValue.Empty
    }

    val checkpoint = compaction[index]
    val completedToolCallIds = mutableSetOf<String>()
    val pendingCallsReversed = mutableListOf<ResponseItem.ToolCall>()
    var sawToolOutput = false
    fun stateAfterReading(item: ResponseItem): CodexAgentStateValue? =
        when (item) {
            is ResponseItem.ToolCallOutput -> {
                completedToolCallIds += item.callId
                sawToolOutput = true
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
                } else if (sawToolOutput) {
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

            is ResponseItem.AgentMessage -> {
                if (pendingCallsReversed.isNotEmpty()) {
                    CodexAgentStateValue.ToolPending(pendingCallsReversed.asReversed().toList())
                } else if (sawToolOutput) {
                    CodexAgentStateValue.ToolCompleted
                } else {
                    CodexAgentStateValue.UserMessage
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
        pendingCallsReversed.isNotEmpty() ->
            CodexAgentStateValue.ToolPending(pendingCallsReversed.asReversed().toList())

        sawToolOutput -> CodexAgentStateValue.ToolCompleted
        else -> CodexAgentStateValue.Empty
    }
}

private fun List<ResponseItem.ToolCall>.requireCall(callId: String): ResponseItem.ToolCall =
    firstOrNull { call -> call.callId == callId }
        ?: throw IllegalArgumentException("Tool output does not match a pending call id: $callId")

private fun toolPendingState(calls: List<ResponseItem.ToolCall>): CodexAgentStateValue {
    return if (calls.isEmpty()) {
        CodexAgentStateValue.ToolCompleted
    } else {
        CodexAgentStateValue.ToolPending(calls)
    }
}

private fun now(): Instant = Clock.System.now()
