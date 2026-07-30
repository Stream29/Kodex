package io.github.stream29.codex.lite.agentstate.impl

import io.github.stream29.codex.lite.agentcontext.prefix.render.render as renderCollaborationMode
import io.github.stream29.codex.lite.agentcontext.prefix.render.renderMultiAgentMode
import io.github.stream29.codex.lite.agentcontext.contract.AgentContextSettings
import io.github.stream29.codex.lite.agentcontext.prefix.impl.AgentContextPrefixResolver
import io.github.stream29.codex.lite.agentcontext.prefix.render.render
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.contract.canAppendUserMessage
import io.github.stream29.codex.lite.agentstate.contract.canCompact
import io.github.stream29.codex.lite.agentstate.contract.canMarkNewTurn
import io.github.stream29.codex.lite.agentstate.contract.canRequestResponseApi
import io.github.stream29.codex.lite.agentstate.tool.toPendingToolEvent
import io.github.stream29.codex.lite.agentstate.tool.visibleToolSpecs
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.toStableCleanEventOrNull
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.codex.lite.agentstorage.contract.CodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.contract.appendCompactionCheckpoint
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.agentstorage.contract.prevIndex
import io.github.stream29.codex.lite.agentstorage.contract.revertWithTransaction
import io.github.stream29.codex.lite.agentstorage.contract.setWithTransaction
import io.github.stream29.codex.lite.mcp.contract.McpService
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
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.codexRequestWindowId
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Loads a state-layer implementation from [storage] as a child of this scope.
 *
 * Construction is suspend because storage reads may be asynchronous. The
 * initial phase is reconstructed from persisted history rather than assumed
 * from the newest global index, which may belong to another timeline.
 *
 * [contextSettings] is observed for every normal Responses request. This state
 * resolves the complete context prefix internally; the prefix is never
 * persisted or included in remote compaction input.
 *
 * [mcpService] is sampled once for every normal Responses request and remote
 * compaction request. AgentState derives the matching tool-search definition
 * internally and never closes the application-owned service.
 */
public suspend fun CoroutineScope.CodexAgentState(
    client: OpenAiClient,
    storage: MutableCodexAgentStorage,
    contextSettings: StateFlow<AgentContextSettings>,
    mcpService: McpService,
): CodexAgentState {
    val stateScope = supervisorChildScope()
    try {
        val loadedLatestIndex = storage.latestIndex()
        return CodexAgentStateImpl(
            scope = stateScope,
            client = client,
            storage = storage,
            contextSettings = contextSettings,
            mcpService = mcpService,
            loadedLatestIndex = loadedLatestIndex,
            initialState = storage.stateAt(loadedLatestIndex),
        )
    } catch (failure: Throwable) {
        stateScope.cancel()
        throw failure
    }
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
    scope: CoroutineScope,
    private val client: OpenAiClient,
    override val storage: MutableCodexAgentStorage,
    contextSettings: StateFlow<AgentContextSettings>,
    private val mcpService: McpService,
    loadedLatestIndex: Int,
    initialState: CodexAgentStateValue,
) : CodexAgentState, CoroutineScope by scope {
    private val contextPrefixResolver = AgentContextPrefixResolver(contextSettings)

    override val state: StateFlow<CodexAgentStateValue>
        field = MutableStateFlow(initialState)

    override val latestIndex: StateFlow<Int>
        field = MutableStateFlow(loadedLatestIndex)

    override suspend fun <T> modify(
        block: suspend (MutableCodexAgentStorage) -> T,
    ): T =
        mutate(
            validate = {},
            inFlight = CodexAgentStateValue.ExternalWrite,
        ) {
            try {
                block(storage)
            } finally {
                withContext(NonCancellable) {
                    val index = storage.latestIndex()
                    latestIndex.value = index
                    state.value = storage.stateAt(index)
                }
            }
        }

    override fun requestResponseApi(): Flow<ResponsesStreamEvent> = flow {
        val previousState = state.value
        previousState.requireCanRequestResponseApi()
        val request = ActiveRequest()
        if (!state.compareAndSet(previousState, request.snapshot())) {
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
            val contextPrefix = contextPrefixResolver.resolve(settings).render()
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
                    tools = mcpService.visibleToolSpecs(settings),
                ),
                installationId = clientMetadata.installationId,
                turnMetadata = clientMetadata.turnMetadata,
                windowId = clientMetadata.windowId,
            ).collect { event ->
                if (event !is ResponsesStreamEvent.OutputItemDone) {
                    request.accept(event)
                }
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
                if (event is ResponsesStreamEvent.OutputItemDone) {
                    request.accept(event)
                    request.complete(event.outputIndex)
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
                    tools = mcpService.visibleToolSpecs(settings),
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
            var pendingSnapshot = storage.pendingToolsAt(firstIndex - 1)
            val index = storage.history.revertWithTransaction(firstIndex) {
                storage.stable.revertWithTransaction(firstIndex) {
                    storage.unstable.revertWithTransaction(firstIndex) {
                        storage.timestamp.revertWithTransaction(firstIndex) {
                            var index = storage.latestIndex()
                            for (item in items) {
                                index += 1
                                storage.history[index] = item
                                item.toStableCleanEventOrNull()?.let { event ->
                                    storage.stable[index] = event
                                }
                                item.pendingSnapshotAfter(pendingSnapshot)?.let { nextSnapshot ->
                                    pendingSnapshot = nextSnapshot
                                    storage.unstable[index] = nextSnapshot
                                }
                                storage.timestamp[index] = timestamp
                            }
                            index
                        }
                    }
                }
            }
            latestIndex.value = index
            state.value = storage.stateAt(index)
            index
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun markNewTurn(): Int {
        var isEmpty = false
        return mutate(
            validate = { value ->
                value.requireCanMarkNewTurn()
                isEmpty = value == CodexAgentStateValue.Empty
            },
            inFlight = CodexAgentStateValue.ExternalWrite,
        ) {
            val currentIndex = storage.latestIndex()
            if (isEmpty) {
                return@mutate currentIndex
            }
            val index = currentIndex + 1
            val settings = storage.settings.latestValue().copy(
                turnId = Uuid.generateV7().toString(),
            )
            storage.settings.setWithTransaction(index, settings) {
                storage.timestamp.setWithTransaction(index, now()) { index }
            }
            latestIndex.value = index
            index
        }
    }

    override suspend fun appendUserMessage(content: List<ContentItem>): Int =
        mutate(
            validate = CodexAgentStateValue::requireCanAppendUserMessage,
            inFlight = CodexAgentStateValue.ExternalWrite,
        ) {
            val snapshotIndex = storage.latestIndex()
            val item = ResponseItem.Message(
                role = MessageRole.User,
                content = content,
            )
            val firstIndex = snapshotIndex + 1
            val timestamp = now()
            val stableEvent = checkNotNull(item.toStableCleanEventOrNull())
            val index = storage.history.revertWithTransaction(firstIndex) {
                storage.stable.revertWithTransaction(firstIndex) {
                    storage.timestamp.revertWithTransaction(firstIndex) {
                        storage.history[firstIndex] = item
                        storage.stable[firstIndex] = stableEvent
                        storage.timestamp[firstIndex] = timestamp
                        firstIndex
                    }
                }
            }
            latestIndex.value = index
            state.value = storage.stateAt(index)
            index
        }

    override suspend fun completeToolCall(
        output: ResponseItem.ToolCallOutput,
        completed: StableCleanEvent.CompletedTool,
    ): Int {
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
            val remainingPending = storage.pendingToolsWithout(index - 1, output.callId)
            storage.history.setWithTransaction(index, output) {
                storage.stable.setWithTransaction(index, completed) {
                    storage.unstable.setWithTransaction(index, remainingPending) {
                        storage.timestamp.setWithTransaction(index, now()) { index }
                    }
                }
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
            val currentSettings = storage.settings.latestValue()
            require(currentSettings.collaborationMode != ModeKind.Plan) {
                "update_plan is a TODO/checklist tool and is not allowed in Plan mode."
            }
            val nextState = toolPendingState(pendingCalls.filterNot { call -> call.callId == output.callId })
            val index = storage.latestIndex() + 1
            val remainingPending = storage.pendingToolsWithout(index - 1, output.callId)
            storage.settings.setWithTransaction(index, currentSettings.copy(plan = plan)) {
                storage.history.setWithTransaction(index, output) {
                    storage.stable.setWithTransaction(index, StablePlanUpdate) {
                        storage.unstable.setWithTransaction(index, remainingPending) {
                            storage.timestamp.setWithTransaction(index, now()) { index }
                        }
                    }
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
            val currentSettings = storage.settings.latestValue()
            val index = storage.latestIndex() + 1
            require(index > 0) { "Settings updates require an existing state index." }
            storage.settings.setWithTransaction(index, settings.copy(turnId = currentSettings.turnId)) {
                storage.timestamp.setWithTransaction(index, now()) { index }
            }
            latestIndex.value = index
            index
        }

    private suspend fun appendHistoryItem(
        item: ResponseItem.HistoryItem,
        timestamp: Instant,
        tokenCount: Long?,
    ): Int {
        val index = storage.latestIndex() + 1
        val stableEvent = item.toStableCleanEventOrNull()
        val pendingSnapshot = item.pendingSnapshotAfter(storage.pendingToolsAt(index - 1))
        if (tokenCount == null) {
            storage.history.setWithTransaction(index, item) {
                storage.setCleanTimelinesWithTransaction(index, stableEvent, pendingSnapshot) {
                    storage.timestamp.setWithTransaction(index, timestamp) { index }
                }
            }
        } else {
            storage.tokenCount.setWithTransaction(index, tokenCount) {
                storage.history.setWithTransaction(index, item) {
                    storage.setCleanTimelinesWithTransaction(index, stableEvent, pendingSnapshot) {
                        storage.timestamp.setWithTransaction(index, timestamp) { index }
                    }
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

    private inner class ActiveRequest {
        private var output: ActiveRequestOutput? = null

        fun snapshot(): CodexAgentStateValue.RequestResponse =
            CodexAgentStateValue.RequestResponse.Started

        suspend fun accept(event: ResponsesStreamEvent) {
            when (event) {
                is ResponsesStreamEvent.OutputItemAdded -> {
                    val next = ActiveRequestOutput(event.outputIndex, event.item)
                    output = next
                    state.value = next.state
                    next.emit(event)
                }

                else -> output?.emit(event)
            }
        }

        fun complete(outputIndex: Long) {
            if (output?.outputIndex == outputIndex) {
                output = null
                state.value = snapshot()
            }
        }
    }

    private class ActiveRequestOutput(
        val outputIndex: Long,
        item: ResponseItem,
    ) {
        private val mutableEvents = MutableSharedFlow<ResponsesStreamEvent>(replay = Int.MAX_VALUE)
        val events = mutableEvents.asSharedFlow()
        val state: CodexAgentStateValue.RequestResponse = item.toRequestResponse(events)

        suspend fun emit(event: ResponsesStreamEvent) {
            mutableEvents.emit(event)
        }

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
        is CodexAgentStateValue.RequestResponse,
        CodexAgentStateValue.Compacting,
        -> false
    }

private fun CodexAgentStateValue.requireCanRequestResponseApi() {
    if (!canRequestResponseApi) {
        throw CodexAgentStateInvalidTransitionException("request a response", this)
    }
}

private fun CodexAgentStateValue.requireCanCompact() {
    if (!canCompact) {
        throw CodexAgentStateInvalidTransitionException("compact context", this)
    }
}

private fun CodexAgentStateValue.requireCanAppendUserMessage() {
    if (!canAppendUserMessage) {
        throw CodexAgentStateInvalidTransitionException("append a user message", this)
    }
}

private fun CodexAgentStateValue.requireCanMarkNewTurn() {
    if (!canMarkNewTurn) {
        throw CodexAgentStateInvalidTransitionException("mark a new turn", this)
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

private suspend fun CodexAgentStorage.pendingToolsWithout(
    index: Int,
    callId: String,
): List<PendingToolEvent> {
    val pending = pendingToolsAt(index)
    if (pending.isNotEmpty()) {
        require(pending.any { event -> event.callId == callId }) {
            "Unstable clean timeline does not contain pending call id: $callId"
        }
    }
    return pending.filterNot { event -> event.callId == callId }
}

private suspend fun CodexAgentStorage.pendingToolsAt(index: Int): List<PendingToolEvent> =
    if (unstable.floorToIndex(index) == null) emptyList() else unstable[index]

private fun ResponseItem.HistoryItem.pendingSnapshotAfter(
    current: List<PendingToolEvent>,
): List<PendingToolEvent>? =
    when (this) {
        is ResponseItem.ToolCall -> current + toPendingToolEvent()
        is ResponseItem.ToolCallOutput -> current.filterNot { event -> event.callId == callId }
        else -> null
    }

private suspend inline fun <T> MutableCodexAgentStorage.setCleanTimelinesWithTransaction(
    index: Int,
    stableEvent: StableCleanEvent?,
    pendingSnapshot: List<PendingToolEvent>?,
    block: () -> T,
): T =
    if (stableEvent == null) {
        if (pendingSnapshot == null) {
            block()
        } else {
            unstable.setWithTransaction(index, pendingSnapshot, block)
        }
    } else {
        stable.setWithTransaction(index, stableEvent) {
            if (pendingSnapshot == null) {
                block()
            } else {
                unstable.setWithTransaction(index, pendingSnapshot, block)
            }
        }
    }

private fun toolPendingState(calls: List<ResponseItem.ToolCall>): CodexAgentStateValue {
    return if (calls.isEmpty()) {
        CodexAgentStateValue.ToolCompleted
    } else {
        CodexAgentStateValue.ToolPending(calls)
    }
}

private fun ResponseItem.toRequestResponse(
    events: SharedFlow<ResponsesStreamEvent>,
): CodexAgentStateValue.RequestResponse {
    return when (this) {
        is ResponseItem.Message -> CodexAgentStateValue.RequestResponse.Message(events)
        is ResponseItem.AgentMessage -> CodexAgentStateValue.RequestResponse.AgentMessage(events)
        is ResponseItem.Reasoning -> CodexAgentStateValue.RequestResponse.Reasoning(events)
        is ResponseItem.ToolCall,
        is ResponseItem.ToolCallOutput,
        is ResponseItem.LocalShellCall,
        is ResponseItem.ServerToolSearchCall,
        is ResponseItem.ServerToolSearchOutput,
        is ResponseItem.WebSearchCall,
        is ResponseItem.ImageGenerationCall,
        -> CodexAgentStateValue.RequestResponse.ToolCall(events)

        else -> CodexAgentStateValue.RequestResponse.Unknown(events)
    }
}

private fun now(): Instant = Clock.System.now()
