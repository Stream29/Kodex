@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.getAskUsersAt
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrBefore
import io.github.stream29.kodex.agentstorage.contract.getLlmRequestMetaAtOrNull
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.agentstorage.contract.getSteerAt
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.rpc.client.asSuspendMutableStateFlow
import io.github.stream29.kodex.rpc.client.rpcCachedAgentStorage
import io.github.stream29.kodex.rpc.client.rpcStateIn
import io.github.stream29.kodex.rpc.contract.SuspendMutableStateFlow
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.ShellSessionState
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
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

public actual class RpcSessionBinding private constructor(
    public actual val sessionIndex: Int,
    public actual val storage: ObservableKodexAgentStorage,
    public actual val latestIndex: StateFlow<Int>,
    public actual val state: StateFlow<AgentStateValue>,
    public actual val displayState: StateFlow<KodexAgentStateValue>,
    public actual val running: StateFlow<Boolean>,
    public actual val shellSessions: StateFlow<Map<Int, ShellSessionState>>,
    public actual val settings: SuspendMutableStateFlow<KodexAgentSettings>,
    public actual val pendingSteer: StateFlow<List<StableIndexEvent.Steerable>>,
    internal val pendingAskUser: StateFlow<AskUserEntry?>,
    public actual val tokenCount: StateFlow<LlmRequestMeta?>,
    private val owner: Job,
    private val services: RpcServices,
    private val invalidate: (Throwable) -> Unit,
) {
    public actual fun ensureActive(): Unit = owner.ensureActive()
    /** Reports against this exact binding generation, not whichever tab binding comes later. */
    internal fun failRecovery(failure: Throwable) { if (owner.isActive) invalidate(failure) }

    private suspend fun <R> command(action: suspend () -> R): R =
        runSessionCommand(owner, invalidate, action)

    public actual suspend fun submit(content: List<ContentItem>): Unit =
        command { services.runtime.submit(sessionIndex, content) }
    public actual suspend fun resume(): Unit = command { services.runtime.resume(sessionIndex) }
    public actual suspend fun stop(): Unit = command { services.runtime.cancelRunningTurn(sessionIndex) }
    public actual suspend fun forcedCompact(): Int = command { services.runtime.forcedCompact(sessionIndex) }
    public actual suspend fun clearPending(): Int = command { services.runtime.clearPending(sessionIndex) }
    public actual suspend fun submitAskUserAnswer(askId: Int, response: RequestUserInputResponse): Unit =
        command { services.runtime.submitAskUserAnswer(sessionIndex, askId, response) }
    public actual suspend fun closeShellSession(id: Int): Unit =
        command { services.runtime.closeShellSession(sessionIndex, id) }
    public actual suspend fun revertHistory(untilExclusive: Int, selectedCacheNonce: Long): Unit =
        command { services.runtime.revertHistory(sessionIndex, untilExclusive, selectedCacheNonce) }
    public actual suspend fun forkHistory(untilExclusive: Int, selectedCacheNonce: Long): Int =
        command { services.global.forkSessionHistory(sessionIndex, untilExclusive, selectedCacheNonce) }
    public actual suspend fun readCreatedAt(): Instant? =
        storage.getCommitAtOrBefore(0)?.createdAt?.takeIf { it != Instant.DISTANT_PAST }
    public actual suspend fun readUpdatedAt(): Instant? =
        latestIndex.value.takeIf { it >= 0 }?.let {
            storage.getCommitAtOrBefore(it)?.createdAt?.takeIf { ts -> ts != Instant.DISTANT_PAST }
        }

    internal companion object {
        suspend fun create(
            index: Int,
            services: RpcServices,
            scope: CoroutineScope,
            invalidate: (Throwable) -> Unit,
        ): RpcSessionBinding {
            val owner = requireNotNull(scope.coroutineContext[Job])
            val runtime = services.runtime
            val storage = scope.rpcCachedAgentStorage(
                sessionIndex = index,
                rpc = runtime,
            )
            val latest = storage.latestIndexFlow
            val state = scope.rpcStateIn(
                { runtime.getState(index) }, { runtime.getStateFlow(index).requiredState() },
            )
            val running = scope.rpcStateIn(
                { runtime.getRunningTurn(index) }, { runtime.getRunningTurnFlow(index).requiredState() },
            )
            val shell = scope.rpcStateIn(
                { runtime.getShellSessions(index) }, { runtime.getShellSessionsFlow(index).requiredState() },
            )
            val pending = scope.visibleValue(storage) { position ->
                if (position >= 0) storage.getSteerAt(position) else emptyList()
            }
            val pendingAskUser = scope.visibleValue(storage) { position ->
                if (position >= 0) {
                    storage.getAskUsersAt(position)
                        .filter { it.answer == null }
                        .minByOrNull { it.askId }
                } else {
                    null
                }
            }
            val settings = scope.visibleValue(storage) { position -> storage.getSettingsAt(position) }
            val tokenCount = scope.visibleValue(storage) { position ->
                if (position >= 0) storage.getLlmRequestMetaAtOrNull(position) else null
            }
            return RpcSessionBinding(
                index, storage, latest, state, scope.projectOutput(index, runtime, state), running, shell,
                settings.asSuspendMutableStateFlow { expect, update ->
                    runSessionCommand(owner, invalidate) { runtime.compareAndSetSettings(index, expect, update) }
                },
                pending,
                pendingAskUser,
                tokenCount, owner, services, invalidate,
            )
        }
    }
}

private suspend fun <R> runSessionCommand(
    owner: Job,
    invalidate: (Throwable) -> Unit,
    action: suspend () -> R,
): R {
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

private fun <T> Flow<T>.requiredState(): Flow<T> = onCompletion { cause ->
    if (cause == null) error("A required Session state subscription ended.")
}

/**
 * A changed nonce retries a still-required read, never a command. Initial Get is not polled.
 * Keep null (absent token usage) distinct from an actual stored zero snapshot.
 */
private suspend fun <T> CoroutineScope.visibleValue(
    storage: ObservableKodexAgentStorage,
    read: suspend (Int) -> T,
): StateFlow<T> {
    suspend fun load(): T {
        while (true) {
            val nonce = storage.cacheNonce.value
            try {
                return read(storage.latestIndexFlow.value)
            } catch (_: CacheNonceMismatch) {
                storage.cacheNonce.first { it != nonce }
            }
        }
    }
    val value = MutableStateFlow(load())
    launch {
        combine(storage.cacheNonce, storage.latestIndexFlow) { nonce, tail ->
            nonce to tail
        }.collect {
            value.value = load()
        }
    }
    return value.asStateFlow()
}
