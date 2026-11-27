package io.github.stream29.kodex.rpc.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agenteventbus.AgentEventBus
import io.github.stream29.kodex.agenteventbus.AgentNotificationSignal
import io.github.stream29.kodex.agenteventbus.publishNotification
import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.contract.ConcurrentAgentRuntimeResumeException
import io.github.stream29.kodex.agentruntime.contract.AgentResumeNotAdmittedException
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.contract.StoredAgent
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.clearPending
import io.github.stream29.kodex.agentstate.contract.forcedCompact
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.agentstorage.contract.getSteerAt
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.getOrThrow
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.GeneratedImageArtifact
import io.github.stream29.kodex.rpc.models.ImageGenToolArguments
import io.github.stream29.kodex.rpc.models.ShellSessionState
import io.github.stream29.kodex.rpc.models.SubagentSpawnRejection
import io.github.stream29.kodex.rpc.models.SubagentSpawnRequest
import io.github.stream29.kodex.rpc.models.SubagentSpawnResult
import io.github.stream29.kodex.tool.imagegeneration.ImageGenerationToolClient
import io.github.stream29.kodex.tool.imagegeneration.imageArtifactDirectory
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecProcessSession
import io.github.stream29.kodex.tool.webrun.WebRunToolClient
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.rpcexception.AgentRunFailureReported
import io.github.stream29.kodex.agentstorage.contract.ext.AskUserAlreadyAnswered
import io.github.stream29.kodex.agentstorage.contract.ext.AskUserEntryNotFound
import io.github.stream29.kodex.utils.rpcexception.AskAnswerRejected
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.NoMatchException
import io.github.stream29.kodex.utils.rpcexception.AgentNotActive
import io.github.stream29.kodex.utils.rpcexception.AgentNotFound
import io.github.stream29.kodex.utils.rpcexception.AgentCompleted
import io.github.stream29.kodex.utils.rpcexception.ShellSessionNotRegistered
import io.github.stream29.kodex.utils.rpcexception.SubagentCompletionNoticeFailed
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import io.github.stream29.kodex.utils.stream.StreamNotFoundException
import io.github.stream29.kodex.utils.stream.StreamService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

internal class BackendAgentRuntimeRpc(
    private val repository: KodexSessionRepository,
    private val eventBus: AgentEventBus,
    private val streamService: StreamService,
    private val client: OpenAiClient? = null,
    private val home: Path? = null,
    private val checkAdmission: () -> Unit = {},
    private val subagentSpawner: BackendSubagentSpawner? = null,
    private val archiveSession: suspend (Int) -> Unit = { repository.getEntry(it).archive() },
    private val markChildCompleted: suspend (Int) -> Int? = { error("Subagent persistence is not configured.") },
) : AgentRuntimeRpc {
    private val viewsMutex = Mutex()
    private val views = mutableMapOf<KodexAgentSession, RuntimeView>()

    init {
        repository.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.openSessions.collect { sessions ->
                for ((index, session) in sessions) getOrCreateView(index, session)
            }
        }
    }

    override suspend fun spawnSubagent(request: SubagentSpawnRequest): SubagentSpawnResult = try {
        command(request.source.agentId) {
            checkNotNull(subagentSpawner) { "Subagent persistence is not configured." }.spawn(session, request)
        }
    } catch (_: AgentNotFound) {
        SubagentSpawnResult.Rejected(SubagentSpawnRejection.InvalidSource)
    } catch (_: AgentNotActive) {
        SubagentSpawnResult.Rejected(SubagentSpawnRejection.InvalidSource)
    }

    override suspend fun completeAgent(agentId: Int, expectedInvocationId: String?): Unit = command(agentId) {
        if (expectedInvocationId != null && runtime.invocationId != expectedInvocationId) throw AgentNotActive()
        val parent = newWorkAdmission.withLock {
            val record = repository.getAgentRecord(agentId) ?: throw AgentNotFound()
            if (record.parentAgentId == null) {
                archiveSession(record.sessionId)
                null
            } else {
                markChildCompleted(agentId)
            }
        } ?: return@command
        try {
            eventBus.submitSubagentCompletion(parent, agentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            CompletionLogger.warn(failure) { "Child $agentId is complete, but notifying parent $parent failed." }
            throw SubagentCompletionNoticeFailed()
        }
    }

    private suspend fun requireIncompleteAgent(agentId: Int): StoredAgent {
        val record = repository.getAgentRecord(agentId) ?: throw AgentNotFound()
        if (record.parentAgentId != null && record.completed) throw AgentCompleted()
        return record
    }

    override suspend fun keepAgentAlive(agentId: Int) {
        checkAdmission()
        repository.openAgent(agentId) ?: throw AgentNotFound()
    }

    private suspend fun openSession(index: Int): KodexAgentSession {
        checkAdmission()
        val session = repository.openSessions.value[index]
        if (session == null) {
            if (repository.getAgentRecord(index) == null) throw AgentNotFound()
            throw AgentNotActive()
        }
        if (!session.coroutineContext.job.isActive) throw AgentNotActive()
        return session
    }

    private suspend fun view(index: Int): RuntimeView {
        val session = openSession(index)
        return getOrCreateView(index, session) ?: throw AgentNotActive()
    }

    private suspend fun getOrCreateView(index: Int, session: KodexAgentSession): RuntimeView? =
        viewsMutex.withLock {
            if (repository.openSessions.value[index] !== session || !session.coroutineContext.job.isActive) {
                return@withLock null
            }
            views.getOrPut(session) {
                RuntimeView(session, index, streamService).also { created ->
                    session.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            created.streams.observeRuntime()
                        } finally {
                            withContext(NonCancellable) {
                                try {
                                    created.streams.close()
                                } finally {
                                    viewsMutex.withLock { views.remove(session) }
                                }
                            }
                        }
                    }
                }
            }
        }

    private suspend fun <T> command(index: Int, block: suspend RuntimeView.() -> T): T =
        startCommand(index, block = block).await()

    private suspend fun <T> startCommand(
        index: Int, start: CoroutineStart = CoroutineStart.UNDISPATCHED, block: suspend RuntimeView.() -> T,
    ): Deferred<T> {
        currentCoroutineContext().ensureActive()
        val view = view(index)
        val currentSession = openSession(index)
        if (currentSession !== view.session) throw AgentNotActive()
        return view.operations.async(start = start) {
            checkAdmission()
            if (repository.openSessions.value[index] !== view.session || !view.session.coroutineContext.job.isActive) {
                throw AgentNotActive()
            }
            view.block()
        }
    }

    private suspend fun <R> checkedStorage(
        sessionIndex: Int,
        nonce: Long,
        block: suspend ObservableKodexAgentStorage.() -> R,
    ): R {
        val session = openSession(sessionIndex)
        val storage = session.storage
        if (storage.cacheNonce.value != nonce) throw CacheNonceMismatch()
        return try {
            storage.block()
        } catch (failure: IllegalStateException) {
            if (!session.coroutineContext.job.isActive) {
                throw AgentNotActive()
            }
            throw failure
        }
    }

    override suspend fun getCacheNonce(sessionIndex: Int): Long =
        openSession(sessionIndex).storage.cacheNonce.value

    override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long> = flow {
        val session = openSession(sessionIndex)
        emitAll(session.observeActive(checkAdmission, session.storage.cacheNonce))
    }

    override suspend fun getLatestIndex(sessionIndex: Int): Int =
        openSession(sessionIndex).storage.latestIndexFlow.value

    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int> = flow {
        val session = openSession(sessionIndex)
        emitAll(session.observeActive(checkAdmission, session.storage.latestIndexFlow))
    }

    override suspend fun countCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
    ): Int =
        checkedStorage(sessionIndex, cacheNonce) { countCommitsIn(fromInclusive..toInclusive, types) }

    override suspend fun getFirstCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> =
        checkedStorage(sessionIndex, cacheNonce) { getFirstCommitsIn(fromInclusive..toInclusive, types, limit) }

    override suspend fun getLastCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> =
        checkedStorage(sessionIndex, cacheNonce) { getLastCommitsIn(fromInclusive..toInclusive, types, limit) }

    override suspend fun compareAndSetSettings(
        sessionIndex: Int,
        expect: KodexAgentSettings,
        update: KodexAgentSettings,
    ): Boolean =
        command(sessionIndex) {
            runtime.compareAndSetSettings(expect, update)
        }

    override suspend fun getState(sessionIndex: Int): AgentStateValue =
        view(sessionIndex).streams.getState()

    override fun getStateFlow(sessionIndex: Int): Flow<AgentStateValue> = flow {
        val view = view(sessionIndex)
        emitAll(view.session.observeActive(checkAdmission, view.streams.stateFlow()))
    }

    override fun currentFlow(sessionIndex: Int, nonce: Long): Flow<ResponsesStreamEvent> = flow {
        val view = view(sessionIndex)
        if (view.streams.currentOutput() != nonce) throw NoMatchException()
        val source = view.streams.outputFlow(nonce).catch { failure ->
            if (failure !is StreamNotFoundException) throw failure
            if (!view.session.coroutineContext.job.isActive) throw AgentNotActive()
            throw NoMatchException()
        }
        emitAll(view.session.observeActive(checkAdmission, source))
    }

    override suspend fun submit(sessionIndex: Int, content: List<ContentItem>, expectedInvocationId: String?): Unit =
        command(sessionIndex) {
            if (content.isEmpty()) return@command
            if (expectedInvocationId != null && runtime.invocationId != expectedInvocationId) {
                throw AgentNotActive()
            }
            newWorkAdmission.withLock {
                requireIncompleteAgent(sessionIndex)
                eventBus.submitUserMessage(session, content)
            }
        }

    override suspend fun submitAskUserAnswer(
        sessionIndex: Int,
        askId: Int,
        response: RequestUserInputResponse,
    ): Unit =
        command(sessionIndex) {
            try {
                eventBus.submitAskUserAnswer(sessionIndex, askId, response)
            } catch (_: AskUserEntryNotFound) {
                throw AskAnswerRejected(alreadyAnswered = false)
            } catch (_: AskUserAlreadyAnswered) {
                throw AskAnswerRejected(alreadyAnswered = true)
            }
        }

    override suspend fun createAskUser(
        sessionIndex: Int,
        arguments: RequestUserInputArgs,
    ): AskUserEntry =
        command(sessionIndex) {
            val currentSettings = runtime.storage.getSettingsAt(runtime.storage.latestIndex())
            check(currentSettings.requestUserInputMode == RequestUserInputMode.AskUser) {
                "Asking the user is disabled when RequestUserInputMode is NoQuestion."
            }
            val entry = runtime.createAskUser(arguments)
            try {
                eventBus.broker.publishNotification(
                    AgentNotificationSignal.AskUserCreated(session.sessionId, sessionIndex, entry.askId),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                RuntimeRpcLogger.warn(error) { "Cannot publish AskUser notification for Agent $sessionIndex." }
            }
            entry
        }

    override suspend fun runWebSearch(
        sessionIndex: Int,
        commands: SearchCommands,
    ): SearchResponse =
        command(sessionIndex) {
            val openAi = checkNotNull(client) { "OpenAiClient is not configured on BackendAgentRuntimeRpc." }
            val currentSettings = runtime.storage.getSettingsAt(runtime.storage.latestIndex())
            WebRunToolClient(
                client = openAi,
                sessionId = currentSettings.threadId.ifEmpty { currentSettings.firstWindowId.ifEmpty { "agent:${session.sessionId}" } },
                modelProvider = { currentSettings.model },
            ).run(commands).getOrThrow().copy(encryptedOutput = null)
        }

    override suspend fun generateImage(
        sessionIndex: Int,
        arguments: ImageGenToolArguments,
    ): GeneratedImageArtifact =
        command(sessionIndex) {
            val openAi = checkNotNull(client) { "OpenAiClient is not configured on BackendAgentRuntimeRpc." }
            val kodexHome = checkNotNull(home) { "Kodex Home is not configured on BackendAgentRuntimeRpc." }
            ImageGenerationToolClient(
                client = openAi,
                workingDirectoryProvider = { runtime.storage.getSettingsAt(runtime.storage.latestIndex()).cwd },
            ).generateToFile(
                arguments = arguments,
                outputDirectory = imageArtifactDirectory(kodexHome, session.sessionId.toString()),
            )
        }

    override suspend fun resume(sessionIndex: Int): Unit = command(sessionIndex) {
        requireIncompleteAgent(sessionIndex)
        exclusive {
            val before = runtime.latestIndex.value
            val acceptedState = runtime.isAcceptedResumeStart(before)
            try {
                resumeBeforeCompletion(sessionIndex)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: ConcurrentAgentRuntimeResumeException) {
                throw rejected
            } catch (completed: AgentCompleted) {
                throw completed
            } catch (missing: AgentNotFound) {
                throw missing
            } catch (failure: Throwable) {
                if (acceptedState) {
                    throw AgentRunFailureReported(failure.message).also { it.addSuppressed(failure) }
                }
                throw failure
            }
        }
    }

    override suspend fun forcedCompact(sessionIndex: Int): Int = command(sessionIndex) {
        exclusive { runtime.forcedCompact() }
    }

    override suspend fun requestResume(sessionIndex: Int): Boolean {
        val accepted = CompletableDeferred<Boolean>()
        val run = startCommand(sessionIndex, CoroutineStart.DEFAULT) {
            val completed = tryExclusive {
                resumeBeforeCompletion(sessionIndex) { accepted.complete(true) }
                true
            }
            if (completed == null) {
                requireIncompleteAgent(sessionIndex)
                accepted.complete(false)
            }
        }
        run.invokeOnCompletion { failure ->
            when (failure) {
                is ConcurrentAgentRuntimeResumeException, is AgentResumeNotAdmittedException -> accepted.complete(false)
                null -> if (!accepted.isCompleted) accepted.complete(false) else Unit
                else -> accepted.completeExceptionally(failure)
            }
        }
        return accepted.await()
    }

    private suspend fun RuntimeView.resumeBeforeCompletion(agentId: Int, onAccepted: (() -> Unit)? = null) {
        newWorkAdmission.lock()
        var locked = true
        fun release() {
            locked = false
            newWorkAdmission.unlock()
        }
        try {
            val record = requireIncompleteAgent(agentId)
            if (record.parentAgentId == null && onAccepted == null) {
                // The synchronous root operation retains its existing no-op behavior on an empty history.
                release()
                runtime.resume()
            } else {
                runtime.resume {
                    // Completion need not wait for the accepted model turn or stop it.
                    release()
                    onAccepted?.invoke()
                }
            }
        } finally {
            if (locked) release()
        }
    }

    override suspend fun clearPending(sessionIndex: Int): Int =
        command(sessionIndex) { runtime.clearPending() }

    override suspend fun revertHistory(
        sessionIndex: Int,
        untilExclusive: Int,
        expectedCacheNonce: Long,
    ): Unit = command(sessionIndex) {
        exclusive {
            require(runtime.runningTurn.value == null) { "Cannot replace running Agent history." }
            runtime.modify { storage ->
                require(runtime.runningTurn.value == null) { "Cannot replace running Agent history." }
                val observable = runtime.storage as ObservableKodexAgentStorage
                if (observable.cacheNonce.value != expectedCacheNonce) throw CacheNonceMismatch()
                require(untilExclusive > 0 && untilExclusive <= storage.latestIndex() + 1) {
                    "History boundary must retain initialization and lie within storage."
                }
                storage.revert(untilExclusive)
            }
        }
    }

    override suspend fun getShellSessions(sessionIndex: Int): Map<Int, ShellSessionState> =
        view(sessionIndex).streams.getShells()

    override fun getShellSessionsFlow(sessionIndex: Int): Flow<Map<Int, ShellSessionState>> = flow {
        val view = view(sessionIndex)
        emitAll(view.session.observeActive(checkAdmission, view.streams.shellsFlow()))
    }

    override suspend fun readShellSessionOutput(
        sessionIndex: Int,
        shellSessionId: Int,
        offset: ULong,
        limit: UInt,
        expectedRegistrationNonce: String?,
    ): StdoutSlice {
        currentCoroutineContext().ensureActive()
        val session = openSession(sessionIndex)
        val shell = session.runtime.unifiedExecToolClient.activeSessions.value
            .registeredShell(shellSessionId, expectedRegistrationNonce)
        val output = shell.readOutput(offset = offset, limit = limit)
        currentCoroutineContext().ensureActive()
        if (repository.openSessions.value[sessionIndex] !== session || !session.coroutineContext.job.isActive) {
            throw AgentNotActive()
        }
        return output
    }

    override suspend fun writeShellSessionStdin(
        sessionIndex: Int,
        shellSessionId: Int,
        chars: String,
        closeInput: Boolean,
    ): Unit =
        command(sessionIndex) {
            val shellSession = requireNotNull(runtime.unifiedExecToolClient.activeSessions.value[shellSessionId]) {
                "Shell session is no longer registered."
            }
            shellSession.writeStdin(chars = chars, closeInput = closeInput)
        }

    override suspend fun closeShellSession(
        sessionIndex: Int,
        shellSessionId: Int,
        expectedRegistrationNonce: String?,
    ): Unit =
        command(sessionIndex) {
            runtime.unifiedExecToolClient.activeSessions.value
                .registeredShell(shellSessionId, expectedRegistrationNonce).close()
        }

    override suspend fun getRunningTurn(sessionIndex: Int): Boolean =
        openSession(sessionIndex).runtime.runningTurn.value != null

    override fun getRunningTurnFlow(sessionIndex: Int): Flow<Boolean> =
        observe(sessionIndex) { runningTurn.map { it != null }.distinctUntilChanged() }

    override suspend fun cancelRunningTurn(sessionIndex: Int): Unit = command(sessionIndex) {
        runtime.runningTurn.value?.cancel()
    }

    private fun <T> observe(index: Int, source: AgentRuntime.() -> Flow<T>): Flow<T> = flow {
        val session = openSession(index)
        emitAll(session.observeActive(checkAdmission, session.runtime.source()))
    }
}

internal fun <T> KodexAgentSession.observeActive(
    checkAdmission: () -> Unit,
    source: Flow<T>,
): Flow<T> = channelFlow {
    checkAdmission()
    val sessionJob = this@observeActive.coroutineContext.job
    if (!sessionJob.isActive) throw AgentNotActive()
    val deactivated = CompletableDeferred<Unit>()
    val completionHandle = sessionJob.invokeOnCompletion {
        deactivated.complete(Unit)
    }
    val watcher = launch {
        deactivated.await()
        throw AgentNotActive()
    }
    try {
        if (!sessionJob.isActive) throw AgentNotActive()
        source.collect { send(it) }
        if (!sessionJob.isActive) throw AgentNotActive()
    } catch (error: StreamNotFoundException) {
        if (!sessionJob.isActive) throw AgentNotActive()
        throw error
    } finally {
        completionHandle.dispose()
        watcher.cancel()
    }
}

internal fun Map<Int, UnifiedExecProcessSession>.registeredShell(
    id: Int,
    expectedRegistrationNonce: String?,
): UnifiedExecProcessSession {
    val shell = this[id] ?: throw ShellSessionNotRegistered()
    if (expectedRegistrationNonce != null && expectedRegistrationNonce != shell.registrationNonce) {
        throw ShellSessionNotRegistered()
    }
    return shell
}

private val CompletionLogger = KotlinLogging.logger("SubagentCompletion")
private val RuntimeRpcLogger = KotlinLogging.logger {}

private class RuntimeView(val session: KodexAgentSession, agentId: Int, streamService: StreamService) {
    val operations: CoroutineScope = session.supervisorChildScope()
    val runtime: AgentRuntime get() = session.runtime
    val newWorkAdmission = Mutex()
    private val admission = Mutex()
    val streams = RuntimeStreams(session, agentId, streamService, operations)

    suspend fun <T> exclusive(block: suspend () -> T): T {
        check(admission.tryLock()) { "This Agent already has a running or history operation." }
        try {
            return block()
        } finally {
            admission.unlock()
        }
    }

    suspend fun <T : Any> tryExclusive(block: suspend () -> T): T? {
        if (!admission.tryLock()) return null
        try {
            return block()
        } finally {
            admission.unlock()
        }
    }
}

private suspend fun AgentRuntime.isAcceptedResumeStart(beforeIndex: Int): Boolean =
    when (state.value) {
        KodexAgentStateValue.Empty -> beforeIndex >= 0 && storage.getSteerAt(beforeIndex).isNotEmpty()
        KodexAgentStateValue.ExternalWrite,
        KodexAgentStateValue.Compacting,
        is KodexAgentStateValue.RequestResponse,
        -> false

        else -> true
    }
