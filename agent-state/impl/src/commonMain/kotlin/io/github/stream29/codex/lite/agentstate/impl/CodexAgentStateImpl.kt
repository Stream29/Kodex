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
import io.github.stream29.codex.lite.agentstorage.cleanmodels.codexRequestWindowId
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingPlanUpdate
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.UnstableCleanEvent
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
        if (!state.compareAndSet(previousState, CodexAgentStateValue.RequestResponse.Started)) {
            throw CodexAgentStateInvalidTransitionException("start a response request", state.value)
        }

        try {
            var streamingOutput: StreamingOutput? = null

            suspend fun acceptResponseEvent(event: ResponsesStreamEvent) {
                when (event) {
                    is ResponsesStreamEvent.OutputItemAdded -> {
                        val next = StreamingOutput(event.outputIndex, event.item)
                        streamingOutput = next
                        state.value = next.state
                        next.emit(event)
                    }

                    else -> streamingOutput?.emit(event)
                }
            }

            fun completeResponseOutput(outputIndex: Long) {
                if (streamingOutput?.outputIndex == outputIndex) {
                    streamingOutput = null
                    state.value = CodexAgentStateValue.RequestResponse.Started
                }
            }

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
                    acceptResponseEvent(event)
                }
                when (event) {
                    is ResponsesStreamEvent.OutputItemDone -> {
                        val historyItem = event.item as? ResponseItem.HistoryItem
                        if (historyItem != null) {
                            appendResponseHistoryItem(historyItem, now())
                        }
                    }

                    is ResponsesStreamEvent.Completed -> {
                        storage.requireNoPendingServerToolSearch()
                        event.response.usage?.totalTokens?.let { tokenCount ->
                            appendTimestampAndTokenCount(tokenCount)
                        }
                    }

                    else -> Unit
                }
                if (event is ResponsesStreamEvent.OutputItemDone) {
                    acceptResponseEvent(event)
                    completeResponseOutput(event.outputIndex)
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
                prefix = buildRemoteCompactionV2Prefix(input),
                compaction = result.compactionOutput,
                timestamp = now(),
                tokenCount = result.completedResponse?.usage?.totalTokens,
                previousCheckpoint = checkpoint,
                nextWindowId = Uuid.generateV7().toString(),
                settings = settings,
            ).also { latestIndex.value = it }
        }

    override suspend fun injectHistory(events: List<StableCleanEvent>): Int {
        if (events.isEmpty()) {
            return latestIndex.value
        }
        return mutate(
            validate = {},
            inFlight = CodexAgentStateValue.ExternalWrite,
        ) {
            val timestamp = now()
            val firstIndex = storage.latestIndex() + 1
            val index = storage.stable.revertWithTransaction(firstIndex) {
                storage.timestamp.revertWithTransaction(firstIndex) {
                    var index = storage.latestIndex()
                    for (event in events) {
                        index += 1
                        storage.stable[index] = event
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
            val firstIndex = storage.latestIndex() + 1
            val timestamp = now()
            val stableEvent = StableCleanEvent.UserMessage(content)
            val index = storage.stable.revertWithTransaction(firstIndex) {
                storage.timestamp.revertWithTransaction(firstIndex) {
                    storage.stable[firstIndex] = stableEvent
                    storage.timestamp[firstIndex] = timestamp
                    firstIndex
                }
            }
            latestIndex.value = index
            state.value = storage.stateAt(index)
            index
        }

    override suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int {
        var pendingEvents = emptyList<PendingToolEvent>()
        return mutate(
            validate = { value ->
                val pending = value.requireToolPending()
                pendingEvents = pending.events
            },
            inFlight = CodexAgentStateValue.ExternalWrite,
        ) {
            val output = completed.requireProjectedOutput()
            pendingEvents.requireEvent(output.callId)
            val nextState = toolPendingState(
                pendingEvents.filterNot { event -> event.callId == output.callId },
            )
            val index = storage.latestIndex() + 1
            val remainingPending = storage.unstableWithoutPendingToolCall(index - 1, output.callId)
            storage.stable.setWithTransaction(index, completed) {
                storage.unstable.setWithTransaction(index, remainingPending) {
                    storage.timestamp.setWithTransaction(index, now()) { index }
                }
            }
            latestIndex.value = index
            state.value = nextState
            index
        }
    }

    override suspend fun appendPlanUpdate(completed: StablePlanUpdate): Int {
        var pendingEvents = emptyList<PendingToolEvent>()
        return mutate(
            validate = { value ->
                val pending = value.requireToolPending()
                pendingEvents = pending.events
            },
            inFlight = CodexAgentStateValue.ExternalWrite,
        ) {
            val pending = pendingEvents.requireEvent(completed.callId)
            require(pending is PendingPlanUpdate && pending.arguments == completed.arguments) {
                "Plan updates can complete only a pending update_plan function call."
            }
            val currentSettings = storage.settings.latestValue()
            require(currentSettings.collaborationMode != ModeKind.Plan) {
                "update_plan is a TODO/checklist tool and is not allowed in Plan mode."
            }
            val nextState = toolPendingState(
                pendingEvents.filterNot { event -> event.callId == completed.callId },
            )
            val index = storage.latestIndex() + 1
            val remainingPending = storage.unstableWithoutPendingToolCall(index - 1, completed.callId)
            storage.settings.setWithTransaction(index, currentSettings.copy(plan = completed.arguments)) {
                storage.stable.setWithTransaction(
                    index,
                    completed,
                ) {
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

    private suspend fun appendResponseHistoryItem(
        item: ResponseItem.HistoryItem,
        timestamp: Instant,
    ) {
        when (item) {
            is ResponseItem.ToolCall ->
                appendPendingToolEvent(item.toPendingToolEvent(), timestamp)

            is ResponseItem.ServerToolSearchCall ->
                appendPendingServerToolSearch(item, timestamp)

            is ResponseItem.ServerToolSearchOutput ->
                appendServerToolSearchOutput(item, timestamp)

            else ->
                appendStableEvent(item.toIndependentStableCleanEvent(), timestamp)
        }
    }

    private suspend fun appendStableEvent(
        event: StableCleanEvent,
        timestamp: Instant,
    ): Int {
        val index = storage.latestIndex() + 1
        storage.stable.setWithTransaction(index, event) {
            storage.timestamp.setWithTransaction(index, timestamp) { index }
        }
        latestIndex.value = index
        return index
    }

    private suspend fun appendPendingToolEvent(
        event: PendingToolEvent,
        timestamp: Instant,
    ): Int {
        val index = storage.latestIndex() + 1
        val current = storage.unstableEventsAt(index - 1)
        require(current.filterIsInstance<PendingToolEvent>().none { pending -> pending.callId == event.callId }) {
            "Duplicate pending tool call id: ${event.callId}"
        }
        storage.unstable.setWithTransaction(index, current + event) {
            storage.timestamp.setWithTransaction(index, timestamp) { index }
        }
        latestIndex.value = index
        return index
    }

    private suspend fun appendPendingServerToolSearch(
        call: ResponseItem.ServerToolSearchCall,
        timestamp: Instant,
    ): Int {
        val index = storage.latestIndex() + 1
        val current = storage.unstableEventsAt(index - 1)
        require(current.none { event -> event is PendingServerToolSearch }) {
            "A server tool-search call is already waiting for its output."
        }
        storage.unstable.setWithTransaction(index, current + PendingServerToolSearch(call)) {
            storage.timestamp.setWithTransaction(index, timestamp) { index }
        }
        latestIndex.value = index
        return index
    }

    private suspend fun appendServerToolSearchOutput(
        output: ResponseItem.ServerToolSearchOutput,
        timestamp: Instant,
    ): Int {
        val index = storage.latestIndex() + 1
        val current = storage.unstableEventsAt(index - 1)
        val pending = current.filterIsInstance<PendingServerToolSearch>().singleOrNull()
            ?: error("Server tool-search output has no preceding call.")
        val remaining = current.toMutableList().also { events -> events.remove(pending) }
        storage.stable.setWithTransaction(
            index,
            StableCleanEvent.ServerToolSearch(
                call = pending.call,
                output = output,
            ),
        ) {
            storage.unstable.setWithTransaction(index, remaining) {
                storage.timestamp.setWithTransaction(index, timestamp) { index }
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

    private class StreamingOutput(
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
    val items = checkpoint.toResponseHistoryItems().toMutableList<ResponseItem>()
    stable.indexes(checkpoint.historyBaseIndex).collect { eventIndex ->
        if (eventIndex <= index) {
            items += stable[eventIndex].toResponseHistoryItems()
        }
    }
    items += unstableEventsAt(index).flatMap(UnstableCleanEvent::toResponseHistoryItems)
    return items
}

/**
 * Derives the state from the clean stable history and unstable pending tail.
 */
private suspend fun CodexAgentStorage.stateAt(index: Int): CodexAgentStateValue {
    if (index < 0) {
        return CodexAgentStateValue.Empty
    }

    val pending = pendingToolEventsAt(index)
    if (pending.isNotEmpty()) {
        return CodexAgentStateValue.ToolPending(pending)
    }

    var eventIndex = stable.floorToIndex(index)
    while (eventIndex != null) {
        stable[eventIndex].toAgentStateValueOrNull()?.let { return it }
        eventIndex = stable.prevIndex(eventIndex)
    }

    for (event in compaction[index].prefix.asReversed()) {
        event.toAgentStateValueOrNull()?.let { return it }
    }
    return CodexAgentStateValue.Empty
}

private fun StableCleanEvent.toAgentStateValueOrNull(): CodexAgentStateValue? =
    when (this) {
        is StableCleanEvent.UserMessage,
        is StableCleanEvent.AgentMessage,
        -> CodexAgentStateValue.UserMessage

        is StableCleanEvent.AssistantMessage ->
            CodexAgentStateValue.AssistantMessage

        is StableCleanEvent.CompletedTool ->
            CodexAgentStateValue.ToolCompleted

        is StableCleanEvent.DeveloperMessage,
        is StableCleanEvent.Reasoning,
        StableCleanEvent.ContextCompaction,
        -> null
    }

private fun List<PendingToolEvent>.requireEvent(callId: String): PendingToolEvent =
    firstOrNull { event -> event.callId == callId }
        ?: throw IllegalArgumentException("Tool output does not match a pending call id: $callId")

private suspend fun CodexAgentStorage.unstableWithoutPendingToolCall(
    index: Int,
    callId: String,
): List<UnstableCleanEvent> {
    val events = unstableEventsAt(index)
    require(events.filterIsInstance<PendingToolEvent>().any { event -> event.callId == callId }) {
        "Unstable clean timeline does not contain pending call id: $callId"
    }
    return events.filterNot { event -> event is PendingToolEvent && event.callId == callId }
}

private suspend fun CodexAgentStorage.unstableEventsAt(index: Int): List<UnstableCleanEvent> =
    if (unstable.floorToIndex(index) == null) emptyList() else unstable[index]

private suspend fun CodexAgentStorage.pendingToolEventsAt(index: Int): List<PendingToolEvent> =
    unstableEventsAt(index).filterIsInstance<PendingToolEvent>()

private suspend fun CodexAgentStorage.requireNoPendingServerToolSearch() {
    check(unstableEventsAt(latestIndex()).none { event -> event is PendingServerToolSearch }) {
        "Server tool-search call completed without an output."
    }
}

private fun ResponseItem.HistoryItem.toIndependentStableCleanEvent(): StableCleanEvent =
    when (this) {
        is ResponseItem.Message ->
            when (role) {
                MessageRole.User -> StableCleanEvent.UserMessage(content)
                MessageRole.Developer -> StableCleanEvent.DeveloperMessage(content)
                MessageRole.Assistant -> StableCleanEvent.AssistantMessage(
                    content = content,
                    id = id,
                    phase = phase,
                )

                MessageRole.Tool ->
                    error("Tool-role messages are not part of the clean history model.")
            }

        is ResponseItem.AgentMessage ->
            StableCleanEvent.AgentMessage(
                author = author,
                recipient = recipient,
                content = content,
            )

        is ResponseItem.Reasoning ->
            StableCleanEvent.Reasoning(this)

        is ResponseItem.WebSearchCall ->
            StableCleanEvent.WebSearchCall(this)

        is ResponseItem.ImageGenerationCall ->
            StableCleanEvent.ImageGenerationCall(this)

        is ResponseItem.ToolCall,
        is ResponseItem.ToolCallOutput,
        is ResponseItem.ServerToolSearchCall,
        is ResponseItem.ServerToolSearchOutput,
        is ResponseItem.LocalShellCall,
        is ResponseItem.Compaction,
        is ResponseItem.CompactionSummary,
        is ResponseItem.ContextCompaction,
        -> error("Response item ${this::class.simpleName} requires a dedicated clean-history path.")
    }

private fun StableCleanEvent.CompletedTool.requireProjectedOutput(): ResponseItem.ToolCallOutput =
    toResponseHistoryItems()
        .filterIsInstance<ResponseItem.ToolCallOutput>()
        .singleOrNull()
        ?: throw IllegalArgumentException(
            "Completed local tool events must project exactly one tool output.",
        )

private fun toolPendingState(events: List<PendingToolEvent>): CodexAgentStateValue {
    return if (events.isEmpty()) {
        CodexAgentStateValue.ToolCompleted
    } else {
        CodexAgentStateValue.ToolPending(events)
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
