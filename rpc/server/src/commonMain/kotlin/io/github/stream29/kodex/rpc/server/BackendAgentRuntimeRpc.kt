package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agenteventbus.AgentEventBus
import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.contract.ConcurrentAgentRuntimeResumeException
import io.github.stream29.kodex.agentruntime.contract.AgentResumeNotAdmittedException
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
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
import io.github.stream29.kodex.rpc.models.Notification
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
import io.github.stream29.kodex.utils.rpcexception.ShellSessionNotRegistered
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.random.Random

internal class BackendAgentRuntimeRpc(
    private val repository: KodexSessionRepository,
    private val eventBus: AgentEventBus = AgentEventBus(repository),
    private val client: OpenAiClient? = null,
    private val home: Path? = null,
    private val onAskUserCreated: (Notification.AskUserCreated) -> Unit = {},
    private val checkAdmission: () -> Unit = {},
    private val subagentSpawner: BackendSubagentSpawner? = null,
) : AgentRuntimeRpc {
    private val viewsMutex = Mutex()
    private val views = mutableMapOf<KodexAgentSession, RuntimeView>()

    override suspend fun spawnSubagent(request: SubagentSpawnRequest): SubagentSpawnResult = try {
        command(request.source.agentId) {
            checkNotNull(subagentSpawner) { "Subagent persistence is not configured." }.spawn(session, request)
        }
    } catch (_: AgentNotFound) {
        SubagentSpawnResult.Rejected(SubagentSpawnRejection.InvalidSource)
    } catch (_: AgentNotActive) {
        SubagentSpawnResult.Rejected(SubagentSpawnRejection.InvalidSource)
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
        return viewsMutex.withLock {
            if (!session.coroutineContext.job.isActive) throw AgentNotActive()
            views.getOrPut(session) {
                RuntimeView(session).also { created ->
                    session.launch {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                viewsMutex.withLock {
                                    if (views[session] === created) {
                                        views.remove(session)
                                    }
                                }
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
        view(sessionIndex).snapshot().value

    override fun getStateFlow(sessionIndex: Int): Flow<AgentStateValue> = flow {
        val view = view(sessionIndex)
        emitAll(
            view.session.observeActive(
                checkAdmission = checkAdmission,
                source = view.runtime.state.map { view.snapshot().value },
            ).distinctUntilChanged(),
        )
    }

    override fun currentFlow(sessionIndex: Int, nonce: Long): Flow<ResponsesStreamEvent> = flow {
        val view = view(sessionIndex)
        val snapshot = view.snapshot()
        val source = snapshot.events
        if (source == null || snapshot.nonce != nonce) throw NoMatchException()
        emitAll(view.session.observeActive(checkAdmission, source))
    }

    override suspend fun submit(sessionIndex: Int, content: List<ContentItem>, expectedInvocationId: String?): Unit =
        command(sessionIndex) {
            if (content.isEmpty()) return@command
            if (expectedInvocationId != null && runtime.invocationId != expectedInvocationId) {
                throw AgentNotActive()
            }
            eventBus.submitUserMessage(session, content)
        }

    override suspend fun submitAskUserAnswer(
        sessionIndex: Int,
        askId: Int,
        response: RequestUserInputResponse,
    ): Unit =
        command(sessionIndex) {
            try {
                runtime.submitAskUserAnswer(askId, response)
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
            onAskUserCreated(Notification.AskUserCreated(session.sessionId, entry, sessionIndex))
            entry
        }

    override suspend fun runWebSearch(
        sessionIndex: Int,
        commands: SearchCommands,
    ): SearchResponse =
        command(sessionIndex) {
            val openAi = checkNotNull(client) { "OpenAiClient is not configured on BackendAgentRuntimeRpc." }
            WebRunToolClient(
                client = openAi,
                sessionId = runtime.storage.uri,
                modelProvider = { runtime.storage.getSettingsAt(runtime.storage.latestIndex()).model },
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
        exclusive {
            val before = runtime.latestIndex.value
            val acceptedState = runtime.isAcceptedResumeStart(before)
            try {
                runtime.resume()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: ConcurrentAgentRuntimeResumeException) {
                throw rejected
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
                runtime.resume { accepted.complete(true) }
                true
            }
            if (completed == null) accepted.complete(false)
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
        openSession(sessionIndex).runtime.unifiedExecToolClient.activeSessions.value.mapValues { (_, shellSession) ->
            shellSession.toShellSessionState()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getShellSessionsFlow(sessionIndex: Int): Flow<Map<Int, ShellSessionState>> =
        observe(sessionIndex) {
            unifiedExecToolClient.activeSessions.flatMapLatest { sessions ->
                if (sessions.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        sessions.map { (id, shellSession) ->
                            shellSession.exitCode.map { exitCode ->
                                id to shellSession.toShellSessionState(exitCode)
                            }
                        },
                    ) { entries -> entries.toMap() }
                }
            }
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

private fun UnifiedExecProcessSession.toShellSessionState(code: Int? = exitCode.value): ShellSessionState =
    ShellSessionState(
        arguments = arguments,
        registrationNonce = registrationNonce,
        exitCode = code,
    )

private class RuntimeView(val session: KodexAgentSession) {
    val operations: CoroutineScope = session.supervisorChildScope()
    val runtime: AgentRuntime get() = session.runtime
    private val admission = Mutex()
    private val output = RuntimeOutputProjection()

    suspend fun snapshot(): OutputSnapshot = output.snapshot { runtime.state.value }

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

/** Holds only the current stream identity; snapshots carry the captured source. */
internal class RuntimeOutputProjection {
    private val mutex = Mutex()
    private var source: SharedFlow<ResponsesStreamEvent>? = null
    private var nonce = Random.nextLong()

    suspend fun snapshot(read: () -> KodexAgentStateValue): OutputSnapshot = mutex.withLock {
        val value = read()
        val next = when (value) {
            is KodexAgentStateValue.RequestResponse.Message -> value.events
            is KodexAgentStateValue.RequestResponse.AgentMessage -> value.events
            is KodexAgentStateValue.RequestResponse.Reasoning -> value.events
            is KodexAgentStateValue.RequestResponse.ToolCall -> value.events
            is KodexAgentStateValue.RequestResponse.Unknown -> value.events
            else -> null
        }
        if (source !== next) {
            source = next
            val old = nonce
            do {
                nonce = Random.nextLong()
            } while (nonce == old)
        }
        val projected = when (value) {
            KodexAgentStateValue.Empty -> AgentStateValue.Empty
            KodexAgentStateValue.UserMessage -> AgentStateValue.UserMessage
            KodexAgentStateValue.AssistantMessage -> AgentStateValue.AssistantMessage
            KodexAgentStateValue.ToolCompleted -> AgentStateValue.ToolCompleted
            KodexAgentStateValue.ExternalWrite -> AgentStateValue.ExternalWrite
            KodexAgentStateValue.Compacting -> AgentStateValue.Compacting
            is KodexAgentStateValue.ToolPending -> AgentStateValue.ToolPending(value.events)
            KodexAgentStateValue.RequestResponse.Started -> AgentStateValue.RequestResponse.Started
            is KodexAgentStateValue.RequestResponse.Message -> AgentStateValue.RequestResponse.Message(nonce)
            is KodexAgentStateValue.RequestResponse.AgentMessage -> AgentStateValue.RequestResponse.AgentMessage(nonce)
            is KodexAgentStateValue.RequestResponse.Reasoning -> AgentStateValue.RequestResponse.Reasoning(nonce)
            is KodexAgentStateValue.RequestResponse.ToolCall -> AgentStateValue.RequestResponse.ToolCall(nonce)
            is KodexAgentStateValue.RequestResponse.Unknown -> AgentStateValue.RequestResponse.Unknown(nonce)
        }
        OutputSnapshot(projected, nonce, next)
    }
}

internal data class OutputSnapshot(
    val value: AgentStateValue,
    val nonce: Long,
    val events: SharedFlow<ResponsesStreamEvent>?,
)
