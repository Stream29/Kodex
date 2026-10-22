@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.contract.KodexRootSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns one lazily acquired repository and its accepted work. The factory runs
 * only on actual [BackendSessionHost.repository] access and must create the
 * repository in its receiver scope; an existing frontend-owned instance is
 * not a valid factory result. Neither the host nor its bindings may escape.
 *
 * Normal return, failure and cancellation wait for repository/Session cleanup.
 * A cancelled RPC waiter does not cancel work already accepted by this host.
 */
public expect suspend fun <R> withBackendSessionHost(
    createRepository: suspend CoroutineScope.() -> KodexRootSessionRepository,
    renewBackend: suspend () -> Unit = {},
    backendRenewalInterval: () -> Duration = { 20.seconds },
    checkAdmission: () -> Unit = {},
    block: suspend CoroutineScope.(BackendSessionHost) -> R,
): R

/** Backend-only resource; no Job, repository or binding crosses the RPC boundary. */
public expect class BackendSessionHost {
    /**
     * Acquires the actual repository once under the original backend owner.
     * Concurrent first accesses share one successful factory result; failure or
     * cancellation never publishes a partial/dead owner. A factory is responsible
     * for cleaning its failed acquisition. Accepted work outlives an RPC waiter.
     * Closing an untouched host never invokes the factory.
     */
    public suspend fun repository(): KodexRootSessionRepository

    /** One service-graph admission check shared by commands and newly requested observations. */
    public fun ensureAdmission()

    /** Explicit activation; a successful return grants a full TTL after loading. */
    public suspend fun keepSessionAlive(index: Int)

    /** Resolves a live binding without loading or extending its lifetime. */
    public suspend fun session(index: Int): BackendSessionBinding

    /**
     * Accepts work into the Session scope, retaining its complete result/wait
     * semantics. Only a runningTurn renews residency; other work can be
     * cancelled by TTL expiry. The block must not re-enter host lifecycle APIs
     * before its first suspension.
     */
    public suspend fun <R> inSession(index: Int, block: suspend KodexAgentSession.() -> R): R

    /** Global accepted work belongs to the host, not its RPC waiter. */
    public suspend fun <R> inBackend(block: suspend () -> R): R

    /** Coordinates delete with activation/expiry; missing entries remain false. */
    public suspend fun deleteSession(index: Int): Boolean
}

/** A binding is tied to one owner, never silently rebound after reactivation. */
public expect class BackendSessionBinding {
    public val session: KodexAgentSession
    public val inactive: Deferred<Unit>

    /** Ends existing RPC upstreams on deactivation without keeping the owner alive. */
    public fun <T> observe(source: Flow<T>): Flow<T>
}
