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
import kotlinx.io.files.Path

/**
 * Direct settings contract implemented by every stable settings owner.
 *
 * Field-specific commands must transform the latest complete snapshot so a
 * frontend cannot overwrite unrelated runtime-owned settings with an old copy.
 */
public interface AgentSettingsViewModel {
    public val settings: StateFlow<KodexAgentSettings>
    public val models: StateFlow<List<ModelInfo>>

    public suspend fun updateModel(model: OpenAiModelId): Unit

    public suspend fun updateWorkingDirectory(workingDirectory: Path): Unit

    public suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort): Unit

    public suspend fun updateServiceTier(serviceTier: ServiceTier): Unit

    public suspend fun updateRequestUserInputMode(mode: RequestUserInputMode): Unit

    /** Atomically updates the three fields selected by the runtime model menu. */
    public suspend fun updateModelConfiguration(
        model: OpenAiModelId,
        reasoningEffort: ReasoningEffort,
        serviceTier: ServiceTier,
    ): Unit
}

/**
 * Frontend contract for one materialized Agent.
 *
 * Stable identity and mutable Agent-owned state are direct properties.
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

    /** Submits content to this exact Agent address. */
    public suspend fun submit(content: List<ContentItem>): Unit

    /**
     * Starts waiting for a backend-owned continuation; closing this view only cancels the wait.
     */
    public fun resume(): Unit

    public fun cancel(): Unit

    public fun clearPending(): Unit

    /** Starts waiting for backend-owned compaction. */
    public fun forceCompact(): Unit

    /** Updates only the thread name on the latest persisted settings snapshot. */
    public suspend fun renameThread(threadName: String): Unit

    /** Opens an Agent-owned confirmation for an exclusive storage boundary. */
    public fun requestHistoryRevert(untilExclusive: Int, expectedGeneration: Long): Long

    /**
     * Removes records at or after [untilExclusive], without requesting confirmation.
     * The boundary must preserve initialization (index zero), but need not name a history row.
     * Rejects a stale [expectedGeneration], an unavailable Agent, or an out-of-range boundary.
     *
     * Returns on success and throws on failure. An accepted operation belongs to this Agent's
     * lifetime, so cancelling the caller stops waiting without cancelling the owned revert.
     */
    public suspend fun revertHistory(untilExclusive: Int, expectedGeneration: Long): Unit

    public fun dismissHistoryRevert(requestId: Long): Unit

    /**
     * Accepts the exact still-pending revert request.
     *
     * The accepted operation executes in this ViewModel's lifetime and this
     * command returns after that ownership transfer.
     */
    public fun confirmHistoryRevert(requestId: Long): Unit

    public fun dismissNotification(notificationId: Long): Unit

    override fun close(): Unit
}
