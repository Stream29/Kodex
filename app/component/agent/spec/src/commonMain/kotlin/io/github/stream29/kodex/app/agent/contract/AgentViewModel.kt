package io.github.stream29.kodex.app.agent.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.rpc.models.AgentStateValue
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import kotlinx.io.files.Path

/**
 * Direct settings contract implemented by every stable settings owner.
 *
 * Field-specific commands must transform the latest complete snapshot so a
 * frontend cannot overwrite unrelated runtime-owned settings with an old copy.
 * Implementations receive their actual settings source, model catalog and write
 * dependency through their existing typed constructor/factory, not through a renderer
 * or Application service locator. Settings/models are borrowed observations.
 *
 * Persisted Agent commands capture their field baseline, validate the exact still-open
 * binding and use full-snapshot CAS; an unrelated-field conflict may be retried but a
 * changed edited field is rejected. A New Session owner instead edits its exact local
 * draft through its existing admission rules. Neither uses the application settings queue.
 * Render settings from this observation or the stable Runtime Configuration child;
 * never keep another editable configuration authority in the parent renderer.
 */
public interface AgentSettingsViewModel {
    public val settings: StateFlow<KodexAgentSettings>
    public val models: StateFlow<List<ModelInfo>>

    /**
     * @throws CancellationException when the owner/binding is closed or the caller stops waiting.
     * @throws IllegalStateException when the edited baseline changed.
     * @throws Exception when the settings write fails; cancellation stops the caller's wait.
     */
    public suspend fun updateModel(model: OpenAiModelId): Unit

    /**
     * @throws CancellationException when the owner/binding is closed or the caller stops waiting.
     * @throws IllegalStateException when the edited baseline changed.
     * @throws Exception when the settings write fails; cancellation stops the caller's wait.
     */
    public suspend fun updateWorkingDirectory(workingDirectory: Path): Unit

    /**
     * @throws CancellationException when the owner/binding is closed or the caller stops waiting.
     * @throws IllegalStateException when the edited baseline changed.
     * @throws Exception when the settings write fails; cancellation stops the caller's wait.
     */
    public suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort): Unit

    /**
     * @throws CancellationException when the owner/binding is closed or the caller stops waiting.
     * @throws IllegalStateException when the edited baseline changed.
     * @throws Exception when the settings write fails; cancellation stops the caller's wait.
     */
    public suspend fun updateServiceTier(serviceTier: ServiceTier): Unit

    /**
     * @throws CancellationException when the owner/binding is closed or the caller stops waiting.
     * @throws IllegalStateException when the edited baseline changed.
     * @throws Exception when the settings write fails; cancellation stops the caller's wait.
     */
    public suspend fun updateRequestUserInputMode(mode: RequestUserInputMode): Unit

    /**
     * Atomically updates the three fields selected by the runtime model menu.
     * Never split this into three writes.
     *
     * @throws CancellationException when the owner/binding is closed or the caller stops waiting.
     * @throws IllegalStateException when an edited baseline changed.
     * @throws Exception when the settings write fails; cancellation stops the caller's wait.
     */
    public suspend fun updateModelConfiguration(
        model: OpenAiModelId,
        reasoningEffort: ReasoningEffort,
        serviceTier: ServiceTier,
    ): Unit
}

/**
 * Frontend contract for one materialized Agent.
 *
 * Stable identity and mutable Agent-owned state are direct properties. Production
 * assembly uses the existing RPC factory
 * `createRpcAgentViewModel(binding, scope, services, models, onCreated)`: the exact
 * binding/services and model catalog are borrowed; scope owns the local Agent child;
 * onCreated receives accepted suggested-session results for navigation. Those inputs
 * are not renderer inputs or spec dependencies, and close does not close the shared
 * services/catalog/parent scope.
 * This owner belongs to one live binding, not to the currently selected tab.
 *
 * Composer, History, History Index, Runtime Configuration and the two pending-tool
 * children are stable for this owner's lifetime and are borrowed by renderers.
 * Unmounting a page/sidebar/popup does not close them or cancel accepted work.
 * History Index's per-consumer read handles still require their own release.
 *
 * Render the independent [state]/[running] facts without deriving another execution
 * aggregate. Show one pending-steer preview, the exact pending-tool child, and one
 * [notification] outlet. [tokenCount] null omits the counter. History context menus
 * capture the real item, index and generation. Ordinary asynchronous command failures
 * become Agent notifications; cancellation is not an ordinary failure. Session
 * inactivity restores observations, never replays accepted writes or creates.
 *
 * Primary control is Stop whenever running (including manual compaction), otherwise
 * Clear pending for ToolPending and Resume for all other states. Hide Compact while
 * running; when idle it is enabled only for UserMessage, AssistantMessage and
 * ToolCompleted. History editing is unavailable while running or in RequestResponse,
 * ExternalWrite or Compacting. These are visual predicates, not substitutes for
 * backend admission. Pending-tool branches borrow their existing child renderer and
 * disable ordinary Composer editing while host interaction is pending; never clone
 * answers/configuration into this parent.
 */
public interface AgentViewModel :
    AgentSettingsViewModel,
    AutoCloseable {
    /** Stable Storage URI of this root Session. */
    public val storageUri: String
    public val composer: ComposerViewModel
    public val history: AgentHistoryViewModel
    public val historyIndex: HistoryIndexViewModel
    /** Stable configuration child bound to this exact Agent, closed with it; not the selected tab. */
    public val runtimeConfiguration: RuntimeConfigurationViewModel
    public val requestUserInput: RequestUserInputViewModel
    public val suggestSubagentTask: SuggestSubagentTaskViewModel
    public val shellSessions: AgentShellSessionRegistry

    /** Independent backend facts; controls derive their own presentation predicates. */
    public val state: StateFlow<AgentStateValue>
    public val running: StateFlow<Boolean>
    public val latestIndex: StateFlow<Int>
    public val tokenCount: StateFlow<Long?>
    public val pendingSteer: StateFlow<List<StableIndexEvent.Steerable>>
    public val historyAction: StateFlow<AgentHistoryActionState>
    public val notification: StateFlow<AgentNotification?>
    public val lifecycle: StateFlow<AgentLifecycleState>

    /**
     * Submits content to this exact Agent address; accepted append is not replayed on recovery.
     *
     * @throws CancellationException when this owner/binding is closed or the caller stops waiting.
     * @throws Exception when append/steer fails; caller cancellation stops waiting.
     */
    public suspend fun submit(content: List<ContentItem>): Unit

    /**
     * Starts waiting for a backend-owned continuation; closing this view only cancels the wait.
     * Closed owners ignore the intent; an inactive binding does not retarget another Agent.
     * Ordinary failures are reported
     * through [notification], while cancellation is not reported as an operation failure.
     * @throws CancellationException when the captured binding is already closed at admission.
     */
    public fun resume(): Unit

    /**
     * Explicit Stop for this Agent; backend admission remains authoritative. Reports ordinary failures.
     * Closed owners ignore the intent rather than stopping another binding.
     * @throws CancellationException when the captured binding is already closed at admission.
     */
    public fun cancel(): Unit

    /**
     * Clears pending input for this Agent, subject to backend admission; reports ordinary failures.
     * Closed owners ignore the intent.
     * @throws CancellationException when the captured binding is already closed at admission.
     */
    public fun clearPending(): Unit

    /**
     * Starts waiting for backend-owned compaction; reports ordinary failures, not cancellation.
     * Closed owners ignore the intent.
     * @throws CancellationException when the captured binding is already closed at admission.
     */
    public fun forceCompact(): Unit

    /**
     * Updates only the thread name on the latest persisted settings snapshot.
     *
     * @throws IllegalArgumentException when [threadName] is blank.
     * @throws CancellationException when this owner/binding is closed or the caller stops waiting.
     * @throws IllegalStateException when the edited baseline changed.
     * @throws Exception when the settings write fails.
     */
    public suspend fun renameThread(threadName: String): Unit

    /**
     * Opens an Agent-owned confirmation for an exclusive storage boundary.
     *
     * @throws CancellationException when this owner/binding is closed.
     * @throws IllegalStateException when running/history state forbids editing or request ids are exhausted.
     * @throws CacheNonceMismatch when [expectedGeneration] is stale.
     * @throws IllegalArgumentException when [untilExclusive] fails to preserve initialization or exceeds latest + 1.
     */
    public fun requestHistoryRevert(untilExclusive: Int, expectedGeneration: Long): Long

    /**
     * Removes records at or after [untilExclusive], without requesting confirmation.
     * The boundary must preserve initialization (index zero), but need not name a history row.
     * Rejects a stale [expectedGeneration], an unavailable Agent, or an out-of-range boundary.
     *
     * Returns on success and throws on failure. An accepted operation belongs to this Agent's
     * lifetime, so cancelling the caller stops waiting without cancelling the owned revert.
     *
     * @throws CancellationException when this owner/binding is closed or the caller stops waiting.
     * @throws CacheNonceMismatch when [expectedGeneration] no longer matches the stored history.
     * @throws Exception when backend boundary/generation/admission validation or the revert fails.
     */
    public suspend fun revertHistory(untilExclusive: Int, expectedGeneration: Long): Unit

    /** Dismisses only the matching pending request; a stale request id does nothing. */
    public fun dismissHistoryRevert(requestId: Long): Unit

    /**
     * Accepts the exact still-pending revert request.
     *
     * The accepted operation executes in this ViewModel's lifetime and this
     * command returns after that ownership transfer. A missing/stale request is rejected
     * without consuming a different request; rejection and ordinary execution failures
     * use [notification]. A closed owner does not start another operation.
     */
    public fun confirmHistoryRevert(requestId: Long): Unit

    /** Dismisses only the matching notification, never a later result. */
    public fun dismissNotification(notificationId: Long): Unit

    /**
     * Idempotently stops local observations/waits and closes owned children.
     * Does not issue Stop, close the shared RPC client or release the backend/Home owner.
     */
    override fun close(): Unit
}
