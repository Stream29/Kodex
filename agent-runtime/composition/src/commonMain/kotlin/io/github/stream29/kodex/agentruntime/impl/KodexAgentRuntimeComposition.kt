package io.github.stream29.kodex.agentruntime.impl

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentruntime.decorator.compact.compactionRuntime
import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.contract.AgentStopEvent
import io.github.stream29.kodex.agentruntime.contract.ConcurrentAgentRuntimeResumeException
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentruntime.decorator.tool.toolRuntime
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.canAppendUserMessage
import io.github.stream29.kodex.agentstate.contract.clearPending
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTerminalExitedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.toFailedToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwaitEventToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.agentstorage.contract.getSteerAt
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.tool.awaitevent.AwaitEventOutput
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecToolClient
import io.github.stream29.kodex.utils.logging.agent
import io.github.stream29.kodex.utils.logging.global
import io.github.stream29.kodex.utils.logging.session
import io.github.stream29.kodex.utils.logging.tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock

/** Builds the root Agent runtime. */
public actual fun KodexAgentState.buildMasterAgentRuntime(
    dependencies: KodexAgentDependencies,
): AgentRuntime {
    val sessionLogger = RuntimeLogger.session(storage.uri)
    return try {
        buildAgentRuntime(sessionLogger) { agentLogger ->
            masterRuntimeLayer(
                dependencies = dependencies,
                logger = agentLogger,
            )
        }.also {
            sessionLogger.info { "Session opened." }
            coroutineContext.job.invokeOnCompletion { failure ->
                sessionLogger.logClosed(
                    failure = failure,
                    closedMessage = "Session closed.",
                    failedMessage = "Session closed after a failure.",
                )
            }
        }
    } catch (failure: Throwable) {
        sessionLogger.error(failure) { "Session failed to open." }
        throw failure
    }
}

private fun KodexAgentState.masterRuntimeLayer(
    dependencies: KodexAgentDependencies,
    logger: KLogger,
): AgentRuntimeLayer {
    val fixedTools = fixedTools(dependencies)
    return fixedTools.tools.closeOnFailure {
        AgentRuntimeLayer(
            delegate = compactionRuntime(
                modelCatalog = dependencies.modelCatalog,
                logger = logger,
            )
                .toolRuntime(
                    tools = fixedTools.tools,
                    logger = logger,
                )
                .also {
                    coroutineContext.job.invokeOnCompletion {
                        fixedTools.tools.closeAll(logger)
                    }
                },
            unifiedExecToolClient = fixedTools.unifiedExecToolClient,
        )
    }
}

private fun KodexAgentState.buildAgentRuntime(
    sessionLogger: KLogger,
    buildLayer: (KLogger) -> AgentRuntimeLayer,
): AgentRuntime {
    val logger = sessionLogger.agent()
    return try {
        val layer = buildLayer(logger)
        logger.info { "Agent runtime opened." }
        coroutineContext.job.invokeOnCompletion { failure ->
            logger.logClosed(
                failure = failure,
                closedMessage = "Agent runtime closed.",
                failedMessage = "Agent runtime closed after a failure.",
            )
        }
        AgentRuntimeImpl(
            delegate = layer.delegate,
            unifiedExecToolClient = layer.unifiedExecToolClient,
            logger = logger,
        )
    } catch (failure: Throwable) {
        logger.error(failure) { "Agent runtime failed to open." }
        throw failure
    }
}

private data class AgentRuntimeLayer(
    val delegate: ResumableAgentLayer,
    val unifiedExecToolClient: UnifiedExecToolClient,
)

@OptIn(ExperimentalAtomicApi::class)
private class AgentRuntimeImpl(
    private val delegate: ResumableAgentLayer,
    override val unifiedExecToolClient: UnifiedExecToolClient,
    private val logger: KLogger,
) : AgentRuntime, KodexAgentState by delegate {
    private val runningTurnSlot: MutableStateFlow<Job?> = MutableStateFlow(null)
    private val needsSubmittedSteerDrain: AtomicBoolean = AtomicBoolean(false)
    private val mutableStopEvents: MutableSharedFlow<AgentStopEvent> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val runningTurn: StateFlow<Job?> = runningTurnSlot.asStateFlow()
    override val stopEvents: Flow<AgentStopEvent> = mutableStopEvents.asSharedFlow()

    override suspend fun resume() {
        var completedNormally = false
        withRunningTurn {
            val before = delegate.latestIndex.value
            val acceptedStart = isAcceptedResumeStart(before)
            logger.info { "Agent turn started." }
            try {
                var parkedOnAwaitEvent = false
                while (true) {
                    needsSubmittedSteerDrain.store(false)
                    delegate.resume()
                    val pending = delegate.state.value as? KodexAgentStateValue.ToolPending
                        ?: break
                    val unsupported = pending.events.filterNot { it is PendingAwaitEventToolEvent }
                    if (unsupported.isNotEmpty()) {
                        for (unmatched in unsupported) {
                            val label = unmatched.loggingToolNameOrNull() ?: unmatched.callId
                            completeToolCall(
                                unmatched.toFailedToolEvent(
                                    "unsupported tool call: $label",
                                ),
                            )
                        }
                        continue
                    }
                    val awaitEvents = pending.events.filterIsInstance<PendingAwaitEventToolEvent>()
                    if (awaitEvents.isEmpty()) {
                        break
                    }
                    if (!resolvePendingAwaitEvents(awaitEvents)) {
                        parkedOnAwaitEvent = true
                        break
                    }
                }
                if (!parkedOnAwaitEvent) {
                    logger.logPendingHostToolCalls(delegate.state.value)
                    emitStopEventsOnNormalStop(before)
                }
                logger.info { "Agent turn completed." }
                completedNormally = true
            } catch (cancellation: CancellationException) {
                val cleanupFailure = withContext(NonCancellable) {
                    runCatching { delegate.clearPending() }.exceptionOrNull()
                }
                if (cleanupFailure != null && cleanupFailure !== cancellation) {
                    cancellation.addSuppressed(cleanupFailure)
                }
                logger.info { "Agent turn cancelled." }
                throw cancellation
            } catch (failure: Throwable) {
                if (acceptedStart) {
                    mutableStopEvents.tryEmit(AgentStopEvent.UnhandledError(failure.message))
                }
                logger.error(failure) { "Agent turn failed." }
                throw failure
            }
        }
        if (completedNormally && needsSubmittedSteerDrain.exchange(false)) {
            recheckSubmittedSteerAfterTurnRelease()
        }
    }

    override suspend fun compact(
        trigger: CompactionTrigger,
        reason: CompactionReason,
        phase: CompactionPhase,
    ): Int =
        withRunningTurn {
            try {
                delegate.compact(trigger, reason, phase)
            } finally {
                // Per AgentRuntime.compact contract, standalone compaction preserves queued steer
                // without consuming it or automatically launching a resume turn on completion.
                needsSubmittedSteerDrain.store(false)
            }
        }

    override suspend fun submit(events: List<StableIndexEvent.Steerable>) {
        if (events.isEmpty()) return
        delegate.steer(events)
        ensureTurnRunningForSubmittedSteer()
    }

    override suspend fun submitAskUserAnswer(
        askId: Int,
        response: RequestUserInputResponse,
    ) {
        delegate.answerAskUser(askId, response)
        ensureTurnRunningForSubmittedSteer()
    }

    private fun ensureTurnRunningForSubmittedSteer() {
        needsSubmittedSteerDrain.store(true)
        if (runningTurnSlot.value == null && isActive) {
            launchBackgroundResume()
        }
    }

    private suspend fun recheckSubmittedSteerAfterTurnRelease() {
        if (runningTurnSlot.value == null && isActive && hasWakeableSteer()) {
            launchBackgroundResume()
        }
    }

    private fun launchBackgroundResume() {
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                resume()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: ConcurrentAgentRuntimeResumeException) {
                return@launch
            } catch (_: Throwable) {
                // Logged and published to stopEvents inside resume().
            }
        }
    }

    private suspend fun resolvePendingAwaitEvents(
        awaitEvents: List<PendingAwaitEventToolEvent>,
    ): Boolean {
        val currentLatest = delegate.latestIndex.value
        if (currentLatest >= 0 && delegate.storage.getSteerAt(currentLatest).isNotEmpty()) {
            completeAllAwaitEventsWithTimestamp(awaitEvents)
            return true
        }

        val activeSessions = unifiedExecToolClient.activeSessions.value
        val invalidByCallId = awaitEvents.associate { pending ->
            pending.callId to pending.arguments.terminals.filter { sessionId -> sessionId !in activeSessions }
        }.filterValues { missing -> missing.isNotEmpty() }

        if (invalidByCallId.isNotEmpty()) {
            val timestamp = Clock.System.now()
            for (pending in awaitEvents) {
                val missing = invalidByCallId[pending.callId]
                val result = if (missing != null) {
                    StableAwaitEventResult.Failure(
                        "Unknown or expired terminal session ID(s): ${missing.joinToString(", ")}",
                    )
                } else {
                    StableAwaitEventResult.Output(AwaitEventOutput(timestamp = timestamp))
                }
                completeToolCall(
                    StableAwaitEventToolEvent(
                        callId = pending.callId,
                        itemId = pending.itemId,
                        arguments = pending.arguments,
                        result = result,
                    ),
                )
            }
            return true
        }

        val exitedTerminals = awaitEvents
            .flatMap { pending -> pending.arguments.terminals }
            .distinct()
            .mapNotNull { sessionId ->
                val session = activeSessions[sessionId] ?: return@mapNotNull null
                val code = session.exitCode.value ?: return@mapNotNull null
                StableTerminalExitedEvent(
                    sessionId = session.sessionId,
                    name = session.arguments.name,
                    command = session.arguments.command,
                    exitCode = code,
                )
            }

        if (exitedTerminals.isNotEmpty()) {
            delegate.steer(exitedTerminals)
            completeAllAwaitEventsWithTimestamp(awaitEvents)
            return true
        }

        return false
    }

    private suspend fun completeAllAwaitEventsWithTimestamp(
        awaitEvents: List<PendingAwaitEventToolEvent>,
    ) {
        val timestamp = Clock.System.now()
        for (pending in awaitEvents) {
            completeToolCall(
                StableAwaitEventToolEvent(
                    callId = pending.callId,
                    itemId = pending.itemId,
                    arguments = pending.arguments,
                    result = StableAwaitEventResult.Output(AwaitEventOutput(timestamp = timestamp)),
                ),
            )
        }
    }

    private suspend fun isAcceptedResumeStart(beforeIndex: Int): Boolean =
        when (delegate.state.value) {
            KodexAgentStateValue.Empty ->
                beforeIndex >= 0 && delegate.storage.getSteerAt(beforeIndex).isNotEmpty()
            KodexAgentStateValue.ExternalWrite,
            KodexAgentStateValue.Compacting,
            is KodexAgentStateValue.RequestResponse,
            -> false
            else -> true
        }

    private suspend fun hasWakeableSteer(): Boolean {
        val currentState = delegate.state.value
        val wakeable = currentState.canAppendUserMessage ||
            (currentState is KodexAgentStateValue.ToolPending &&
                currentState.events.all { it is PendingAwaitEventToolEvent })
        if (!wakeable) return false
        val index = delegate.latestIndex.value
        return index >= 0 && delegate.storage.getSteerAt(index).isNotEmpty()
    }

    private suspend fun emitStopEventsOnNormalStop(before: Int) {
        try {
            when (delegate.state.value) {
                KodexAgentStateValue.AssistantMessage -> {
                    val latest = delegate.latestIndex.value
                    if (latest > before) {
                        val message = delegate.storage.getLastCommitsIn(
                            (before + 1)..latest,
                            setOf(
                                AgentCommitType.AssistantMessage,
                                AgentCommitType.AssistantMessageCommentary,
                                AgentCommitType.AssistantMessageFinal,
                            ),
                            limit = 1,
                        ).lastOrNull()?.commit as? StableAssistantMessage
                        if (message != null) {
                            mutableStopEvents.tryEmit(AgentStopEvent.AssistantMessage(message))
                        }
                    }
                }

                else -> Unit
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            logger.warn(failure) { "Unable to project an AgentStopEvent." }
        }
    }

    private suspend fun <T> withRunningTurn(block: suspend () -> T): T {
        val turn = currentCoroutineContext().job
        if (!runningTurnSlot.compareAndSet(null, turn)) {
            logger.warn { "Rejected concurrent Agent execution." }
            throw ConcurrentAgentRuntimeResumeException()
        }
        try {
            return block()
        } finally {
            runningTurnSlot.compareAndSet(turn, null)
        }
    }

    override suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int {
        val pending = delegate.pendingToolFor(completed)
            ?: return delegate.completeToolCall(completed)
        val toolName = pending.loggingToolNameOrNull()
            ?: return delegate.completeToolCall(completed)
        val toolLogger = logger.tool(toolName, pending.callId)
        return try {
            delegate.completeToolCall(completed).also {
                toolLogger.info { "Tool call completed by host." }
            }
        } catch (cancellation: CancellationException) {
            toolLogger.info { "Host tool call completion cancelled." }
            throw cancellation
        } catch (failure: Throwable) {
            toolLogger.error(failure) { "Host tool call completion failed." }
            throw failure
        }
    }
}

private fun List<Tool>.closeAll(logger: KLogger) {
    asReversed().forEach { tool ->
        try {
            tool.close()
        } catch (failure: Throwable) {
            logger.warn(failure) { "Failed to close Agent tool." }
        }
    }
}

private inline fun <Result> List<Tool>.closeOnFailure(
    block: () -> Result,
): Result =
    try {
        block()
    } catch (failure: Throwable) {
        asReversed().forEach { tool ->
            try {
                tool.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
        }
        throw failure
    }

private fun KLogger.logClosed(
    failure: Throwable?,
    closedMessage: String,
    failedMessage: String,
) {
    if (failure == null || failure is CancellationException) {
        info { closedMessage }
    } else {
        error(failure) { failedMessage }
    }
}

private fun KLogger.logPendingHostToolCalls(state: KodexAgentStateValue) {
    val pending = state as? KodexAgentStateValue.ToolPending ?: return
    pending.events.forEach { event ->
        event.loggingToolNameOrNull()?.let { toolName ->
            tool(toolName, event.callId).info {
                "Tool call is awaiting host completion."
            }
        }
    }
}

private fun ResumableAgentLayer.pendingToolFor(
    completed: StableCleanEvent.CompletedTool,
): PendingToolEvent? {
    val callId = completed.toResponseHistoryItems()
        .filterIsInstance<ResponseItem.ToolCallOutput>()
        .singleOrNull()
        ?.callId
        ?: return null
    return (state.value as? KodexAgentStateValue.ToolPending)
        ?.events
        ?.firstOrNull { pending -> pending.callId == callId }
}

private fun PendingToolEvent.loggingToolNameOrNull(): String? {
    val name = toolName ?: return null
    return toolNamespace?.let { namespace -> "$namespace.$name" } ?: name
}

private val RuntimeLogger: KLogger by lazy {
    KotlinLogging.logger {}.global()
}
