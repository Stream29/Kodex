package io.github.stream29.kodex.agentstate.impl

import io.github.stream29.kodex.agentcontext.prefix.render.renderPlanningInstructions
import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentcontext.prefix.impl.AgentContextPrefixResolver
import io.github.stream29.kodex.agentcontext.prefix.render.render
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.RequestFinish
import io.github.stream29.kodex.agentstate.contract.canAppendUserMessage
import io.github.stream29.kodex.agentstate.contract.canCompact
import io.github.stream29.kodex.agentstate.contract.canRequestResponseApi
import io.github.stream29.kodex.agentstate.tool.toPendingToolEvent
import io.github.stream29.kodex.agentstate.tool.visibleToolSpecs
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableImageGenerationCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableReasoning
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWebSearchCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingServerToolSearch
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.ext.appendCompaction
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.contract.revert
import io.github.stream29.kodex.agentstorage.contract.ext.activeMessageWindowAt
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.codexRequestWindowId
import io.github.stream29.kodex.openai.CodexResponsesMetadata
import io.github.stream29.kodex.openai.CodexResponsesRequestKind
import io.github.stream29.kodex.openai.CompactionImplementation
import io.github.stream29.kodex.openai.CompactionStrategy
import io.github.stream29.kodex.openai.CompactionTurnMetadata
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.MessagePhase
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
            val messageWindow = storage.activeMessageWindowAt(snapshotIndex)
            val durableInput = messageWindow.flatMap(StableCleanEvent::toResponseHistoryItems)
            val planningContext = ResponseItem.Message(
                role = MessageRole.Developer,
                content = listOf(ContentItem.InputText(renderPlanningInstructions())),
            )
            val contextPrefix = contextPrefixResolver.resolve(settings).render()
            val threadId = storage.id.toCodexThreadId()
            val windowId = settings.codexRequestWindowId(threadId)
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
                    input = listOf(planningContext) + contextPrefix + durableInput,
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
    ): Int {
        val snapshot = startCompaction()
        return try {
            val messageWindow = storage.activeMessageWindowAt(snapshot.index)
            val input = messageWindow.flatMap(StableCleanEvent::toResponseHistoryItems)
            val threadId = storage.id.toCodexThreadId()
            val windowId = snapshot.settings.codexRequestWindowId(threadId)
            val metadata = CodexResponsesMetadata(
                installationId = snapshot.settings.installationId,
                sessionId = snapshot.settings.sessionId,
                threadId = threadId,
                turnId = snapshot.settings.turnId,
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
                request = snapshot.settings.toResponsesApiRequest(
                    input = input + ResponseItem.CompactionTrigger,
                    clientMetadata = clientMetadata,
                    tools = mcpService.visibleToolSpecs(snapshot.settings),
                ),
                installationId = clientMetadata.installationId,
                turnMetadata = clientMetadata.turnMetadata,
                windowId = clientMetadata.windowId,
            )
            commitCompaction(
                previousSettings = snapshot.settings,
                output = StableContextCompaction(
                    id = result.compactionOutput.id,
                    encryptedContent = result.compactionOutput.encryptedContent,
                ),
            )
        } finally {
            finishCompaction()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun injectHistory(events: List<StableIndexEvent.Steerable>): Int {
        if (events.isEmpty()) {
            return latestIndex.value
        }
        return mutate(
            validate = {},
            inFlight = KodexAgentStateValue.ExternalWrite,
        ) {
            val timestamp = now()
            val firstIndex = storage.latestIndex() + 1
            var currentSettings = storage.settings[storage.latestIndex()]
            var previousMessageStartsNewTurn = storage.startsNewTurnBefore(firstIndex)
            var index = storage.latestIndex()
            for (event in events) {
                index += 1
                val startsNewTurn = event is StableUserMessage &&
                    previousMessageStartsNewTurn
                if (startsNewTurn) {
                    currentSettings = currentSettings.copy(
                        turnId = Uuid.generateV7().toString(),
                    )
                    storage.settings[index] = currentSettings
                }
                storage.index[index] = event
                storage.timestamp[index] = timestamp
                previousMessageStartsNewTurn = when (event) {
                    is StableAssistantMessage -> event.startsNewTurn()
                    is StableUserMessage -> false
                    else -> previousMessageStartsNewTurn
                }
            }
            latestIndex.value = index
            state.value = storage.stateAt(index)
            index
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun appendUserMessage(content: List<ContentItem>): Int =
        mutate(
            validate = KodexAgentStateValue::requireCanAppendUserMessage,
            inFlight = KodexAgentStateValue.ExternalWrite,
        ) {
            val firstIndex = storage.latestIndex() + 1
            val timestamp = now()
            val stableEvent = StableUserMessage(content)
            val settings = storage.settings[storage.latestIndex()]
            val settingsForNewTurn = storage.startsNewTurnBefore(firstIndex)
                .takeIf { it }
                ?.let { settings.copy(turnId = Uuid.generateV7().toString()) }
            storage.index[firstIndex] = stableEvent
            settingsForNewTurn?.let { value -> storage.settings[firstIndex] = value }
            storage.timestamp[firstIndex] = timestamp
            val index = firstIndex
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
            when (completed) {
                is StableIndexEvent -> {
                    storage.index[index] = completed
                    storage.unstable[index] = remainingPending
                    storage.timestamp[index] = now()
                }

                is StableWorkEvent -> {
                    storage.work[index] = completed
                    storage.unstable[index] = remainingPending
                    storage.timestamp[index] = now()
                }

                else -> error("Unsupported completed tool event: ${completed::class.simpleName}.")
            }
            latestIndex.value = index
            state.value = nextState
            index
        }
    }

    override suspend fun updateSettings(settings: KodexAgentSettings): Int =
        writeMutex.withLock {
            val index = storage.latestIndex() + 1
            require(index > 0) { "Settings updates require an existing state index." }
            storage.settings[index] = settings
            storage.timestamp[index] = now()
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
        when (event) {
            is StableIndexEvent -> {
                storage.index[index] = event
                storage.timestamp[index] = timestamp
            }

            is StableWorkEvent -> {
                storage.work[index] = event
                storage.timestamp[index] = timestamp
            }

            else -> error("Unsupported stable event: ${event::class.simpleName}.")
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
        storage.unstable[index] = current + event
        storage.timestamp[index] = timestamp
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
        storage.unstable[index] = current + PendingServerToolSearch(call)
        storage.timestamp[index] = timestamp
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
        storage.work[index] = StableServerToolSearch(
            call = pending.call,
            output = output,
        )
        storage.unstable[index] = remaining
        storage.timestamp[index] = timestamp
        latestIndex.value = index
        return index
    }

    private suspend fun appendTimestampAndTokenCount(tokenCount: Long) {
        val index = storage.latestIndex() + 1
        storage.tokenCount[index] = tokenCount
        storage.timestamp[index] = now()
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

    private data class CompactionSnapshot(
        val index: Int,
        val settings: KodexAgentSettings,
    )

    private suspend fun startCompaction(): CompactionSnapshot =
        writeMutex.withLock {
            val currentState = state.value
            if (!currentState.isStable) {
                throw KodexAgentStateInvalidTransitionException(
                    "start an atomic operation",
                    currentState,
                )
            }
            currentState.requireCanCompact()
            val index = storage.latestIndex()
            check(index >= 0) { "Cannot compact an agent without initial state." }
            state.value = KodexAgentStateValue.Compacting
            CompactionSnapshot(
                index = index,
                settings = storage.settings[index],
            )
        }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun commitCompaction(
        previousSettings: KodexAgentSettings,
        output: StableContextCompaction,
    ): Int = writeMutex.withLock {
        check(state.value == KodexAgentStateValue.Compacting) {
            "Compaction ownership was lost before its result was committed."
        }
        val index = storage.appendCompaction(
            output = output,
            timestamp = now(),
            nextWindowId = Uuid.generateV7().toString(),
            previousSettings = previousSettings,
        )
        latestIndex.value = index
        index
    }

    private suspend fun finishCompaction() {
        withContext(NonCancellable) {
            writeMutex.withLock {
                val index = storage.latestIndex()
                latestIndex.value = index
                state.value = storage.stateAt(index)
            }
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

private fun KodexAgentStateValue.requireToolPending(): KodexAgentStateValue.ToolPending =
    this as? KodexAgentStateValue.ToolPending
        ?: throw KodexAgentStateInvalidTransitionException("complete tool calls", this)

/**
 * Derives the state from the independent index/work timelines and unstable
 * pending tail. The two stable timelines are never merged for this scan.
 */
private suspend fun KodexAgentStorage.stateAt(index: Int): KodexAgentStateValue {
    if (index < 0) {
        return KodexAgentStateValue.Empty
    }

    val pending = pendingToolEventsAt(index)
    if (pending.isNotEmpty()) {
        return KodexAgentStateValue.ToolPending(pending)
    }

    val indexCandidate = latestStateCandidate(
        indexes = this.index.indexesIn(0..index).asReversed(),
        read = { eventIndex -> this.index.getExact(eventIndex) as? StableCleanEvent },
    )
    val workCandidate = latestStateCandidate(
        indexes = work.indexesIn(0..index).asReversed(),
        read = { eventIndex -> work.getExact(eventIndex) },
    )
    return listOfNotNull(indexCandidate, workCandidate)
        .maxByOrNull { candidate -> candidate.first }
        ?.second
        ?: KodexAgentStateValue.Empty
}

private fun StableCleanEvent.toAgentStateValueOrNull(): KodexAgentStateValue? =
    when (this) {
        is StableUserMessage,
        is StableAgentMessage,
        -> KodexAgentStateValue.UserMessage

        is StableAssistantMessage ->
            KodexAgentStateValue.AssistantMessage

        is StableCleanEvent.CompletedTool ->
            KodexAgentStateValue.ToolCompleted

        else -> null
    }

private suspend fun <T : StableCleanEvent> KodexAgentStorage.latestStateCandidate(
    indexes: List<Int>,
    read: suspend (Int) -> T?,
): Pair<Int, KodexAgentStateValue>? {
    for (eventIndex in indexes) {
        val event = read(eventIndex) ?: continue
        event.toAgentStateValueOrNull()?.let { state ->
            return eventIndex to state
        }
    }
    return null
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

private suspend fun KodexAgentStorage.startsNewTurnBefore(
    index: Int,
): Boolean {
    for (eventIndex in this.index.indexesIn(0 until index).asReversed()) {
        val event = this.index.getExact(eventIndex)
        when (event) {
            is StableAssistantMessage -> return event.startsNewTurn()
            is StableUserMessage -> return false
            else -> Unit
        }
    }
    return false
}

private fun StableAssistantMessage.startsNewTurn(): Boolean =
    phase != MessagePhase.Commentary

private suspend fun KodexAgentStorage.requireNoPendingServerToolSearch() {
    check(unstableEventsAt(latestIndex()).none { event -> event is PendingServerToolSearch }) {
        "Server tool-search call completed without an output."
    }
}

private fun ResponseItem.HistoryItem.toIndependentStableCleanEvent(): StableCleanEvent =
    when (this) {
        is ResponseItem.Message ->
            when (role) {
                MessageRole.User -> StableUserMessage(content)
                MessageRole.Developer -> StableDeveloperMessage(content)
                MessageRole.Assistant -> StableAssistantMessage(
                    content = content,
                    id = id,
                    phase = phase,
                )

                MessageRole.Tool ->
                    error("Tool-role messages are not part of the clean history model.")
            }

        is ResponseItem.AgentMessage ->
            StableAgentMessage(
                author = author,
                recipient = recipient,
                content = content,
            )

        is ResponseItem.Reasoning ->
            StableReasoning(this)

        is ResponseItem.WebSearchCall ->
            StableWebSearchCall(this)

        is ResponseItem.ImageGenerationCall ->
            StableImageGenerationCall(this)

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
