package io.github.stream29.kodex.rpc.contract

import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * Settings timeline reads and current-settings CAS for a persisted root Agent.
 *
 * Follows [TimelineRpc]'s initialization-only metadata getters, current-value flows,
 * cache-nonce-checked data queries, and inclusive range semantics. The cache nonce and
 * latest index belong to this settings-timeline owner. Cancelling a subscription does not
 * close the Session. Current values are projected from this timeline's metadata and value reads,
 * without a separate current-settings service or value flow.
 *
 * This is the only frontend-writable timeline: [compareAndSet] updates the current settings
 * through the backend's business write boundary, not an arbitrary historical index.
 * The connected backend serves this timeline; frontend clients borrow its
 * reads/CAS capabilities without acquiring ownership of the Session.
 */
@Rpc
public interface SettingsTimelineRpc : TimelineRpc<KodexAgentSettings> {
    public override suspend fun getCacheNonce(sessionIndex: Int): Long

    public override fun getCacheNonceFlow(sessionIndex: Int): Flow<Long>

    public override suspend fun getLatestIndex(sessionIndex: Int): Int

    public override fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>

    public override suspend fun get(
        sessionIndex: Int,
        cacheNonce: Long,
        index: Int,
    ): KodexAgentSettings

    public override suspend fun floorToIndex(
        sessionIndex: Int,
        cacheNonce: Long,
        index: Int,
    ): Int?

    public override suspend fun ceilToIndex(
        sessionIndex: Int,
        cacheNonce: Long,
        index: Int,
    ): Int?

    public override suspend fun indexesIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
    ): List<Int>

    public override suspend fun getExact(
        sessionIndex: Int,
        cacheNonce: Long,
        index: Int,
    ): KodexAgentSettings?

    public override suspend fun valuesIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
    ): List<Pair<Int, KodexAgentSettings>>

    /**
     * Compares the current complete settings with [expect] using value equality and, if equal,
     * commits [update] through the backend's existing settings-write boundary.
     * Comparison and commit are atomic with respect to other settings writes.
     *
     * Returns false without writing on mismatch. If current, expected and updated values are
     * equal, returns true without changing state. Validation, persistence and transport failures
     * are not comparison mismatches. An inactive Session fails with SessionNotActive.
     *
     * No historical index is writable through this operation. Backend state validity and
     * runtime-owned field constraints still apply; this is not CAS on a separate cache.
     * CAS compares current settings values, not a historical cache nonce. A successful append
     * advances this timeline's latest index without replacing its cache nonce.
     * Reads and updates do not renew TTL. Success does not synchronize frontend projections.
     *
     * After false, use the latest subscription-driven settings. Without observed progress,
     * cancellable, paced retries are allowed; do not require the settings value to change.
     * Retry an edit only if its target fields retain their original values; preserve unrelated changes.
     * A target-field change is a real conflict, not permission to rebase and overwrite it.
     * A lost reply does not establish whether the write occurred.
     */
    public suspend fun compareAndSet(
        sessionIndex: Int,
        expect: KodexAgentSettings,
        update: KodexAgentSettings,
    ): Boolean
}
