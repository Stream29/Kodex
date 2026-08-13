package io.github.stream29.kodex.app.agent.contract

import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModeKind
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/** Outcome of consuming one exact composer revision. */
public enum class AgentComposerSubmissionResult {
    Submitted,
    QueuedAsSteer,
    Empty,
    Stale,
    Unavailable,
}

/**
 * Direct settings contract implemented by every stable settings owner.
 *
 * Field-specific commands must transform the latest complete snapshot so a
 * frontend cannot overwrite unrelated runtime-owned settings with an old copy.
 */
public interface AgentSettingsViewModel {
    public val settings: StateFlow<KodexAgentSettings>

    public suspend fun updateModel(model: OpenAiModelId): Unit

    public suspend fun updateWorkingDirectory(workingDirectory: Path): Unit

    public suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort): Unit

    public suspend fun updateServiceTier(serviceTier: ServiceTier): Unit

    public suspend fun updateMode(mode: ModeKind): Unit
}

/**
 * Frontend contract for one materialized Agent.
 *
 * Stable identity and materialized child handles are direct properties.
 * Mutable Agent-owned state remains on this ViewModel.
 */
public interface AgentViewModel :
    AgentSettingsViewModel,
    AutoCloseable {
    public val address: AgentAddress
    public val parentAddress: AgentAddress?

    public val composer: ComposerViewModel
    public val history: AgentHistoryViewModel
    public val requestUserInput: RequestUserInputViewModel
    public val shellSessions: AgentShellSessionRegistry

    public val execution: StateFlow<AgentExecutionState>
    public val tokenCount: StateFlow<Long?>
    public val stream: StateFlow<AgentStreamState>
    public val directChildren: StateFlow<AgentChildrenState>
    public val historyAction: StateFlow<AgentHistoryActionState>
    public val notification: StateFlow<AgentNotification?>
    public val lifecycle: StateFlow<AgentLifecycleState>

    /** Submits content to this exact Agent address. */
    public suspend fun submit(content: List<ContentItem>): Unit

    /**
     * Consumes and submits only [expectedRevision] from this Agent's composer.
     */
    public suspend fun submitComposer(
        expectedRevision: Long,
    ): AgentComposerSubmissionResult

    public suspend fun resume(): Unit

    public fun cancel(): Unit

    public suspend fun clearPending(): Unit

    public suspend fun forceCompact(): Unit

    /** Updates only the thread name on the latest persisted settings snapshot. */
    public suspend fun renameThread(threadName: String): Unit

    /** Loads or refreshes only this Agent's direct child slots. */
    public suspend fun loadDirectChildren(): Unit

    /** Opens an Agent-owned confirmation and returns its request id. */
    public fun requestHistoryRevert(target: AgentHistoryTarget): Long

    public fun dismissHistoryRevert(requestId: Long): Unit

    /** Executes the exact still-pending revert request. */
    public suspend fun confirmHistoryRevert(requestId: Long): Unit

    public fun dismissNotification(notificationId: Long): Unit

    override fun close(): Unit
}
