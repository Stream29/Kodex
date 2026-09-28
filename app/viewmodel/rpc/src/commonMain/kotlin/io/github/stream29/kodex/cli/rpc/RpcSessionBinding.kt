package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.CachedIndexVersioned
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.TokenCountSnapshot
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.rpc.client.asSuspendMutableStateFlow
import io.github.stream29.kodex.rpc.client.rpcCachedIndexVersioned
import io.github.stream29.kodex.rpc.client.rpcStateIn
import io.github.stream29.kodex.rpc.contract.SuspendMutableStateFlow
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.ShellSessionState
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import io.github.stream29.kodex.utils.rpcexception.SessionNotFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlin.time.Instant

/** A disposable frontend read/command binding. It is neither AgentRuntime nor AgentState. */
public class RpcSessionBinding private constructor(
    public val sessionIndex: Int,
    public val storage: ObservableKodexAgentStorage,
    public val latestIndex: StateFlow<Int>,
    public val state: StateFlow<AgentStateValue>,
    public val displayState: StateFlow<KodexAgentStateValue>,
    public val running: StateFlow<Boolean>,
    public val shellSessions: StateFlow<Map<Int, ShellSessionState>>,
    public val settings: SuspendMutableStateFlow<KodexAgentSettings>,
    public val pendingSteer: SuspendMutableStateFlow<List<StableIndexEvent.Steerable>>,
    public val tokenCount: StateFlow<TokenCountSnapshot?>,
    private val owner: Job,
    private val services: RpcServices,
    private val invalidate: (Throwable) -> Unit,
) {
    public fun ensureActive(): Unit = owner.ensureActive()

    private suspend fun <R> command(action: suspend () -> R): R {
        ensureActive()
        return try {
            action()
        } catch (error: SessionNotActive) {
            invalidate(error)
            throw error
        } catch (error: SessionNotFound) {
            invalidate(error)
            throw error
        }
    }

    public suspend fun appendUserMessage(content: List<ContentItem>): Int =
        command { services.runtime.appendUserMessage(sessionIndex, content) }
    public suspend fun resume(): Unit = command { services.runtime.resume(sessionIndex) }
    public suspend fun stop(): Unit = command { services.runtime.cancelRunningTurn(sessionIndex) }
    public suspend fun forcedCompact(): Int = command { services.runtime.forcedCompact(sessionIndex) }
    public suspend fun clearPending(): Int = command { services.runtime.clearPending(sessionIndex) }
    public suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int =
        command { services.runtime.completeToolCall(sessionIndex, completed) }
    public suspend fun closeShellSession(id: Int): Unit =
        command { services.runtime.closeShellSession(sessionIndex, id) }
    public suspend fun revertHistory(untilExclusive: Int, selectedCacheNonce: Long): Unit =
        command { services.runtime.revertHistory(sessionIndex, untilExclusive, selectedCacheNonce) }
    public suspend fun forkHistory(untilExclusive: Int, selectedCacheNonce: Long): Int =
        command { services.global.forkSessionHistory(sessionIndex, untilExclusive, selectedCacheNonce) }
    public suspend fun readCreatedAt(): Instant? =
        storage.timestamp.ceilToIndex(0)?.let { storage.timestamp.getExact(it) }
    public suspend fun readUpdatedAt(): Instant? =
        storage.timestamp.latestIndex.value.takeIf { it >= 0 }?.let { storage.timestamp.getExact(it) }

    internal companion object {
        suspend fun create(
            index: Int,
            services: RpcServices,
            scope: CoroutineScope,
            invalidate: (Throwable) -> Unit,
        ): RpcSessionBinding {
            val owner = requireNotNull(scope.coroutineContext[Job])
            val runtime = services.runtime
            val storage = RpcStorage(
                uri = runtime.getStorageUri(index),
                index = scope.rpcCachedIndexVersioned(index, services.index),
                work = scope.rpcCachedIndexVersioned(index, services.work),
                settings = scope.rpcCachedIndexVersioned(index, services.settings),
                timestamp = scope.rpcCachedIndexVersioned(index, services.timestamp),
                tokenCount = scope.rpcCachedIndexVersioned(index, services.tokenCount),
                unstable = scope.rpcCachedIndexVersioned(index, services.unstable),
            )
            val latest = scope.rpcStateIn(
                { runtime.getLatestIndex(index) }, { runtime.getLatestIndexFlow(index).requiredState() },
            )
            val state = scope.rpcStateIn(
                { runtime.getState(index) }, { runtime.getStateFlow(index).requiredState() },
            )
            val running = scope.rpcStateIn(
                { runtime.getRunningTurn(index) }, { runtime.getRunningTurnFlow(index).requiredState() },
            )
            val shell = scope.rpcStateIn(
                { runtime.getShellSessions(index) }, { runtime.getShellSessionsFlow(index).requiredState() },
            )
            val pending = scope.rpcStateIn(
                { runtime.getPendingSteer(index) }, { runtime.getPendingSteerFlow(index).requiredState() },
            )
            val settings = scope.visibleValue(storage.settings, latest) { position -> storage.settings.get(position) }
            val tokenCount = scope.visibleValue(storage.tokenCount, latest) { position ->
                storage.tokenCount.floorToIndex(position)?.let { storage.tokenCount.getExact(it) }
            }
            suspend fun <R> compare(action: suspend () -> R): R {
                owner.ensureActive()
                return try {
                    action()
                } catch (error: SessionNotActive) {
                    invalidate(error)
                    throw error
                } catch (error: SessionNotFound) {
                    invalidate(error)
                    throw error
                }
            }
            return RpcSessionBinding(
                index, storage, latest, state, scope.projectOutput(index, runtime, state), running, shell,
                settings.asSuspendMutableStateFlow { expect, update ->
                    compare { services.settings.compareAndSet(index, expect, update) }
                },
                pending.asSuspendMutableStateFlow { expect, update ->
                    compare { runtime.compareAndSetPendingSteer(index, expect, update) }
                },
                tokenCount, owner, services, invalidate,
            )
        }
    }
}

private data class RpcStorage(
    override val uri: String,
    override val index: CachedIndexVersioned<CleanIndexEntry>,
    override val work: CachedIndexVersioned<StableWorkEvent>,
    override val settings: CachedIndexVersioned<KodexAgentSettings>,
    override val timestamp: CachedIndexVersioned<Instant>,
    override val tokenCount: CachedIndexVersioned<TokenCountSnapshot>,
    override val unstable: CachedIndexVersioned<List<UnstableCleanEvent>>,
) : ObservableKodexAgentStorage

private fun <T> Flow<T>.requiredState(): Flow<T> = onCompletion { cause ->
    if (cause == null) error("A required Session state subscription ended.")
}

/**
 * A changed nonce retries a still-required read, never a command. Initial Get is not polled.
 * Keep null (absent token usage) distinct from an actual stored zero snapshot.
 */
private suspend fun <T> CoroutineScope.visibleValue(
    timeline: CachedIndexVersioned<*>,
    latest: StateFlow<Int>,
    read: suspend (Int) -> T,
): StateFlow<T> {
    suspend fun load(): T {
        while (true) {
            val nonce = timeline.cacheNonce.value
            try {
                return read(latest.value)
            } catch (_: CacheNonceMismatch) {
                timeline.cacheNonce.first { it != nonce }
            }
        }
    }
    val value = MutableStateFlow(load())
    launch {
        combine(latest, timeline.cacheNonce, timeline.latestIndex) { position, nonce, tail ->
            Triple(position, nonce, tail)
        }.collect {
            value.value = load()
        }
    }
    return value.asStateFlow()
}
