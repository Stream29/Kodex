@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.rpc.contract.AgentStorageRpc
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Uses the actual cache metadata, never a service-owned index or random marker. */
public actual class BackendAgentStorageRpc actual constructor(
    private val repository: KodexSessionRepository,
    private val checkAdmission: () -> Unit,
) : AgentStorageRpc {
    private val scopesMutex = Mutex()
    private val operationScopes = mutableMapOf<KodexAgentSession, CoroutineScope>()

    private fun openSession(sessionIndex: Int): KodexAgentSession {
        checkAdmission()
        val session = repository.openSessions.value[sessionIndex] ?: throw SessionNotActive()
        if (!session.coroutineContext.job.isActive) throw SessionNotActive()
        return session
    }

    private fun cache(sessionIndex: Int): ObservableKodexAgentStorage =
        openSession(sessionIndex).storage as ObservableKodexAgentStorage

    private suspend fun operationScopeFor(session: KodexAgentSession): CoroutineScope =
        scopesMutex.withLock {
            if (!session.coroutineContext.job.isActive) throw SessionNotActive()
            operationScopes.getOrPut(session) {
                session.supervisorChildScope().also {
                    session.launch {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                scopesMutex.withLock { operationScopes.remove(session) }
                            }
                        }
                    }
                }
            }
        }

    private suspend fun <R> checked(
        sessionIndex: Int,
        nonce: Long,
        block: suspend ObservableKodexAgentStorage.() -> R,
    ): R {
        val session = openSession(sessionIndex)
        val storage = session.storage as ObservableKodexAgentStorage
        if (storage.cacheNonce.value != nonce) throw CacheNonceMismatch()
        return try {
            storage.block()
        } catch (failure: IllegalStateException) {
            if (!session.coroutineContext.job.isActive) {
                throw SessionNotActive()
            }
            throw failure
        }
    }

    actual override suspend fun getCacheNonce(sessionIndex: Int): Long =
        cache(sessionIndex).cacheNonce.value

    actual override suspend fun getLatestIndex(sessionIndex: Int): Int =
        cache(sessionIndex).latestIndexFlow.value

    actual override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long> = flow {
        val session = openSession(sessionIndex)
        emitAll(session.observeActive(checkAdmission, (session.storage as ObservableKodexAgentStorage).cacheNonce))
    }

    actual override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int> = flow {
        val session = openSession(sessionIndex)
        emitAll(session.observeActive(checkAdmission, (session.storage as ObservableKodexAgentStorage).latestIndexFlow))
    }

    actual override suspend fun countCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
    ): Int =
        checked(sessionIndex, cacheNonce) { countCommitsIn(fromInclusive..toInclusive, types) }

    actual override suspend fun getFirstCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> =
        checked(sessionIndex, cacheNonce) { getFirstCommitsIn(fromInclusive..toInclusive, types, limit) }

    actual override suspend fun getLastCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit> =
        checked(sessionIndex, cacheNonce) { getLastCommitsIn(fromInclusive..toInclusive, types, limit) }

    actual override suspend fun compareAndSetSettings(
        sessionIndex: Int,
        expect: KodexAgentSettings,
        update: KodexAgentSettings,
    ): Boolean {
        val session = openSession(sessionIndex)
        val ops = operationScopeFor(session)
        return ops.async(start = CoroutineStart.UNDISPATCHED) {
            if (!session.coroutineContext.job.isActive) throw SessionNotActive()
            session.runtime.compareAndSetSettings(expect, update)
        }.await()
    }
}

internal fun <T> KodexAgentSession.observeActive(
    checkAdmission: () -> Unit,
    source: Flow<T>,
): Flow<T> = channelFlow {
    checkAdmission()
    val sessionJob = this@observeActive.coroutineContext.job
    if (!sessionJob.isActive) throw SessionNotActive()
    val deactivated = CompletableDeferred<Unit>()
    val completionHandle = sessionJob.invokeOnCompletion {
        deactivated.complete(Unit)
    }
    val watcher = launch {
        deactivated.await()
        throw SessionNotActive()
    }
    try {
        if (!sessionJob.isActive) throw SessionNotActive()
        source.collect { send(it) }
    } finally {
        completionHandle.dispose()
        watcher.cancel()
    }
}
