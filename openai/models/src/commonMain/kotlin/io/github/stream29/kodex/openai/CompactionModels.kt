package io.github.stream29.kodex.openai

import io.github.stream29.kodex.utils.kotlinxioserialization.PathAsStringSerializer
import kotlinx.io.files.Path
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Agent-thread settings visible at an agent state index.
 *
 * This type intentionally excludes request input; AgentState reconstructs it
 * from persisted clean history and the active compaction checkpoint, then projects
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
 * @property requestUserInputMode Whether future requests expose the structured
 * user-question tool. Changing it does not alter already-issued tool calls.
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
@Serializable
public data class KodexAgentSettings(
    public val model: OpenAiModelId,
    /**
     * OpenAI turn identity used by requests built from this settings snapshot.
     *
     * An empty value is accepted only for legacy/uninitialized payloads; new
     * storage initialization supplies a generated identity.
     */
    public val turnId: String = "",
    /** Monotonic provider context-window number for this settings snapshot. */
    public val windowNumber: Long = 0,
    /** First provider context-window id in this thread's lineage. */
    public val firstWindowId: String = "",
    /** Immediately preceding provider context-window id, if any. */
    public val previousWindowId: String? = null,
    /** Provider context-window id represented by this settings snapshot. */
    public val windowId: String = "",
    @Serializable(with = PathAsStringSerializer::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    public val cwd: Path = Path("."),
    public val threadName: String = "",
    public val autoCompactionTokenLimit: Long? = null,
    public val requestUserInputMode: RequestUserInputMode = RequestUserInputMode.AskUser,
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

/** Returns the provider-facing request-window identity for this snapshot. */
public fun KodexAgentSettings.codexRequestWindowId(threadId: String): String =
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
