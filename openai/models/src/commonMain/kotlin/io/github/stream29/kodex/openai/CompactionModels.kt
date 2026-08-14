package io.github.stream29.kodex.openai

import io.github.stream29.kodex.utils.kotlinxioserialization.PathAsStringSerializer
import kotlinx.io.files.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
 * @property cwd Session working directory used to resolve project context and
 * relative paths for local tools. `Path(".")` is retained only as the
 * compatibility fallback for settings written before this field existed.
 * @property threadName Local user-facing thread title. Session repositories
 * ensure root snapshots use a non-empty title; an empty value is retained only
 * for legacy or independently initialized non-root Agent storage.
 * @property autoCompactionTokenLimit Nullable because a thread may defer its
 * threshold to the selected model metadata; `null` means use the catalog
 * policy, or 90% of the resolved model context window when no catalog-specific
 * threshold is present.
 * @property turnId UUIDv7 identity of the active logical user turn. Accepting
 * a new user message is the only operation that rotates it; compaction and
 * other state transitions retain the current value.
 * @property agentMode Whether this Agent may delegate work to subagents. It is
 * independent of the task checklist and current goal.
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
@Serializable
public data class KodexAgentSettings(
    public val model: OpenAiModelId,
    @Serializable(with = PathAsStringSerializer::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    public val cwd: Path = Path("."),
    public val threadName: String = "",
    public val autoCompactionTokenLimit: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    public val turnId: String = Uuid.generateV7().toString(),
    public val agentMode: AgentMode = AgentMode.Single,
    public val plan: UpdatePlanArgs = UpdatePlanArgs(plan = emptyList()),
    public val goal: ThreadGoal? = null,
    public val installationId: String? = null,
    public val sessionId: String? = null,
    public val instructions: String = "",
    public val previousResponseId: String? = null,
    public val toolChoice: ToolChoice = ToolChoice.Auto,
    public val parallelToolCalls: Boolean = false,
    public val reasoning: Reasoning = Reasoning(),
    public val include: Set<ResponseInclude> = emptySet(),
    public val serviceTier: ServiceTier = ServiceTier.Default,
    public val promptCacheKey: String? = null,
    public val text: TextControls = TextControls(),
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
@Serializable
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
 * [threadId] is the stable provider-facing projection of the backing agent
 * storage identity; [windowNumber] identifies this checkpoint within that
 * thread namespace.
 */
public fun CompactionCheckpoint.codexRequestWindowId(threadId: String): String =
    "$threadId:$windowNumber"

/**
 * Result of a remote compaction v2 Responses stream.
 *
 * @property compactionOutput Compaction item returned by the service.
 * @property completedResponse Nullable because a valid compaction stream may
 * end after the compaction item without a `response.completed` event; `null`
 * means no completed response was observed.
 */
public data class RemoteCompactionV2Response(
    public val compactionOutput: ResponseItem.Compaction,
    public val completedResponse: Response?,
)

@Serializable
public enum class CompactionTrigger {
    @SerialName("auto")
    Auto,

    @SerialName("manual")
    Manual,
}

@Serializable
public enum class CompactionReason {
    @SerialName("user_requested")
    UserRequested,

    @SerialName("context_limit")
    ContextLimit,

    @SerialName("model_downshift")
    ModelDownshift,

    @SerialName("comp_hash_changed")
    CompHashChanged,
}

@Serializable
public enum class CompactionPhase {
    @SerialName("standalone_turn")
    StandaloneTurn,

    @SerialName("pre_turn")
    PreTurn,

    @SerialName("mid_turn")
    MidTurn,
}
