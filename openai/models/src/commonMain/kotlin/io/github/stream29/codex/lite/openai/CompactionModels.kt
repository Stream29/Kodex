package io.github.stream29.codex.lite.openai

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * @property instructions Compaction instructions. The empty default is omitted
 * from the wire.
 * @property reasoning Reasoning controls. The default value is omitted from
 * the wire.
 * @property serviceTier Service tier selection. [ServiceTier.Default] is
 * omitted from the wire.
 * @property promptCacheKey Nullable because prompt cache affinity is optional;
 * `null` means no cache key is sent.
 * @property text Text controls. The default value is omitted from the wire.
 */
@Serializable
public data class CompactionInput(
    public val model: OpenAiModelId,
    public val input: List<ResponseItem>,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val instructions: String = "",
    public val tools: List<ToolSpec> = emptyList(),
    @SerialName("parallel_tool_calls")
    public val parallelToolCalls: Boolean = false,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val reasoning: Reasoning = Reasoning(),
    @SerialName("service_tier")
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val serviceTier: ServiceTier = ServiceTier.Default,
    @SerialName("prompt_cache_key")
    public val promptCacheKey: String? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val text: TextControls = TextControls(),
)

@Serializable
public data class CompactionResponse(
    public val output: List<ResponseItem.HistoryItem> = emptyList(),
)

/**
 * Model request settings visible at an agent state index.
 *
 * This type intentionally excludes request input; input is reconstructed from
 * persisted history and the active [CompactionCheckpoint].
 *
 * @property model Model identifier used for the next Responses API request.
 * @property autoCompactionTokenLimit Nullable because token-count-triggered
 * auto-compaction can be disabled; `null` means AgentState will not trigger
 * compaction from token count alone.
 * @property remoteCompactionV2 Whether compaction uses the Responses stream
 * with a trailing `compaction_trigger`.
 * @property installationId Nullable because Codex identity metadata is
 * optional; `null` means no installation id is sent.
 * @property sessionId Nullable because Codex identity metadata is optional;
 * `null` means no session id is sent.
 * @property threadId Nullable because Codex identity metadata is optional;
 * `null` means no thread id is sent.
 * @property previousResponseId Nullable because a request may be built from
 * full local history instead of a provider-side response chain; `null` means no
 * provider response id is referenced.
 * @property promptCacheKey Nullable because prompt-cache affinity is optional;
 * `null` means no explicit prompt cache key is stored.
 */
public data class CodexAgentSettings(
    public val model: OpenAiModelId,
    public val autoCompactionTokenLimit: Long? = null,
    public val remoteCompactionV2: Boolean = true,
    public val installationId: String? = null,
    public val sessionId: String? = null,
    public val threadId: String? = null,
    public val instructions: String = "",
    public val store: Boolean = false,
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
 * Typed input for the Codex remote compaction v2 Responses flow.
 *
 * The OpenAI client owns the wire projection: it appends the
 * `compaction_trigger`, injects remote-compaction client metadata, and sends
 * the required `x-codex-*` headers.
 *
 * @property history Active model-visible history before the client appends the
 * remote-compaction trigger item.
 */
public data class RemoteCompactionV2Request(
    public val history: List<ResponseItem>,
    public val checkpoint: CompactionCheckpoint,
    public val settings: CodexAgentSettings,
    public val turnId: String,
    public val trigger: RemoteCompactionV2Trigger,
    public val reason: RemoteCompactionV2Reason,
    public val phase: RemoteCompactionV2Phase,
)

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
