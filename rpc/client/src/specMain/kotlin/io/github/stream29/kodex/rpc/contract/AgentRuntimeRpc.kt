package io.github.stream29.kodex.rpc.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.SearchResponse
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.GeneratedImageArtifact
import io.github.stream29.kodex.rpc.models.ImageGenToolArguments
import io.github.stream29.kodex.rpc.models.ShellSessionState
import io.github.stream29.kodex.rpc.models.SubagentSpawnRequest
import io.github.stream29.kodex.rpc.models.SubagentSpawnResult
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * Runtime and storage capabilities of the root Agent addressed by an existing Session index.
 *
 * Pending steer is persisted in the target Agent's storage via `SteerSnapshot` and observed by
 * the frontend directly through `AgentStorage` (`getSteerAt(latestIndex)`), while user input
 * submission goes through [submit].
 * Targets are AgentIds, never SessionIds. Access does not implicitly open a target.
 * AgentNotFound denotes absent persistence and AgentNotActive denotes absent or retired
 * residency. Deactivation ends existing owner-bound upstreams with AgentNotActive.
 * Inactivity is distinct from currentFlow's output-binding NoMatchException.
 */
@Rpc
public interface AgentRuntimeRpc {
    /** Starts a new child of the verified calling Agent, without waiting for the child's response. */
    public suspend fun spawnSubagent(request: SubagentSpawnRequest): SubagentSpawnResult

    /** Archives a root's Session or completes a child without stopping its current turn. */
    public suspend fun completeAgent(agentId: Int, expectedInvocationId: String? = null)

    /**
     * Activates or renews only the exact Agent, without unarchiving its Session.
     * Running turns renew their own deadline; ordinary reads and subscriptions do not.
     */
    public suspend fun keepAgentAlive(agentId: Int)

    /** Reads the initial random cache-validity nonce for this storage owner. */
    public suspend fun getCacheNonce(sessionIndex: Int): Long

    /** Includes the current cache nonce when collection begins, followed by replacements. */
    public fun getCacheNonceFlow(sessionIndex: Int): Flow<Long>

    /** Reads the initial greatest stored index, or -1 for an empty storage. */
    public suspend fun getLatestIndex(sessionIndex: Int): Int

    /**
     * Includes the current greatest stored index when collection begins, followed by changes.
     *
     * Revert may decrease the index; intermediate values may be conflated. This value is not
     * a cache nonce or an immutable snapshot lease for subsequent RPC reads. Timeline queries
     * still require their own cache-nonce checks and stale-result handling.
     *
     * Cancelling collection does not close the Session; neither reads nor collection renew TTL.
     */
    public fun getLatestIndexFlow(sessionIndex: Int): Flow<Int>

    /** Counts all commits in [fromInclusive..toInclusive] whose type is in [types]. */
    public suspend fun countCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType> = AgentCommitType.All,
    ): Int

    /** Reads up to [limit] earliest commits in [fromInclusive..toInclusive] whose type is in [types]. */
    public suspend fun getFirstCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType> = AgentCommitType.All,
        limit: Int = Int.MAX_VALUE,
    ): List<IndexedAgentCommit>

    /** Reads up to [limit] latest commits in [fromInclusive..toInclusive] whose type is in [types] in ascending order. */
    public suspend fun getLastCommitsIn(
        sessionIndex: Int,
        cacheNonce: Long,
        fromInclusive: Int,
        toInclusive: Int,
        types: Set<AgentCommitType> = AgentCommitType.All,
        limit: Int = Int.MAX_VALUE,
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
     * Submits one user message through the global `AgentEventBus.submitUserMessage` channel.
     *
     * Requires an active Session (`SessionNotActive` otherwise). Persists the message into
     * `SteerSnapshot` and either lets the currently active turn consume the queued steer at its
     * next safe point (`runningTurn` occupied) or starts a new backend-owned `resume` turn
     * (`runningTurn` idle). Automatic session-title generation is a standalone capability
     * attached at Session creation, not part of [submit].
     *
     * Accepted work belongs to the backend Agent lifetime; cancelling the caller's wait does
     * not undo the persisted steer or cancel an accepted turn.
     */
    public suspend fun submit(sessionIndex: Int, content: List<ContentItem>, expectedInvocationId: String? = null): Unit

    /**
     * Submits a first-time user answer for `ask_user` entry [askId] on [sessionIndex] through
     * the global `AgentEventBus.submitAskUserAnswer` channel.
     *
     * Requires an active Session (`SessionNotActive` otherwise). Atomically records [response]
     * in `AskUserSnapshot` and appends `StableAskUserEvent` to `SteerSnapshot`, starting a
     * backend-owned `resume` turn when `runningTurn` is idle.
     */
    public suspend fun submitAskUserAnswer(
        sessionIndex: Int,
        askId: Int,
        response: RequestUserInputResponse,
    ): Unit

    /**
     * Creates a new unanswered [AskUserEntry] in [sessionIndex]'s `AskUserSnapshot` (`kodex ask create`).
     *
     * Requires an active Session (`SessionNotActive` otherwise) and
     * `settings.requestUserInputMode == RequestUserInputMode.AskUser` (fails explicitly when `NoQuestion`).
     * Immediately emits `Notification.AskUserCreated(sessionIndex, entry)` onto `BackendServices.notifications`.
     */
    public suspend fun createAskUser(
        sessionIndex: Int,
        arguments: RequestUserInputArgs,
    ): AskUserEntry

    /**
     * Executes a Codex web-search query batch using the backend's authenticated OpenAI client and
     * [sessionIndex]'s current model (`kodex search`).
     *
     * Requires an active Session (`SessionNotActive` otherwise) without renewing TTL.
     */
    public suspend fun runWebSearch(
        sessionIndex: Int,
        commands: SearchCommands,
    ): SearchResponse

    /**
     * Generates or edits a PNG image using the backend's authenticated OpenAI client and
     * [sessionIndex]'s current working directory, persisting the PNG under `<KodexHome>/media/generated/<sessionIndex>/`
     * and returning its file artifact metadata (`kodex image`).
     *
     * Requires an active Session (`SessionNotActive` otherwise) without renewing TTL.
     */
    public suspend fun generateImage(
        sessionIndex: Int,
        arguments: ImageGenToolArguments,
    ): GeneratedImageArtifact

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
     * A failed accepted run that offered an UnhandledError Stop throws
     * AgentRunFailureReported; clients must not also send a local error notification.
     */
    public suspend fun resume(sessionIndex: Int): Unit

    /** Starts the same backend-owned operation; returns when this invocation is admitted, not completed. */
    public suspend fun requestResume(sessionIndex: Int): Boolean

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
     * Non-destructive output cursor reads and standard-input writes use [readShellSessionOutput]
     * and [writeShellSessionStdin].
     */
    public fun getShellSessionsFlow(sessionIndex: Int): Flow<Map<Int, ShellSessionState>>

    /**
     * Non-destructively reads buffered output from [shellSessionId] starting at byte [offset]
     * up to [limit] retained bytes.
     *
     * Captures the currently registered process instance in the active backend Session without
     * implicitly activating an inactive Session (`SessionNotActive`) or renewing Session TTL;
     * unknown or evicted [shellSessionId], or a mismatch with [expectedRegistrationNonce],
     * fails with `ShellSessionNotRegistered`. A null nonce denotes numeric addressing.
     * Returns immediately from the captured buffer
     * snapshot without waiting for future output or consuming buffered bytes.
     */
    public suspend fun readShellSessionOutput(
        sessionIndex: Int,
        shellSessionId: Int,
        offset: ULong = 0uL,
        limit: UInt = UInt.MAX_VALUE,
        expectedRegistrationNonce: String? = null,
    ): StdoutSlice

    /**
     * Writes [chars] to [shellSessionId]'s standard input, optionally closing stdin afterwards
     * when [closeInput] is `true`.
     *
     * Captures the currently registered running process instance in the active backend Session
     * without implicitly activating an inactive Session (`SessionNotActive`) or renewing Session
     * TTL; unknown/evicted [shellSessionId], completed process, or closed input fails. Accepted
     * writes belong to the backend Session owner and complete when flushed to the process pipe.
     */
    public suspend fun writeShellSessionStdin(
        sessionIndex: Int,
        shellSessionId: Int,
        chars: String,
        closeInput: Boolean = false,
    ): Unit

    /**
     * Requests process-tree termination for the currently registered [shellSessionId].
     *
     * Resolve the process in the specified Agent and capture that instance; missing Session or
     * process, or a mismatch with [expectedRegistrationNonce], fails. A null nonce denotes
     * numeric addressing. IDs identify current registry entries, not permanent identities across
     * removal or owner reconstruction. Does not redirect an accepted operation to another entry.
     *
     * Preserves the original close behavior: leave registration and final output available for
     * the backend tool's final read. Returns after requesting termination, not after completion
     * or output consumption; does not optimistically publish completed=true.
     * Does not close the Kodex Session, stop the Agent or renew TTL.
     */
    public suspend fun closeShellSession(
        sessionIndex: Int,
        shellSessionId: Int,
        expectedRegistrationNonce: String? = null,
    ): Unit

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
}
