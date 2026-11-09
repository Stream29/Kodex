@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.rpc.contract.AgentStorageRpc
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job

/** Uses the actual cache metadata, never a service-owned index or random marker. */
public actual class BackendAgentStorageRpc actual constructor(
    private val host: BackendSessionHost,
) : AgentStorageRpc {
    private suspend fun cache(sessionIndex: Int): ObservableKodexAgentStorage =
        host.session(sessionIndex).session.storage as ObservableKodexAgentStorage

    private suspend fun <R> checked(
        sessionIndex: Int,
        nonce: Long,
        block: suspend ObservableKodexAgentStorage.() -> R,
    ): R {
        val binding = host.session(sessionIndex)
        val storage = binding.session.storage as ObservableKodexAgentStorage
        if (storage.cacheNonce.value != nonce) throw CacheNonceMismatch()
        return try {
            storage.block()
        } catch (failure: IllegalStateException) {
            if (binding.inactive.isCompleted || !binding.session.coroutineContext.job.isActive) {
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
        val binding = host.session(sessionIndex)
        emitAll(binding.observe((binding.session.storage as ObservableKodexAgentStorage).cacheNonce))
    }

    actual override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int> = flow {
        val binding = host.session(sessionIndex)
        emitAll(binding.observe((binding.session.storage as ObservableKodexAgentStorage).latestIndexFlow))
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
    ): Boolean = host.inSession(sessionIndex) { runtime.compareAndSetSettings(expect, update) }
}
