@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.rpc.contract.AgentStorageRpc
import kotlinx.coroutines.flow.Flow

public expect class BackendAgentStorageRpc(host: BackendSessionHost) : AgentStorageRpc {
    override suspend fun getCacheNonce(sessionIndex: Int): Long
    override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long>
    override suspend fun getLatestIndex(sessionIndex: Int): Int
    override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>
    override suspend fun countCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
    ): Int
    override suspend fun getFirstCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit>
    override suspend fun getLastCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType>,
        limit: Int,
    ): List<IndexedAgentCommit>
    override suspend fun compareAndSetSettings(
        sessionIndex: Int,
        expect: KodexAgentSettings,
        update: KodexAgentSettings,
    ): Boolean
}
