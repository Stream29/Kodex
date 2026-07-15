package io.github.stream29.codex.lite.openai

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Agent-thread settings visible at an agent state index.
 *
 * This type intentionally excludes request input; AgentState reconstructs it
 * from persisted history and the active [CompactionCheckpoint], then projects
 * the request-facing fields into a [ResponsesApiRequest].
 *
 * @property model Model identifier used for the next Responses API request.
 * @property autoCompactionTokenLimit Nullable because token-count-triggered
 * auto-compaction can be disabled; `null` means AgentRuntime will not trigger
 * compaction from token count alone.
 * @property turnId UUIDv7 identity allocated for the active or next logical
 * Codex turn. AgentState rotates it only when it begins a new logical turn.
 * @property collaborationMode Active collaboration behavior. It is independent
 * of the task checklist and current goal.
 * @property plan Full replacement `update_plan` snapshot. An empty plan means
 * the thread has no active checklist steps.
 * @property goal Nullable because a thread may not have a current goal; `null`
 * means no goal has been created or retained for this thread.
 * @property installationId Nullable because Codex identity metadata is
 * optional; `null` means no installation id is sent.
 * @property sessionId Nullable because Codex identity metadata is optional;
 * `null` means no session id is sent.
 * @property previousResponseId Nullable because a request may be built from
 * full local history instead of a provider-side response chain; `null` means no
 * provider response id is referenced.
 * @property promptCacheKey Nullable because prompt-cache affinity is optional;
 * `null` means no explicit prompt cache key is stored.
 */
@OptIn(ExperimentalUuidApi::class)
public data class CodexAgentSettings(
    public val model: OpenAiModelId,
    public val autoCompactionTokenLimit: Long? = null,
    public val turnId: String = Uuid.generateV7().toString(),
    public val collaborationMode: ModeKind = ModeKind.Default,
    public val plan: UpdatePlanArgs = UpdatePlanArgs(plan = emptyList()),
    public val goal: ThreadGoal? = null,
    public val installationId: String? = null,
    public val sessionId: String? = null,
    public val instructions: String = "",
    public val previousResponseId: String? = null,
    public val tools: List<ToolSpec> = emptyList(),
    public val toolChoice: ToolChoice = ToolChoice.Auto,
    public val parallelToolCalls: Boolean = false,
    public val reasoning: Reasoning = Reasoning(),
    public val include: Set<ResponseInclude> = emptySet(),
    public val serviceTier: ServiceTier = ServiceTier.Default,
    public val promptCacheKey: String? = null,
    public val text: TextControls = TextControls(),
    public val clientMetadata: Map<String, String> = emptyMap(),
)

/**
 * Checkpoint that defines the compacted model-visible prefix active at an
 * agent state index.
 *
 * @property prefix Replacement model-visible base history. This should contain
 * the compaction summary and any retained or re-injected context needed for the
 * next model request.
 * @property historyBaseIndex First history state index not covered by [prefix].
 * Raw history items before this index remain stored for audit/forking, but are
 * excluded from the active prompt projection while this checkpoint is visible.
 * @property windowNumber Monotonic compaction-window sequence number.
 * @property firstWindowId Stable UUIDv7 identifier for the thread's first
 * context window.
 * @property previousWindowId Nullable because the first context window has no
 * predecessor; `null` means this checkpoint belongs to that first window.
 * @property windowId Stable UUIDv7 identifier for the active context window.
 */
public data class CompactionCheckpoint(
    public val prefix: List<ResponseItem.HistoryItem>,
    public val historyBaseIndex: Int,
    public val windowNumber: Long,
    public val firstWindowId: String,
    public val previousWindowId: String? = null,
    public val windowId: String,
)

/**
 * Returns the Codex wire window identity for this checkpoint.
 *
 * [threadId] is owned by the agent storage that represents the Codex thread;
 * [windowNumber] identifies this checkpoint within that thread.
 */
public fun CompactionCheckpoint.codexRequestWindowId(threadId: String): String =
    "$threadId:$windowNumber"

/**
 * Result of a remote compaction v2 Responses stream.
 */
public data class RemoteCompactionV2Response(
    public val compactionOutput: ResponseItem.Compaction,
    public val completedResponse: Response?,
)

public enum class RemoteCompactionV2Trigger(public val wireName: String) {
    Auto("auto"),
    Manual("manual"),
}

public enum class RemoteCompactionV2Reason(public val wireName: String) {
    UserRequested("user_requested"),
    ContextLimit("context_limit"),
}

public enum class RemoteCompactionV2Phase(public val wireName: String) {
    StandaloneTurn("standalone_turn"),
    PreTurn("pre_turn"),
    MidTurn("mid_turn"),
}
