package io.github.stream29.kodex.agentstate.impl

import io.github.stream29.kodex.agentcontext.prefix.render.renderPlanningInstructions
import io.github.stream29.kodex.agentcontext.prefix.render.renderMultiAgentMode
import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentcontext.prefix.impl.AgentContextPrefixResolver
import io.github.stream29.kodex.agentcontext.prefix.render.render
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.RequestFinish
import io.github.stream29.kodex.agentstate.contract.canAppendUserMessage
import io.github.stream29.kodex.agentstate.contract.canCompact
import io.github.stream29.kodex.agentstate.contract.canMarkNewTurn
import io.github.stream29.kodex.agentstate.contract.canRequestResponseApi
import io.github.stream29.kodex.agentstate.tool.toPendingToolEvent
import io.github.stream29.kodex.agentstate.tool.visibleToolSpecs
import io.github.stream29.kodex.agentstorage.cleanmodels.CleanCompactionCheckpoint
import io.github.stream29.kodex.agentstorage.cleanmodels.codexRequestWindowId
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.appendCompactionCheckpoint
import io.github.stream29.kodex.agentstorage.contract.indexes
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.contract.latestValue
import io.github.stream29.kodex.agentstorage.contract.prevIndex
import io.github.stream29.kodex.agentstorage.contract.revertWithTransaction
import io.github.stream29.kodex.agentstorage.contract.setWithTransaction
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.CodexResponsesMetadata
import io.github.stream29.kodex.openai.CodexResponsesRequestKind
import io.github.stream29.kodex.openai.CompactionImplementation
import io.github.stream29.kodex.openai.CompactionStrategy
import io.github.stream29.kodex.openai.CompactionTurnMetadata
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiResponseStreamIncompleteException
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
public suspend fun CoroutineScope.KodexAgentState(
    client: OpenAiClient,
    storage: MutableKodexAgentStorage,
    contextSettings: StateFlow<AgentContextSettings>,
    mcpService: McpService,
): KodexAgentState {
    val stateScope = supervisorChildScope()
    try {
        val loadedLatestIndex = storage.latestIndex()
        return KodexAgentStateImpl(
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
private class KodexAgentStateImpl(
    scope: CoroutineScope,
    private val client: OpenAiClient,
    override val storage: MutableKodexAgentStorage,
    contextSettings: StateFlow<AgentContextSettings>,
    private val mcpService: McpService,
    loadedLatestIndex: Int,
    initialState: KodexAgentStateValue,
) : KodexAgentState, CoroutineScope by scope {
    private val contextPrefixResolver = AgentContextPrefixResolver(contextSettings)
    private val writeMutex = Mutex()

    override val state: StateFlow<KodexAgentStateValue>
        field = MutableStateFlow(initialState)

    override val latestIndex: StateFlow<Int>
        field = MutableStateFlow(loadedLatestIndex)

    override suspend fun <T> modify(
        block: suspend (MutableKodexAgentStorage) -> T,
    ): T =
        mutate(
            validate = {},
            inFlight = KodexAgentStateValue.ExternalWrite,
        ) {
            block(storage)
        }

    override suspend fun requestResponseApi(): RequestFinish {
        val snapshotIndex = startRequestResponse()
        var terminalReason: RequestFinish? = null
        try {
            var streamingOutput: StreamingOutput? = null

            suspend fun acceptResponseEvent(event: ResponsesStreamEvent) {
                when (event) {
                    is ResponsesStreamEvent.OutputItemAdded -> {
                        val next = StreamingOutput(event.outputIndex, event.item)
                        streamingOutput = next
                        next.emit(event)
                        state.value = next.state
                    }

                    else -> streamingOutput?.emit(event)
                }
            }

            fun completeResponseOutput(outputIndex: Long) {
                if (streamingOutput?.outputIndex == outputIndex) {
                    streamingOutput = null
                    state.value = KodexAgentStateValue.RequestResponse.Started
                }
            }

            val settings = storage.settings[snapshotIndex]
            val durableInput = storage.modelInputAt(snapshotIndex)
            val planningContext = ResponseItem.Message(
                role = MessageRole.Developer,
                content = listOf(ContentItem.InputText(renderPlanningInstructions())),
            )
            val multiAgentContext = ResponseItem.Message(
                role = MessageRole.Developer,
                content = listOf(ContentItem.InputText(settings.agentMode.renderMultiAgentMode())),
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
                    input = listOf(planningContext, multiAgentContext) + contextPrefix + durableInput,
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
                            writeResponseResult {
                                appendResponseHistoryItem(historyItem, now())
                            }
                        }
                    }

                    is ResponsesStreamEvent.Completed -> {
                        writeResponseResult {
                            storage.requireNoPendingServerToolSearch()
                            event.response.usage?.totalTokens?.let { tokenCount ->
                                appendTimestampAndTokenCount(tokenCount)
                            }
                        }
                        terminalReason = if (event.response.endTurn == false) {
                            RequestFinish.Continue
                        } else {
                            RequestFinish.Finish
                        }
                    }

                    is ResponsesStreamEvent.Failed -> {
                        terminalReason = RequestFinish.Retryable
                    }

                    is ResponsesStreamEvent.Incomplete -> {
                        throw OpenAiResponseStreamIncompleteException(event.response.incompleteDetails)
                    }

                    else -> Unit
                }
                if (event is ResponsesStreamEvent.OutputItemDone) {
                    acceptResponseEvent(event)
                    completeResponseOutput(event.outputIndex)
                }
            }
        } finally {
            finishRequestResponse()
        }

        return terminalReason ?: RequestFinish.Retryable
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun compact(
        trigger: CompactionTrigger,
        reason: CompactionReason,
        phase: CompactionPhase,
    ): Int =
        mutate(
            validate = KodexAgentStateValue::requireCanCompact,
            inFlight = KodexAgentStateValue.Compacting,
        ) {
            val snapshotIndex = storage.latestIndex()
            check(snapshotIndex >= 0) { "Cannot compact an agent without initial state." }

            val settings = storage.settings[snapshotIndex]
            val cleanInput = storage.cleanModelInputAt(snapshotIndex)
            val checkpoint = cleanInput.checkpoint
            val input = cleanInput.toResponseItems()
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
                prefix = buildRemoteCompactionV2Prefix(cleanInput.stableEventsForRetention()),
                compaction = StableCleanEvent.ContextCompaction(
                    id = result.compactionOutput.id,
                    encryptedContent = result.compactionOutput.encryptedContent,
                ),
                timestamp = now(),
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
            inFlight = KodexAgentStateValue.ExternalWrite,
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
                isEmpty = value == KodexAgentStateValue.Empty
            },
            inFlight = KodexAgentStateValue.ExternalWrite,
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
            validate = KodexAgentStateValue::requireCanAppendUserMessage,
            inFlight = KodexAgentStateValue.ExternalWrite,
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
            inFlight = KodexAgentStateValue.ExternalWrite,
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

    override suspend fun updateSettings(settings: KodexAgentSettings): Int =
        writeMutex.withLock {
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
        val state: KodexAgentStateValue.RequestResponse = item.toRequestResponse(events)

        suspend fun emit(event: ResponsesStreamEvent) {
            mutableEvents.emit(event)
        }

    }

    private suspend fun startRequestResponse(): Int =
        writeMutex.withLock {
            val currentState = state.value
            if (!currentState.isStable) {
                throw KodexAgentStateInvalidTransitionException("start an atomic operation", currentState)
            }
            currentState.requireCanRequestResponseApi()
            val snapshotIndex = storage.latestIndex()
            check(snapshotIndex >= 0) { "Cannot request a response without initial state." }
            state.value = KodexAgentStateValue.RequestResponse.Started
            snapshotIndex
        }

    private suspend inline fun <T> writeResponseResult(block: () -> T): T =
        writeMutex.withLock {
            val currentState = state.value
            if (currentState !is KodexAgentStateValue.RequestResponse) {
                throw KodexAgentStateInvalidTransitionException("write a response result", currentState)
            }
            block()
        }

    private suspend fun finishRequestResponse() {
        withContext(NonCancellable) {
            writeMutex.withLock {
                val index = storage.latestIndex()
                latestIndex.value = index
                state.value = storage.stateAt(index)
            }
        }
    }

    private suspend inline fun <T> mutate(
        validate: (KodexAgentStateValue) -> Unit,
        inFlight: KodexAgentStateValue,
        block: () -> T,
    ): T {
        writeMutex.lock()
        try {
            val currentState = state.value
            if (!currentState.isStable) {
                throw KodexAgentStateInvalidTransitionException("start an atomic operation", currentState)
            }
            validate(currentState)
            state.value = inFlight
            try {
                return block()
            } finally {
                withContext(NonCancellable) {
                    val index = storage.latestIndex()
                    latestIndex.value = index
                    state.value = storage.stateAt(index)
                }
            }
        } finally {
            writeMutex.unlock()
        }
    }
}

public class KodexAgentStateInvalidTransitionException(
    operation: String,
    public val currentState: KodexAgentStateValue,
) : IllegalStateException("Cannot $operation while agent state is $currentState.")

private val KodexAgentStateValue.isStable: Boolean
    get() = when (this) {
        KodexAgentStateValue.Empty,
        KodexAgentStateValue.UserMessage,
        KodexAgentStateValue.AssistantMessage,
        is KodexAgentStateValue.ToolPending,
        KodexAgentStateValue.ToolCompleted,
        -> true

        KodexAgentStateValue.ExternalWrite,
        is KodexAgentStateValue.RequestResponse,
        KodexAgentStateValue.Compacting,
        -> false
    }

private fun KodexAgentStateValue.requireCanRequestResponseApi() {
    if (!canRequestResponseApi) {
        throw KodexAgentStateInvalidTransitionException("request a response", this)
    }
}

private fun KodexAgentStateValue.requireCanCompact() {
    if (!canCompact) {
        throw KodexAgentStateInvalidTransitionException("compact context", this)
    }
}

private fun KodexAgentStateValue.requireCanAppendUserMessage() {
    if (!canAppendUserMessage) {
        throw KodexAgentStateInvalidTransitionException("append a user message", this)
    }
}

private fun KodexAgentStateValue.requireCanMarkNewTurn() {
    if (!canMarkNewTurn) {
        throw KodexAgentStateInvalidTransitionException("mark a new turn", this)
    }
}

private fun KodexAgentStateValue.requireToolPending(): KodexAgentStateValue.ToolPending =
    this as? KodexAgentStateValue.ToolPending
        ?: throw KodexAgentStateInvalidTransitionException("complete tool calls", this)

private data class CleanModelInput(
    val checkpoint: CleanCompactionCheckpoint,
    val stableEvents: List<StableCleanEvent>,
) {
    fun stableEventsForRetention(): List<StableCleanEvent> =
        checkpoint.prefix + stableEvents

    fun toResponseItems(): List<ResponseItem> =
        checkpoint.toResponseHistoryItems() +
            stableEvents.flatMap(StableCleanEvent::toResponseHistoryItems)
}

private suspend fun KodexAgentStorage.cleanModelInputAt(index: Int): CleanModelInput {
    val checkpoint = compaction[index]
    val stableEvents = buildList {
        stable.indexes(checkpoint.historyBaseIndex).collect { eventIndex ->
            if (eventIndex <= index) {
                add(stable[eventIndex])
            }
        }
    }
    return CleanModelInput(
        checkpoint = checkpoint,
        stableEvents = stableEvents,
    )
}

private suspend fun KodexAgentStorage.modelInputAt(index: Int): List<ResponseItem> =
    cleanModelInputAt(index).toResponseItems()

/**
 * Derives the state from the clean stable history and unstable pending tail.
 */
private suspend fun KodexAgentStorage.stateAt(index: Int): KodexAgentStateValue {
    if (index < 0) {
        return KodexAgentStateValue.Empty
    }

    val pending = pendingToolEventsAt(index)
    if (pending.isNotEmpty()) {
        return KodexAgentStateValue.ToolPending(pending)
    }

    var eventIndex = stable.floorToIndex(index)
    while (eventIndex != null) {
        stable[eventIndex].toAgentStateValueOrNull()?.let { return it }
        eventIndex = stable.prevIndex(eventIndex)
    }

    for (event in compaction[index].prefix.asReversed()) {
        event.toAgentStateValueOrNull()?.let { return it }
    }
    return KodexAgentStateValue.Empty
}

private fun StableCleanEvent.toAgentStateValueOrNull(): KodexAgentStateValue? =
    when (this) {
        is StableCleanEvent.UserMessage,
        is StableCleanEvent.AgentMessage,
        -> KodexAgentStateValue.UserMessage

        is StableCleanEvent.AssistantMessage ->
            KodexAgentStateValue.AssistantMessage

        is StableCleanEvent.CompletedTool ->
            KodexAgentStateValue.ToolCompleted

        is StableCleanEvent.DeveloperMessage,
        is StableCleanEvent.Reasoning,
        is StableCleanEvent.ContextCompaction,
        -> null
    }

private fun List<PendingToolEvent>.requireEvent(callId: String): PendingToolEvent =
    firstOrNull { event -> event.callId == callId }
        ?: throw IllegalArgumentException("Tool output does not match a pending call id: $callId")

private suspend fun KodexAgentStorage.unstableWithoutPendingToolCall(
    index: Int,
    callId: String,
): List<UnstableCleanEvent> {
    val events = unstableEventsAt(index)
    require(events.filterIsInstance<PendingToolEvent>().any { event -> event.callId == callId }) {
        "Unstable clean timeline does not contain pending call id: $callId"
    }
    return events.filterNot { event -> event is PendingToolEvent && event.callId == callId }
}

private suspend fun KodexAgentStorage.unstableEventsAt(index: Int): List<UnstableCleanEvent> =
    if (unstable.floorToIndex(index) == null) emptyList() else unstable[index]

private suspend fun KodexAgentStorage.pendingToolEventsAt(index: Int): List<PendingToolEvent> =
    unstableEventsAt(index).filterIsInstance<PendingToolEvent>()

private suspend fun KodexAgentStorage.requireNoPendingServerToolSearch() {
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

private fun toolPendingState(events: List<PendingToolEvent>): KodexAgentStateValue {
    return if (events.isEmpty()) {
        KodexAgentStateValue.ToolCompleted
    } else {
        KodexAgentStateValue.ToolPending(events)
    }
}

private fun ResponseItem.toRequestResponse(
    events: SharedFlow<ResponsesStreamEvent>,
): KodexAgentStateValue.RequestResponse {
    return when (this) {
        is ResponseItem.Message -> KodexAgentStateValue.RequestResponse.Message(events)
        is ResponseItem.AgentMessage -> KodexAgentStateValue.RequestResponse.AgentMessage(events)
        is ResponseItem.Reasoning -> KodexAgentStateValue.RequestResponse.Reasoning(events)
        is ResponseItem.ToolCall,
        is ResponseItem.ToolCallOutput,
        is ResponseItem.LocalShellCall,
        is ResponseItem.ServerToolSearchCall,
        is ResponseItem.ServerToolSearchOutput,
        is ResponseItem.WebSearchCall,
        is ResponseItem.ImageGenerationCall,
        -> KodexAgentStateValue.RequestResponse.ToolCall(events)

        else -> KodexAgentStateValue.RequestResponse.Unknown(events)
    }
}

private fun now(): Instant = Clock.System.now()
