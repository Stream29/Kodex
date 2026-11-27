package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.ShellSessionState
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecProcessSession
import io.github.stream29.kodex.utils.rpcexception.AgentNotActive
import io.github.stream29.kodex.utils.stream.StreamEntry
import io.github.stream29.kodex.utils.stream.StreamService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** The live Session is the only writer of its three projections. */
internal class RuntimeStreams(
    private val session: KodexAgentSession,
    private val agentId: Int,
    private val streams: StreamService,
    private val scope: CoroutineScope,
) {
    private val stateKey = "agent:$agentId:state"
    private val shellsKey = "agent:$agentId:shells"
    private val stateLock = Mutex()
    private val shellsLock = Mutex()
    private var state: AgentStateValue? = null
    private var shells: Map<Int, ShellSessionState>? = null
    private var activeOutput: Output? = null
    private var lastNonce: Long? = null
    private val sessionJob get() = session.coroutineContext.job

    suspend fun observeRuntime(): Unit = coroutineScope {
        launch {
            session.runtime.state.collect {
                stateLock.withLock { if (sessionJob.isActive) publishState() }
            }
        }
        launch {
            observeShells().collect {
                shellsLock.withLock { if (sessionJob.isActive) publishShells() }
            }
        }
    }

    suspend fun getState(): AgentStateValue = whileActive(stateLock) {
        publishState()
        checkNotNull(state)
    }

    fun stateFlow(): Flow<AgentStateValue> = flow {
        whileActive(stateLock) { publishState() }
        emitAll(streams.readValues<AgentStateValue>(stateKey))
    }.distinctUntilChanged()

    suspend fun getShells(): Map<Int, ShellSessionState> = whileActive(shellsLock) {
        publishShells()
        checkNotNull(shells)
    }

    fun shellsFlow(): Flow<Map<Int, ShellSessionState>> = flow {
        whileActive(shellsLock) { publishShells() }
        emitAll(streams.readValues<Map<Int, ShellSessionState>>(shellsKey))
    }.distinctUntilChanged()

    suspend fun currentOutput(): Long? = whileActive(stateLock) {
        publishState()
        activeOutput?.nonce
    }

    private fun outputKey(nonce: Long): String = "agent:$agentId:output:$nonce"

    fun outputFlow(nonce: Long): Flow<ResponsesStreamEvent> = streams.readValues(outputKey(nonce))

    private suspend inline fun <T> whileActive(lock: Mutex, block: () -> T): T =
        lock.withLock {
            currentCoroutineContext().ensureActive()
            if (!sessionJob.isActive) throw AgentNotActive()
            val value = block()
            currentCoroutineContext().ensureActive()
            if (!sessionJob.isActive) throw AgentNotActive()
            value
        }

    private suspend fun publishState() {
        val value = session.runtime.state.value
        val source = value.outputEvents()
        withContext(NonCancellable) {
            if (source == null) {
                activeOutput?.finish()
                // Keep the completed stream for readers already draining it, not the source's replay cache.
                activeOutput = null
            } else if (activeOutput?.source !== source) {
                activeOutput?.finish()
                lastNonce?.let { streams.delete(outputKey(it)) }
                val nonce = generateSequence { Random.nextLong() }.first { it != lastNonce }
                // Record ownership before create in case creation fails after taking effect.
                lastNonce = nonce
                activeOutput = null
                streams.create(outputKey(nonce))
                activeOutput = Output(nonce, source, scope)
            }
            val next = activeOutput?.let { value.toOutputRpcState(it.nonce) } ?: value.toNonOutputRpcState()
            if (state != next) {
                streams.appendValue(stateKey, next, maxLen = 1)
                state = next
            }
        }
    }

    private suspend fun publishShells() {
        val next = session.runtime.unifiedExecToolClient.activeSessions.value.mapValues { (_, process) ->
            process.toShellSessionState()
        }
        if (next != shells) {
            streams.appendValue(shellsKey, next, maxLen = 1)
            shells = next
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeShells(): Flow<Unit> =
        session.runtime.unifiedExecToolClient.activeSessions.flatMapLatest { sessions ->
            if (sessions.isEmpty()) flowOf(Unit)
            else combine(sessions.values.map { it.exitCode }) { Unit }
        }

    suspend fun close() = closeBackendResources(
        null,
        {
            stateLock.withLock {
                closeBackendResources(
                    null,
                    {
                        activeOutput?.stopWriter()
                        lastNonce?.let { streams.delete(outputKey(it)) }
                    },
                    { streams.delete(stateKey) },
                )
            }
        },
        { shellsLock.withLock { streams.delete(shellsKey) } },
    )

    private inner class Output(val nonce: Long, val source: SharedFlow<ResponsesStreamEvent>, scope: CoroutineScope) {
        val key = outputKey(nonce)
        private var written = 0
        private var index: Long? = null
        private var completed = false
        private val writer: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            source.takeWhile { event ->
                write(event)
                !completed
            }.collect()
        }

        private suspend fun write(event: ResponsesStreamEvent) = withContext(NonCancellable) {
            streams.appendValue(key, event)
            written++
            if (event is ResponsesStreamEvent.OutputItemAdded) index = event.outputIndex
            if (event is ResponsesStreamEvent.OutputItemDone && event.outputIndex == index) complete()
        }

        private suspend fun complete() {
            if (!completed) {
                streams.complete(key)
                completed = true
            }
        }

        suspend fun finish() {
            writer.cancelAndJoin()
            if (completed) return
            // State may advance before the writer sees the final frame. The source replays the
            // whole item, so drain only the suffix the writer did not persist.
            for (event in source.replayCache.drop(written)) {
                if (completed) break
                write(event)
            }
            complete()
        }

        suspend fun stopWriter() {
            writer.cancelAndJoin()
        }
    }
}

private fun UnifiedExecProcessSession.toShellSessionState(): ShellSessionState =
    ShellSessionState(arguments = arguments, registrationNonce = registrationNonce, exitCode = exitCode.value)

private fun KodexAgentStateValue.outputEvents(): SharedFlow<ResponsesStreamEvent>? = when (this) {
    is KodexAgentStateValue.RequestResponse.Message -> events
    is KodexAgentStateValue.RequestResponse.AgentMessage -> events
    is KodexAgentStateValue.RequestResponse.Reasoning -> events
    is KodexAgentStateValue.RequestResponse.ToolCall -> events
    is KodexAgentStateValue.RequestResponse.Unknown -> events
    else -> null
}

private fun KodexAgentStateValue.toOutputRpcState(nonce: Long): AgentStateValue = when (this) {
    is KodexAgentStateValue.RequestResponse.Message -> AgentStateValue.RequestResponse.Message(nonce)
    is KodexAgentStateValue.RequestResponse.AgentMessage -> AgentStateValue.RequestResponse.AgentMessage(nonce)
    is KodexAgentStateValue.RequestResponse.Reasoning -> AgentStateValue.RequestResponse.Reasoning(nonce)
    is KodexAgentStateValue.RequestResponse.ToolCall -> AgentStateValue.RequestResponse.ToolCall(nonce)
    is KodexAgentStateValue.RequestResponse.Unknown -> AgentStateValue.RequestResponse.Unknown(nonce)
    else -> error("Expected an output stream state, got $this")
}

private fun KodexAgentStateValue.toNonOutputRpcState(): AgentStateValue = when (this) {
    KodexAgentStateValue.Empty -> AgentStateValue.Empty
    KodexAgentStateValue.UserMessage -> AgentStateValue.UserMessage
    KodexAgentStateValue.AssistantMessage -> AgentStateValue.AssistantMessage
    KodexAgentStateValue.ToolCompleted -> AgentStateValue.ToolCompleted
    KodexAgentStateValue.ExternalWrite -> AgentStateValue.ExternalWrite
    KodexAgentStateValue.Compacting -> AgentStateValue.Compacting
    is KodexAgentStateValue.ToolPending -> AgentStateValue.ToolPending(events)
    KodexAgentStateValue.RequestResponse.Started -> AgentStateValue.RequestResponse.Started
    else -> error("Expected a non-output state, got $this")
}

internal val StreamJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal suspend inline fun <reified T> StreamService.appendValue(key: String, value: T, maxLen: Int? = null): String =
    append(key, StreamJson.encodeToString(value).encodeToByteArray(), maxLen)

internal inline fun <reified T> StreamService.readValues(key: String): Flow<T> = read(key).map { it.decode() }

internal inline fun <reified T> StreamEntry.decode(): T =
    StreamJson.decodeFromString(payload.decodeToString())
