package io.github.stream29.kodex.agentstorage.cleanmodels.stable

import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ResponseInclude
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.TextControls
import io.github.stream29.kodex.openai.ThreadGoal
import io.github.stream29.kodex.openai.ToolChoice
import io.github.stream29.kodex.openai.UpdatePlanArgs
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
 * the request-facing fields into a [io.github.stream29.kodex.openai.ResponsesApiRequest].
 */
@Serializable
@SerialName("settings")
public data class KodexAgentSettings(
    public val model: OpenAiModelId,
    /**
     * OpenAI turn identity used by requests built from this settings snapshot.
     *
     * An empty value is accepted only for legacy/uninitialized payloads; new
     * storage initialization supplies a generated identity.
     */
    public val turnId: String = "",
    /**
     * Opaque provider routing state for the current turn.
     *
     * `null` means no routing state has been received for this settings
     * snapshot; the value is never part of model input.
     */
    public val turnState: String? = null,
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
) : AgentCommit

/** Returns the provider-facing request-window identity for this snapshot. */
public fun KodexAgentSettings.codexRequestWindowId(threadId: String): String =
    "$threadId:$windowNumber"
