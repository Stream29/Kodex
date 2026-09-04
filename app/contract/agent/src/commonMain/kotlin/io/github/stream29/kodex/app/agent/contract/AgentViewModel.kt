package io.github.stream29.kodex.app.agent.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableIndexEvent
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestedSessionMeta
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
    public val requestUserInput: RequestUserInputViewModel
    public val suggestSubagentTask: SuggestSubagentTaskViewModel
    public val shellSessions: AgentShellSessionRegistry

    public val execution: StateFlow<AgentExecutionState>
    public val tokenCount: StateFlow<Long?>
    public val pendingSteer: StateFlow<List<StableIndexEvent.Steerable>>
    public val historyAction: StateFlow<AgentHistoryActionState>
    public val notification: StateFlow<AgentNotification?>
    public val lifecycle: StateFlow<AgentLifecycleState>

    /** Submits content to this exact Agent address. */
    public suspend fun submit(content: List<ContentItem>): Unit

    /** Prevents automatic title generation for the next submitted turn. */
    public suspend fun suppressAutomaticTitle(): Unit

    /**
     * Consumes and submits only [expectedRevision] from this Agent's composer.
     */
    public suspend fun submitComposer(
        expectedRevision: Long,
    ): AgentComposerSubmissionResult

    /**
     * Starts continuation work in this ViewModel's lifetime and returns after
     * the owned operation has started.
     */
    public fun resume(): Unit

    public fun cancel(): Unit

    public fun clearPending(): Unit

    /** Starts compaction in this ViewModel's lifetime. */
    public fun forceCompact(): Unit

    /** Updates only the thread name on the latest persisted settings snapshot. */
    public suspend fun renameThread(threadName: String): Unit

    /** Opens an Agent-owned confirmation and returns its request id. */
    public fun requestHistoryRevert(target: AgentHistoryTarget): Long

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

/** Batch configuration selected by the user for suggested Sessions. */
public data class SuggestedSessionConfiguration(
    public val model: OpenAiModelId,
    public val reasoningEffort: ReasoningEffort,
    public val serviceTier: ServiceTier,
    public val cwd: Path,
    public val requestUserInputMode: RequestUserInputMode,
)

public sealed interface SuggestSubagentTaskState {
    public data object Idle : SuggestSubagentTaskState

    public data class Pending(
        public val callId: String,
        public val arguments: SuggestSubagentTaskArgs,
        public val configuration: SuggestedSessionConfiguration,
        public val feedback: String = "",
        public val revision: Long = 0,
        public val submitting: Boolean = false,
    ) : SuggestSubagentTaskState
}

public sealed interface SuggestSubagentTaskSubmissionResult {
    public data object Submitted : SuggestSubagentTaskSubmissionResult
    public data object Stale : SuggestSubagentTaskSubmissionResult
    public data object Busy : SuggestSubagentTaskSubmissionResult
    public data class Failed(public val message: String) : SuggestSubagentTaskSubmissionResult
}

/** Host-owned confirmation surface for `suggest_subagent_task`. */
public interface SuggestSubagentTaskViewModel : AutoCloseable {
    public val state: StateFlow<SuggestSubagentTaskState>

    public fun updateFeedback(callId: String, text: String): Boolean

    public fun updateConfiguration(
        callId: String,
        configuration: SuggestedSessionConfiguration,
    ): Boolean

    public suspend fun submit(
        callId: String,
        expectedRevision: Long,
        accepted: Boolean,
    ): SuggestSubagentTaskSubmissionResult

    override fun close(): Unit
}
