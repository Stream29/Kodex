package io.github.stream29.kodex.rpc.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentruntime.contract.ConcurrentAgentRuntimeResumeException
import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.clearPending
import io.github.stream29.kodex.agentstate.contract.forcedCompact
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.contract.revert
import io.github.stream29.kodex.cli.sessiontitle.AgentTitleGeneration
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.ShellSessionState
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.NoMatchException
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Backend-owned operations and projections; no frontend Job owns an accepted command. */
public class BackendAgentRuntimeRpc(
    private val host: BackendSessionHost,
    private val titleSettings: () -> SessionTitleSettings,
    private val titleGenerator: SessionTitleGenerator,
    public val notifications: BackendNotifications = BackendNotifications(),
) : AgentRuntimeRpc {
    private val viewsMutex = Mutex()
    private val views = mutableMapOf<BackendSessionBinding, RuntimeView>()

    private suspend fun view(index: Int): RuntimeView {
        val binding = host.session(index)
        return viewsMutex.withLock {
            views.getOrPut(binding) {
                RuntimeView(binding).also {
                    binding.session.launch {
                        try {
                            binding.inactive.await()
                        } finally {
                            withContext(NonCancellable) { viewsMutex.withLock { views.remove(binding) } }
                        }
                    }
                }
            }
        }
    }

    private suspend fun <T> command(index: Int, block: suspend RuntimeView.() -> T): T {
        val view = view(index)
        return host.inSession(index) {
            if (this !== view.binding.session) throw SessionNotActive()
            view.block()
        }
    }

    override suspend fun getStorageUri(sessionIndex: Int): String = host.session(sessionIndex).session.storage.uri
    override suspend fun getLatestIndex(sessionIndex: Int): Int =
        host.session(sessionIndex).session.runtime.latestIndex.value
    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int> = observe(sessionIndex) { latestIndex }
    override suspend fun getState(sessionIndex: Int): AgentStateValue = view(sessionIndex).snapshot().value
    override fun getStateFlow(sessionIndex: Int): Flow<AgentStateValue> = flow {
        val view = view(sessionIndex)
        emitAll(view.binding.observe(view.runtime.state.map { view.snapshot().value }).distinctUntilChanged())
    }
    override fun currentFlow(sessionIndex: Int, nonce: Long): Flow<ResponsesStreamEvent> = flow {
        val view = view(sessionIndex)
        val snapshot = view.snapshot()
        val source = snapshot.events
        if (source == null || snapshot.nonce != nonce) throw NoMatchException()
        emitAll(view.binding.observe(source))
    }

    override suspend fun appendUserMessage(sessionIndex: Int, content: List<ContentItem>): Int =
        command(sessionIndex) {
            val index = runtime.appendUserMessage(content)
            try {
                val settings = titleSettings()
                title.start(runtime, content, settings.enabled, settings.model, settings.reasoningEffort, titleGenerator)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Message persistence has already succeeded. Optional title setup
                // is not allowed to turn it into an apparent append failure.
            }
            index
        }

    override suspend fun resume(sessionIndex: Int): Unit = command(sessionIndex) {
        exclusive {
            val before = runtime.latestIndex.value
            val acceptedState = when (runtime.state.value) {
                KodexAgentStateValue.Empty, KodexAgentStateValue.ExternalWrite,
                KodexAgentStateValue.Compacting, is KodexAgentStateValue.RequestResponse -> false
                else -> true
            }
            try {
                runtime.resume()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: ConcurrentAgentRuntimeResumeException) {
                throw rejected
            } catch (failure: Throwable) {
                if (acceptedState) notifications.publish(Notification.Stop.UnhandledError(sessionIndex, failure.message))
                throw failure
            }
            try {
                notifications.stopped(sessionIndex, runtime, before)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                RuntimeRpcLogger.warn(failure) { "Unable to project a Stop notification." }
            }
        }
    }
    override suspend fun forcedCompact(sessionIndex: Int): Int = command(sessionIndex) {
        exclusive { runtime.forcedCompact() }
    }
    override suspend fun clearPending(sessionIndex: Int): Int = command(sessionIndex) { runtime.clearPending() }
    override suspend fun completeToolCall(sessionIndex: Int, completed: StableCleanEvent.CompletedTool): Int =
        command(sessionIndex) { runtime.completeToolCall(completed) }

    override suspend fun revertHistory(sessionIndex: Int, untilExclusive: Int, expectedCacheNonce: Long): Unit =
        command(sessionIndex) {
            exclusive {
                require(runtime.runningTurn.value == null) { "Cannot replace running Agent history." }
                title.replaceHistory {
                    runtime.modify { storage ->
                        require(runtime.runningTurn.value == null) { "Cannot replace running Agent history." }
                        val index = (runtime.storage as ObservableKodexAgentStorage).index
                        if (index.cacheNonce.value != expectedCacheNonce) throw CacheNonceMismatch()
                        require(untilExclusive > 0 && untilExclusive <= storage.latestIndex() + 1) {
                            "History boundary must retain initialization and lie within storage."
                        }
                        val retainsText = storage.index.indexesIn(0 until untilExclusive).any {
                            (storage.index.getExact(it) as? StableUserMessage)?.content?.any { item ->
                                item is ContentItem.InputText && item.text.isNotBlank()
                            } == true
                        }
                        storage.revert(untilExclusive)
                        retainsText
                    }
                }
                runtime.pendingSteer.value = emptyList()
            }
        }

    override suspend fun getShellSessions(sessionIndex: Int): Map<Int, ShellSessionState> =
        host.session(sessionIndex).session.runtime.unifiedExecToolClient.activeSessions.value.mapValues {
            ShellSessionState(it.value.arguments, it.value.completed.value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getShellSessionsFlow(sessionIndex: Int): Flow<Map<Int, ShellSessionState>> =
        observe(sessionIndex) {
            unifiedExecToolClient.activeSessions.flatMapLatest { sessions ->
                if (sessions.isEmpty()) flowOf(emptyMap())
                else combine(sessions.map { (id, session) ->
                    session.completed.map { id to ShellSessionState(session.arguments, it) }
                }) { it.toMap() }
            }
        }
    override suspend fun closeShellSession(sessionIndex: Int, shellSessionId: Int): Unit =
        command(sessionIndex) {
            requireNotNull(runtime.unifiedExecToolClient.activeSessions.value[shellSessionId]) {
                "Shell session is no longer registered."
            }.close()
        }
    override suspend fun getRunningTurn(sessionIndex: Int): Boolean =
        host.session(sessionIndex).session.runtime.runningTurn.value != null
    override fun getRunningTurnFlow(sessionIndex: Int): Flow<Boolean> =
        observe(sessionIndex) { runningTurn.map { it != null }.distinctUntilChanged() }
    override suspend fun cancelRunningTurn(sessionIndex: Int): Unit = command(sessionIndex) {
        runtime.runningTurn.value?.cancel()
    }
    override suspend fun getPendingSteer(sessionIndex: Int): List<StableIndexEvent.Steerable> =
        host.session(sessionIndex).session.runtime.pendingSteer.value
    override fun getPendingSteerFlow(sessionIndex: Int): Flow<List<StableIndexEvent.Steerable>> =
        observe(sessionIndex) { pendingSteer }
    override suspend fun compareAndSetPendingSteer(
        sessionIndex: Int,
        expect: List<StableIndexEvent.Steerable>,
        update: List<StableIndexEvent.Steerable>,
    ): Boolean = command(sessionIndex) { runtime.pendingSteer.compareAndSet(expect, update) }

    private fun <T> observe(index: Int, source: AgentRuntime.() -> Flow<T>): Flow<T> = flow {
        val binding = host.session(index)
        emitAll(binding.observe(binding.session.runtime.source()))
    }
}

private class RuntimeView(val binding: BackendSessionBinding) {
    val runtime: AgentRuntime get() = binding.session.runtime
    val title = AgentTitleGeneration(runtime, useSettingsCas = true)
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
            do { nonce = Random.nextLong() } while (nonce == old)
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

private val RuntimeRpcLogger = KotlinLogging.logger {}
