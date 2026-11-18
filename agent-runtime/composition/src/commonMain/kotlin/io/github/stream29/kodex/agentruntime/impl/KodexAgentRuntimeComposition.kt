package io.github.stream29.kodex.agentruntime.impl

import kotlin.uuid.Uuid
import io.github.stream29.kodex.agentruntime.contract.AgentResumeNotAdmittedException
import kotlinx.coroutines.ensureActive
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.contract.AgentStopEvent
import io.github.stream29.kodex.agentruntime.contract.ConcurrentAgentRuntimeResumeException
import io.github.stream29.kodex.agentruntime.contract.PendingAwaitDeadline
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.canAppendUserMessage
import io.github.stream29.kodex.agentstate.contract.clearPending
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTerminalExitedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.callId
import io.github.stream29.kodex.agentstorage.cleanmodels.toFailedToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolCall
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrBefore
import io.github.stream29.kodex.agentstorage.contract.getSteerAt
import io.github.stream29.kodex.tool.awaitevent.AwaitEventInput
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock

internal interface ResumableAgentLayer : KodexAgentState {
    suspend fun resume()
}

/** Builds the root Agent runtime. */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
public actual fun KodexAgentState.buildMasterAgentRuntime(
    dependencies: KodexAgentDependencies,
    admitTurn: suspend (() -> Unit) -> Unit,
): AgentRuntime {
    val sessionLogger = RuntimeLogger.session(storage.uri)
    return try {
        val logger = sessionLogger.agent()
        val runtime = try {
            val invocationId = Uuid.random().toString()
            val fixedTools = fixedTools(dependencies, invocationId)
            val delegate = fixedTools.tools.closeOnFailure {
                compactionRuntime(
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
                    }
            }
            logger.info { "Agent runtime opened." }
            coroutineContext.job.invokeOnCompletion { failure ->
                logger.logClosed(
                    failure = failure,
                    closedMessage = "Agent runtime closed.",
                    failedMessage = "Agent runtime closed after a failure.",
                )
            }
            AgentRuntimeImpl(
                delegate = delegate,
                unifiedExecToolClient = fixedTools.unifiedExecToolClient,
                logger = logger,
                clock = dependencies.clock,
                invocationId = invocationId,
                admitTurn = admitTurn,
            )
        } catch (failure: Throwable) {
            logger.error(failure) { "Agent runtime failed to open." }
            throw failure
        }
        sessionLogger.info { "Session opened." }
        coroutineContext.job.invokeOnCompletion { failure ->
            sessionLogger.logClosed(
                failure = failure,
                closedMessage = "Session closed.",
                failedMessage = "Session closed after a failure.",
            )
        }
        runtime
    } catch (failure: Throwable) {
        sessionLogger.error(failure) { "Session failed to open." }
        throw failure
    }
}

@OptIn(ExperimentalAtomicApi::class)
private class AgentRuntimeImpl(
    private val delegate: ResumableAgentLayer,
    override val unifiedExecToolClient: UnifiedExecToolClient,
    private val logger: KLogger,
    private val clock: Clock,
    override val invocationId: String,
    private val admitTurn: suspend (() -> Unit) -> Unit,
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

    override suspend fun resume(): Unit = runAdmittedTurn()

    override suspend fun resume(onAccepted: () -> Unit): Unit = runAdmittedTurn(onAccepted = onAccepted)

    override fun requestAwaitDeadlineCheck(expected: PendingAwaitDeadline): Job =
        launchAwaitCheck(AwaitWake.Deadline(expected))

    override fun requestAwaitTerminalCheck(expected: PendingAwait, terminalId: Int): Job =
        launchAwaitCheck(AwaitWake.Terminal(expected, terminalId))

    private fun launchAwaitCheck(wake: AwaitWake): Job = launch(start = CoroutineStart.UNDISPATCHED) {
        while (isActive) {
            runningTurnSlot.first { it == null }
            try {
                runAdmittedTurn(wake)
                break
            } catch (_: ConcurrentAgentRuntimeResumeException) {
                // A caller took the slot after the observer saw it idle. Wait for the next release.
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // The admitted turn logs and publishes failures; the host retains any still-persisted deadline.
                break
            }
        }
    }

    private suspend fun runAdmittedTurn(wake: AwaitWake? = null, onAccepted: (() -> Unit)? = null) {
        var completedNormally = false
        withRunningTurn {
            val before = delegate.latestIndex.value
            val acceptedStart = isAcceptedResumeStart(before)
            if (onAccepted != null) {
                if (!acceptedStart) throw AgentResumeNotAdmittedException()
                currentCoroutineContext().ensureActive()
                onAccepted()
            }
            var started = wake == null
            try {
                if (wake != null) {
                    if (resolvePendingAwaitEvents(wake) != AwaitResolution.Completed) {
                        completedNormally = true
                        return@withRunningTurn
                    }
                    started = true
                }
                logger.info { "Agent turn started." }
                var parkedOnAwaitEvent = false
                while (true) {
                    needsSubmittedSteerDrain.store(false)
                    delegate.resume()
                    val pending = delegate.state.value as? KodexAgentStateValue.ToolPending
                        ?: break
                    val unsupported = pending.events.filterNot { it is PendingAwait }
                    if (unsupported.isNotEmpty()) {
                        for (unmatched in unsupported) {
                            val label = unmatched.loggingToolName() ?: unmatched.callId
                            completeToolCall(
                                unmatched.toFailedToolEvent(
                                    "unsupported tool call: $label",
                                ),
                            )
                        }
                        continue
                    }
                    val awaitEvents = pending.events.filterIsInstance<PendingAwait>()
                    if (awaitEvents.isEmpty()) {
                        break
                    }
                    if (resolvePendingAwaitEvents() == AwaitResolution.Parked) {
                        parkedOnAwaitEvent = true
                        break
                    }
                }
                if (!parkedOnAwaitEvent) {
                    emitStopEventsOnNormalStop(before)
                }
                logger.info { "Agent turn completed." }
                completedNormally = true
            } catch (cancellation: CancellationException) {
                val cleanupFailure = if (started) withContext(NonCancellable) {
                    runCatching { delegate.clearPending() }.exceptionOrNull()
                } else null
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

    override suspend fun submit(events: List<AgentCommit.Steerable>) {
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

    // Resolve and write inside the AgentState lock: a late observer must not win against clear/revert.
    private suspend fun resolvePendingAwaitEvents(wake: AwaitWake? = null): AwaitResolution = delegate.modify { storage ->
        val latest = storage.latestIndex()
        val indexed = storage.getCommitAtOrBefore(latest, AgentCommitType.UnstableTypes)
            ?: return@modify AwaitResolution.Inactive
        var records = (indexed.commit as UnstableSnapshot).events
        val tools = records.filterIsInstance<PendingToolCall>()
        val awaits = tools.filterIsInstance<PendingAwait>()
        if (awaits.isEmpty() || awaits.size != tools.size) return@modify AwaitResolution.Inactive
        val now = clock.now()
        val activeSessions = unifiedExecToolClient.activeSessions.value
        when (wake) {
            is AwaitWake.Deadline -> {
                val expected = wake.expected
                if (indexed.index != expected.snapshotIndex || expected.timeoutAt > now || agentId == null ||
                    awaits.none { it.callId == expected.callId && it.timeoutAt == expected.timeoutAt && it.armedForAgentId == agentId }
                ) {
                    return@modify AwaitResolution.Inactive
                }
            }
            is AwaitWake.Terminal -> {
                val terminal = activeSessions[wake.terminalId]
                if (wake.expected !in awaits || wake.terminalId !in wake.expected.arguments.terminalIds ||
                    (terminal != null && terminal.exitCode.value == null)
                ) {
                    return@modify AwaitResolution.Inactive
                }
            }
            null -> Unit
        }

        val hasSteer = storage.getSteerAt(latest).isNotEmpty()
        val missingByCall = if (hasSteer) emptyMap() else awaits.associate { pending ->
            pending.callId to pending.arguments.terminalIds.filter { it !in activeSessions }
        }.filterValues { it.isNotEmpty() }
        val exited = if (hasSteer || missingByCall.isNotEmpty()) emptyList() else {
            awaits.flatMap { it.arguments.terminalIds }.distinct().mapNotNull { id ->
                val session = activeSessions[id] ?: return@mapNotNull null
                val code = session.exitCode.value ?: return@mapNotNull null
                StableTerminalExitedEvent(id, session.arguments.name, session.arguments.command, code)
            }
        }
        val exitedIds = exited.mapTo(mutableSetOf()) { it.sessionId }
        val completeBatch = hasSteer || missingByCall.isNotEmpty() || exited.isNotEmpty() ||
            awaits.any { it.timeoutAt?.let { deadline -> deadline <= now } == true }

        if (exited.isNotEmpty()) storage.steer(exited)
        for (pending in awaits) {
            val missing = missingByCall[pending.callId]
            val terminalIds = pending.arguments.terminalIds
            val singleTerminalOnly = terminalIds.size == 1 && pending.arguments.events.all { it is AwaitEventInput.TerminalExited }
            val result = when {
                hasSteer -> StableAwaitEventResult.Output(AwaitEventOutput(now))
                missing != null -> StableAwaitEventResult.Failure(
                    "Unknown or expired terminal session ID(s): ${missing.joinToString(", ")}",
                )
                singleTerminalOnly && terminalIds.none { it in exitedIds } &&
                    terminalIds.none { activeSessions[it]?.exitCode?.value != null } ->
                    StableAwaitEventResult.Failure(
                        "Awaiting only terminal ${terminalIds.single()} can wait indefinitely. Add a timeout event.",
                    )
                completeBatch -> StableAwaitEventResult.Output(AwaitEventOutput(now))
                else -> null
            }
            if (result != null) {
                records = records.filterNot { it is PendingToolCall && it.callId == pending.callId }
                storage.commit(
                    listOf(StableAwait(pending.callId, pending.itemId, pending.arguments, result), UnstableSnapshot(records)),
                    now,
                )
                logger.tool("await_event", pending.callId).info { "Tool call completed by host." }
            }
        }

        if (records.none { it is PendingAwait }) return@modify AwaitResolution.Completed
        val owner = agentId
        if (owner != null) {
            val armed = records.map { record ->
                if (record is PendingAwait && record.timeoutAt != null && record.armedForAgentId != owner) {
                    record.copy(armedForAgentId = owner)
                } else record
            }
            if (armed != records) storage.commit(listOf(UnstableSnapshot(armed)), now)
        }
        AwaitResolution.Parked
    }

    private enum class AwaitResolution { Inactive, Completed, Parked }

    private sealed interface AwaitWake {
        data class Deadline(val expected: PendingAwaitDeadline) : AwaitWake
        data class Terminal(val expected: PendingAwait, val terminalId: Int) : AwaitWake
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
                currentState.events.all { it is PendingAwait })
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
        try {
            admitTurn {
                if (!runningTurnSlot.compareAndSet(null, turn)) {
                    logger.warn { "Rejected concurrent Agent execution." }
                    throw ConcurrentAgentRuntimeResumeException()
                }
            }
            return block()
        } finally {
            runningTurnSlot.compareAndSet(turn, null)
        }
    }

    override suspend fun completeToolCall(completed: AgentCommit.CompletedTool): Int {
        val pending = delegate.pendingToolFor(completed)
            ?: return delegate.completeToolCall(completed)
        val toolName = pending.loggingToolName()
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

private fun ResumableAgentLayer.pendingToolFor(
    completed: AgentCommit.CompletedTool,
): PendingToolCall? {
    val callId = completed.callId ?: return null
    return (state.value as? KodexAgentStateValue.ToolPending)
        ?.events
        ?.firstOrNull { pending -> pending.callId == callId }
}

internal fun PendingToolCall.loggingToolName(): String? {
    val name = toolName ?: return null
    return toolNamespace?.let { namespace -> "$namespace.$name" } ?: name
}

private val RuntimeLogger: KLogger by lazy {
    KotlinLogging.logger {}.global()
}
