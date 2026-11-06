package io.github.stream29.kodex.rpc.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * Atomic storage read contract and current-settings CAS for a persisted root Agent.
 */
@Rpc
public interface AgentStorageRpc {
    /** Reads the initial random cache-validity nonce for this storage owner. */
    public suspend fun getCacheNonce(sessionIndex: Int): Long

    /** Includes the current cache nonce when collection begins, followed by replacements. */
    public fun getCacheNonceFlow(sessionIndex: Int): Flow<Long>

    /** Reads the initial greatest stored index, or -1 for an empty storage. */
    public suspend fun getLatestIndex(sessionIndex: Int): Int

    /** Includes the current greatest stored index when collection begins, followed by changes. */
    public fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>

    /** Reads all distinct commit indexes in [fromInclusive..toInclusive] whose type is in [types] in ascending order. */
    public suspend fun indexesIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType> = AgentCommitType.All,
    ): List<Int>

    /** Reads all commits stored at exact [index] whose type is in [types]. */
    public suspend fun getCommitsExact(
        sessionIndex: Int,
        cacheNonce: Long,
        index: Int,
        types: Set<AgentCommitType> = AgentCommitType.All,
    ): List<AgentCommit>

    /** Reads all commits in [fromInclusive..toInclusive] whose type is in [types] in ascending order. */
    public suspend fun getCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType> = AgentCommitType.All,
    ): List<IndexedAgentCommit>

    /** Reads up to [limit] earliest commits in [fromInclusive..toInclusive] whose type is in [types]. */
    public suspend fun getFirstCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType> = AgentCommitType.All,
        limit: Int,
    ): List<IndexedAgentCommit>

    /** Reads up to [limit] latest commits in [fromInclusive..toInclusive] whose type is in [types] in ascending order. */
    public suspend fun getLastCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType> = AgentCommitType.All,
        limit: Int,
    ): List<IndexedAgentCommit>

    /**
     * Compares the current complete settings with [expect] using value equality and, if equal,
     * commits [update] through the backend's existing settings-write boundary.
     */
    public suspend fun compareAndSetSettings(
        sessionIndex: Int,
        expect: KodexAgentSettings,
        update: KodexAgentSettings,
    ): Boolean
}
