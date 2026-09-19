package io.github.stream29.kodex.rpc.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.ShellSessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * Runtime capabilities of the root Agent addressed by an existing Session index.
 *
 * Pending steer is the runtime's non-persistent queue, not a frontend composer or a history
 * snapshot. Its existing value types and ordering are preserved without a separate DTO.
 * Access requires an active Session; otherwise it fails with SessionNotActive without
 * implicit activation. Await keepSessionAlive before access. Deactivation ends existing
 * Session-bound upstreams with SessionNotActive, requiring activation and a new subscription.
 * Inactivity is distinct from currentFlow's output-binding NoMatchException.
 * This review contract has no client/server implementation.
 */
@Rpc
public interface AgentRuntimeRpc {
    /**
     * Reads the root Agent's original storage URI from its active backend owner.
     *
     * This stable locator has no companion flow. It is not a frontend-local path, permission
     * to bypass RPC for file access, or an owner/cache identity. Do not reconstruct it
     * from a frontend Home directory or assume a particular storage scheme.
     * Inactive Sessions fail with SessionNotActive; this read does not activate or renew TTL.
     */
    public suspend fun getStorageUri(sessionIndex: Int): String

    /** Reads the initial globally visible storage index published by the backend AgentState. */
    public suspend fun getLatestIndex(sessionIndex: Int): Int

    /**
     * Includes the current globally visible storage index, then the backend's published updates.
     * Retains AgentState's publication after storage transactions, rather than combining the
     * independently observed tails of its six sparse timelines on the frontend.
     *
     * Revert may decrease the index; intermediate values may be conflated. This value is not
     * a cache nonce or an immutable snapshot lease for subsequent RPC reads. Timeline queries
     * still require their own cache-nonce checks and stale-result handling.
     *
     * Cancelling collection does not close the Session; neither reads nor collection renew TTL.
     */
    public fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>

    /** Reads initial atomic state; subsequent state and active output changes use the flow. */
    public suspend fun getState(sessionIndex: Int): AgentStateValue

    /**
     * Includes current atomic state and subsequent updates, with random markers instead of
     * SharedFlow objects. Observe this independently of stored-history latestIndex updates.
     * Intermediate states may be conflated; this is not a log of every request or output.
     */
    public fun getStateFlow(sessionIndex: Int): Flow<AgentStateValue>

    /**
     * Binds to the currently active output only if its marker matches [nonce].
     *
     * At subscription binding, validate and capture the same current state snapshot and stream.
     * Started, an inactive output or a different marker rejects the subscription, rather than
     * returning an empty flow or selecting a newer stream. No historical-stream lookup exists.
     * Stale-binding failure must be distinguishable from unrelated operational failures.
     *
     * Collect the captured SharedFlow directly, replaying its retained complete event prefix
     * before following new events. Do not concatenate a replayCache snapshot with a separately
     * started live subscription. An accepted binding never switches to a replacement stream.
     * A stream that has already ended or been replaced need not accept a new binding.
     *
     * The frontend keeps state observation independent, cancels the old upstream when its
     * marker changes or output leaves state, and creates a fresh local replay accumulator.
     * Original SharedFlow does not finish by itself. Cancellation releases this subscription,
     * not the Agent; it neither requests a model retry nor renews the Session TTL.
     */
    public fun currentFlow(sessionIndex: Int, nonce: Long): Flow<ResponsesStreamEvent>

    /**
     * Appends one user message and returns its committed storage index.
     *
     * Retains the existing atomic state admission, timestamp and persisted turn-id calculation.
     * Does not introduce a separate markNewTurn operation. Requests targeting an invalid state
     * fail rather than bypassing admission; input during execution uses pendingSteer instead.
     *
     * After committing the message, the backend checks automatic-title eligibility using its
     * title settings and owns any resulting auxiliary task. Does not wait for title generation;
     * title failure cannot undo the message or turn an accepted write into a failed append.
     * Does not resume the Agent, clear the frontend composer or renew TTL.
     * Accepted writes belong to the backend owner; cancelling the caller's wait does not undo
     * them. A lost reply or a later resume failure must not trigger a blind repeat append.
     * The returned index does not imply that frontend subscriptions have caught up.
     */
    public suspend fun appendUserMessage(sessionIndex: Int, content: List<ContentItem>): Int

    /**
     * Runs one complete runtime operation, preserving AgentRuntime.resume's waiting semantics.
     *
     * Returns only after normal completion, not merely after starting work. Failures are not
     * successful completion; intermediate progress is observed independently. Retains the
     * runtime's rejection of concurrent resume calls, without queuing or coalescing them.
     *
     * After acceptance, the operation belongs to the backend Agent lifetime. Cancelling the
     * caller's wait is not an explicit Stop and must not cancel that accepted operation.
     * The backend adapter must not use the RPC handler Job as the running turn's owner.
     * Shares one running-turn slot and admission boundary with manual compaction;
     * concurrent execution is rejected rather than queued.
     * The backend renews Session TTL while the running-turn slot is occupied, through cleanup.
     * Entire CLI host shutdown still cleans up both sides.
     */
    public suspend fun resume(sessionIndex: Int): Unit

    /**
     * Performs the existing forcedCompact operation and returns its committed checkpoint index.
     *
     * Uses Manual/UserRequested/StandaloneTurn metadata and preserves backend state admission.
     * Waits for completion rather than returning after launch; failures are not successful
     * checkpoint results. Does not resume the Agent or accept automatic-compaction policy input.
     *
     * Accepted work belongs to the backend owner and occupies the same running-turn slot as
     * resume, without concurrent execution. The backend turn renews Session TTL throughout its
     * lifetime. Cancelling the caller's wait is not explicit cancellation of compaction;
     * cancelRunningTurn cancels this work.
     * Shared ownership does not change compaction metadata or its operation-specific cleanup.
     * Manual and automatic compaction publish only the Compacting state, without an output
     * flow or nonce. SSE collection, retries and checkpoint validation remain backend-owned.
     */
    public suspend fun forcedCompact(sessionIndex: Int): Int

    /**
     * Completes current pending tool calls as user-interrupt failures using the existing helper.
     *
     * Waits for the cleanup loop and returns its final storage index; with no pending tools,
     * returns the current index. Each tool result retains completeToolCall's validation and
     * atomic history/pending-snapshot update. The loop is not one all-or-nothing transaction;
     * already committed results are not rolled back if a later completion fails.
     *
     * Does not clear pendingSteer, cancel the Agent, resume it or renew TTL. Missing Sessions
     * and operational failures are errors, not successful empty cleanup. Accepted work is
     * backend-owned; cancelling the caller's wait does not cancel accepted cleanup.
     */
    public suspend fun clearPending(sessionIndex: Int): Int

    /**
     * Commits one completed tool event and returns its storage index.
     *
     * Retains the original ToolPending admission and projected-output callId validation.
     * Writes the original index/work event and removes the matching unstable pending call in
     * the same state transition; other pending calls remain pending. A stale or already
     * completed call fails instead of appending another result.
     *
     * Does not create suggested Sessions, edit frontend drafts, resume the Agent or renew TTL.
     * Accepted completion belongs to the backend owner. Returning does not synchronize frontend
     * state/history projections, and a failed reply does not prove that nothing was committed.
     */
    public suspend fun completeToolCall(
        sessionIndex: Int,
        completed: StableCleanEvent.CompletedTool,
    ): Int

    /**
     * Removes history at and after [untilExclusive], preserving initialization.
     *
     * [expectedCacheNonce] is the backend index timeline cache nonce captured with the frontend
     * target, not a frontend window counter. Validate it at the actual mutation boundary along
     * with the original not-running/history-replacement admission. Sparse boundaries are valid:
     * the boundary must be positive and no greater than the current storage latest index + 1.
     *
     * Preserves automatic-title coordination and clearing pendingSteer after the history revert.
     * Confirmation and editing the frontend composer remain local; no confirmation handle is
     * transferred. A stale target fails with CacheNonceMismatch rather than being rebound to
     * the current cache nonce. The frontend must reselect or reconfirm a valid target, not
     * automatically retry this command with a replacement cache nonce.
     *
     * Waits for completion of backend-owned work without renewing TTL. Cancelling the caller's
     * wait does not cancel accepted work; returning does not synchronize frontend history caches.
     */
    public suspend fun revertHistory(
        sessionIndex: Int,
        untilExclusive: Int,
        expectedCacheNonce: Long,
    ): Unit

    /** Reads the initial shell registry snapshot; subsequent changes use [getShellSessionsFlow]. */
    public suspend fun getShellSessions(sessionIndex: Int): Map<Int, ShellSessionState>

    /**
     * Includes the current registry and subsequent membership and process-completion changes.
     *
     * Keys are the original shell process session IDs, scoped to this Agent. Completed processes
     * retained for final tool output reads remain in the map with completed=true. Absence and
     * completion are distinct; this is not a list filtered to running processes.
     *
     * Project both registry membership and each registered process's completed state. Mapping
     * only the registry misses completion updates that do not change membership. The frontend
     * observes this single value flow and may derive local per-process presentation from it.
     *
     * Cancelling collection does not close processes, stop the Agent or renew the Session TTL.
     * Does not expose process output consumption, command execution or stdin writing.
     */
    public fun getShellSessionsFlow(sessionIndex: Int): Flow<Map<Int, ShellSessionState>>

    /**
     * Requests process-tree termination for the currently registered [shellSessionId].
     *
     * Resolve the process in the specified Agent and capture that instance; missing Session or
     * process fails. IDs identify current registry entries, not permanent identities across
     * removal or owner reconstruction. Does not redirect an accepted operation to another entry.
     *
     * Preserves the original close behavior: leave registration and final output available for
     * the backend tool's final read. Returns after requesting termination, not after completion
     * or output consumption; does not optimistically publish completed=true.
     * Does not close the Kodex Session, stop the Agent or renew TTL.
     */
    public suspend fun closeShellSession(sessionIndex: Int, shellSessionId: Int): Unit

    /** Reads whether ordinary execution or manual compaction owns the running-turn slot. */
    public suspend fun getRunningTurn(sessionIndex: Int): Boolean

    /**
     * Includes current task presence and subsequent changes without transferring a Job.
     * Both resume and manual compaction occupy this slot; history operations do not.
     * A cancellation request may leave this true until the turn exits and clears its slot.
     * Cancelling collection does not cancel the turn or close its Session.
     */
    public fun getRunningTurnFlow(sessionIndex: Int): Flow<Boolean>

    /**
     * Requests cancellation of ordinary execution or manual compaction current when handled.
     *
     * An existing Session with no current turn is a no-op; a missing Session is an error.
     * Does not identify a particular previously observed turn or queue cancellation for a future
     * turn. Returns after requesting cancellation, not after the turn's cleanup completes.
     * Completion is observed through [getRunningTurnFlow], not inferred from this reply.
     *
     * Preserves operation-specific cleanup, including pending-tool interruption for resume.
     * Serves as the frontend Stop command for both kinds of work. Does not explicitly clear
     * pendingSteer, cancel history operations, close the owner or renew TTL.
     */
    public suspend fun cancelRunningTurn(sessionIndex: Int): Unit

    /** Reads the initial queue; subsequent reads and CAS retries use the subscription. */
    public suspend fun getPendingSteer(sessionIndex: Int): List<StableIndexEvent.Steerable>

    /**
     * Includes the current queue when collection begins, followed by updates, including runtime
     * consumption. Cancelling collection neither clears the queue nor stops the Agent.
     */
    public fun getPendingSteerFlow(sessionIndex: Int): Flow<List<StableIndexEvent.Steerable>>

    /**
     * Compares and replaces the complete authoritative queue using list value equality.
     *
     * Operates on the runtime's actual pendingSteer MutableStateFlow, sharing its atomic boundary
     * with the runtime's get-and-clear consumption, not on a separately synchronized mirror.
     * A mismatch returns false without writing; matching equal values succeed without change.
     * Failures resolving the Agent or performing the operation are not comparison mismatches.
     *
     * Success only means the queue replacement was accepted, not that its content was persisted
     * or consumed by the model, and does not synchronize the frontend projection. After false,
     * recompute from the latest subscribed queue; cancellable, paced retries are allowed even
     * without an observed change. Do not poll Get, spin, or bypass the atomic comparison to
     * restore consumed content. A failed transport does not establish whether replacement
     * occurred and must not cause blind replay.
     *
     * An empty update clears queued steer, not pending tool calls. This is not an append-history,
     * submit or resume command and does not renew the Session retention deadline.
     */
    public suspend fun compareAndSetPendingSteer(
        sessionIndex: Int,
        expect: List<StableIndexEvent.Steerable>,
        update: List<StableIndexEvent.Steerable>,
    ): Boolean
}
