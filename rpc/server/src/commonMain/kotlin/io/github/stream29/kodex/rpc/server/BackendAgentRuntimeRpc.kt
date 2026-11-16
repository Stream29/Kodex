@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agenteventbus.AgentEventBus
import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.contract.ConcurrentAgentRuntimeResumeException
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.clearPending
import io.github.stream29.kodex.agentstate.contract.forcedCompact
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
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
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.rpc.models.ShellSessionState
import io.github.stream29.kodex.tool.imagegeneration.GeneratedImageArtifact
import io.github.stream29.kodex.tool.imagegeneration.ImageGenToolArguments
import io.github.stream29.kodex.tool.imagegeneration.ImageGenerationToolClient
import io.github.stream29.kodex.tool.imagegeneration.imageArtifactDirectory
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecProcessSession
import io.github.stream29.kodex.tool.webrun.WebRunToolClient
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.rpcexception.AgentRunFailureReported
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.NoMatchException
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

public actual class BackendAgentRuntimeRpc actual constructor(
    private val repository: KodexSessionRepository,
    private val eventBus: AgentEventBus,
    private val client: OpenAiClient?,
    private val home: Path?,
    private val onAskUserCreated: (Notification.AskUserCreated) -> Unit,
    private val checkAdmission: () -> Unit,
) : AgentRuntimeRpc {
    private val viewsMutex = Mutex()
    private val views = mutableMapOf<KodexAgentSession, RuntimeView>()

    private fun openSession(index: Int): KodexAgentSession {
        checkAdmission()
        val session = repository.openSessions.value[index] ?: throw SessionNotActive()
        if (!session.coroutineContext.job.isActive) throw SessionNotActive()
        return session
    }

    private suspend fun view(index: Int): RuntimeView {
        val session = openSession(index)
        return viewsMutex.withLock {
            if (!session.coroutineContext.job.isActive) throw SessionNotActive()
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

    private suspend fun <T> command(index: Int, block: suspend RuntimeView.() -> T): T {
        currentCoroutineContext().ensureActive()
        val view = view(index)
        val currentSession = openSession(index)
        if (currentSession !== view.session) throw SessionNotActive()
        return view.operations.async(start = CoroutineStart.UNDISPATCHED) {
            checkAdmission()
            if (repository.openSessions.value[index] !== view.session || !view.session.coroutineContext.job.isActive) {
                throw SessionNotActive()
            }
            view.block()
        }.await()
    }

    actual override suspend fun getStorageUri(sessionIndex: Int): String =
        openSession(sessionIndex).storage.uri

    actual override suspend fun getLatestIndex(sessionIndex: Int): Int =
        openSession(sessionIndex).runtime.latestIndex.value

    actual override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int> =
        observe(sessionIndex) { latestIndex }

    actual override suspend fun getState(sessionIndex: Int): AgentStateValue =
        view(sessionIndex).snapshot().value

    actual override fun getStateFlow(sessionIndex: Int): Flow<AgentStateValue> = flow {
        val view = view(sessionIndex)
        emitAll(
            view.session.observeActive(
                checkAdmission = checkAdmission,
                source = view.runtime.state.map { view.snapshot().value },
            ).distinctUntilChanged(),
        )
    }

    actual override fun currentFlow(sessionIndex: Int, nonce: Long): Flow<ResponsesStreamEvent> = flow {
        val view = view(sessionIndex)
        val snapshot = view.snapshot()
        val source = snapshot.events
        if (source == null || snapshot.nonce != nonce) throw NoMatchException()
        emitAll(view.session.observeActive(checkAdmission, source))
    }

    actual override suspend fun submit(sessionIndex: Int, content: List<ContentItem>): Unit =
        command(sessionIndex) {
            if (content.isEmpty()) return@command
            eventBus.submitUserMessage(sessionIndex, content)
        }

    actual override suspend fun submitAskUserAnswer(
        sessionIndex: Int,
        askId: Int,
        response: RequestUserInputResponse,
    ): Unit =
        command(sessionIndex) {
            eventBus.submitAskUserAnswer(sessionIndex, askId, response)
        }

    actual override suspend fun createAskUser(
        sessionIndex: Int,
        arguments: RequestUserInputArgs,
    ): AskUserEntry =
        command(sessionIndex) {
            val currentSettings = runtime.storage.getSettingsAt(runtime.storage.latestIndex())
            check(currentSettings.requestUserInputMode == RequestUserInputMode.AskUser) {
                "Asking the user is disabled when RequestUserInputMode is NoQuestion."
            }
            val entry = runtime.createAskUser(arguments)
            onAskUserCreated(Notification.AskUserCreated(sessionIndex, entry))
            entry
        }

    actual override suspend fun runWebSearch(
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

    actual override suspend fun generateImage(
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
                outputDirectory = imageArtifactDirectory(kodexHome, sessionIndex.toString()),
            )
        }

    actual override suspend fun appendUserMessage(sessionIndex: Int, content: List<ContentItem>): Int =
        command(sessionIndex) {
            runtime.appendUserMessage(content)
        }

    actual override suspend fun resume(sessionIndex: Int): Unit = command(sessionIndex) {
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

    actual override suspend fun forcedCompact(sessionIndex: Int): Int = command(sessionIndex) {
        exclusive { runtime.forcedCompact() }
    }

    actual override suspend fun clearPending(sessionIndex: Int): Int =
        command(sessionIndex) { runtime.clearPending() }

    actual override suspend fun completeToolCall(
        sessionIndex: Int,
        completed: StableCleanEvent.CompletedTool,
    ): Int = command(sessionIndex) { runtime.completeToolCall(completed) }

    actual override suspend fun revertHistory(
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

    actual override suspend fun getShellSessions(sessionIndex: Int): Map<Int, ShellSessionState> =
        openSession(sessionIndex).runtime.unifiedExecToolClient.activeSessions.value.mapValues { (_, shellSession) ->
            shellSession.toShellSessionState()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    actual override fun getShellSessionsFlow(sessionIndex: Int): Flow<Map<Int, ShellSessionState>> =
        observe(sessionIndex) {
            unifiedExecToolClient.activeSessions.flatMapLatest { sessions ->
                if (sessions.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        sessions.map { (id, shellSession) ->
                            shellSession.exitCode.map { exitCode ->
                                id to ShellSessionState(
                                    cwd = shellSession.cwd.toString(),
                                    arguments = shellSession.arguments,
                                    exitCode = exitCode,
                                )
                            }
                        },
                    ) { entries -> entries.toMap() }
                }
            }
        }

    actual override suspend fun readShellSessionOutput(
        sessionIndex: Int,
        shellSessionId: Int,
        offset: ULong,
        limit: UInt,
    ): StdoutSlice =
        openSession(sessionIndex).let { session ->
            val shellSession = requireNotNull(session.runtime.unifiedExecToolClient.activeSessions.value[shellSessionId]) {
                "Shell session is no longer registered."
            }
            shellSession.readOutput(offset = offset, limit = limit)
        }

    actual override suspend fun writeShellSessionStdin(
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

    actual override suspend fun closeShellSession(sessionIndex: Int, shellSessionId: Int): Unit =
        command(sessionIndex) {
            requireNotNull(runtime.unifiedExecToolClient.activeSessions.value[shellSessionId]) {
                "Shell session is no longer registered."
            }.close()
        }

    actual override suspend fun getRunningTurn(sessionIndex: Int): Boolean =
        openSession(sessionIndex).runtime.runningTurn.value != null

    actual override fun getRunningTurnFlow(sessionIndex: Int): Flow<Boolean> =
        observe(sessionIndex) { runningTurn.map { it != null }.distinctUntilChanged() }

    actual override suspend fun cancelRunningTurn(sessionIndex: Int): Unit = command(sessionIndex) {
        runtime.runningTurn.value?.cancel()
    }

    private fun <T> observe(index: Int, source: AgentRuntime.() -> Flow<T>): Flow<T> = flow {
        val session = openSession(index)
        emitAll(session.observeActive(checkAdmission, session.runtime.source()))
    }
}

private fun UnifiedExecProcessSession.toShellSessionState(): ShellSessionState =
    ShellSessionState(
        cwd = cwd.toString(),
        arguments = arguments,
        exitCode = exitCode.value,
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
