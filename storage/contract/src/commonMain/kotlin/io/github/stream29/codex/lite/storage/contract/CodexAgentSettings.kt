package io.github.stream29.codex.lite.storage.contract

import io.github.stream29.codex.lite.openai.Reasoning
import io.github.stream29.codex.lite.openai.ResponseInclude
import io.github.stream29.codex.lite.openai.TextControls
import io.github.stream29.codex.lite.openai.ToolChoice
import io.github.stream29.codex.lite.openai.ToolSpec

/**
 * Model request settings visible at an agent-storage index.
 *
 * This type intentionally excludes request input; input is reconstructed from
 * [CodexAgentRawDataStorage.history] and the active [CompactionCheckpoint].
 * Store it in [CodexAgentRawDataStorage.settings] as a sparse timeline so
 * settings changes can become visible at the same history boundary as the
 * model/tool event that publishes them.
 *
 * @property model Model identifier used for the next Responses API request.
 * @property instructions Nullable because instructions are optional; `null`
 * means no per-request instruction override is stored.
 * @property store Whether the provider should store the created response.
 * @property previousResponseId Nullable because a request may be built from
 * full local history instead of a provider-side response chain; `null` means no
 * provider response id is referenced.
 * @property tools Model-visible tool specs active at this storage index.
 * @property toolChoice Tool-choice policy active at this storage index.
 * @property parallelToolCalls Whether the model may request parallel tool
 * calls.
 * @property reasoning Nullable because reasoning controls are optional; `null`
 * means use provider or model defaults.
 * @property include Additional response fields requested from the provider.
 * @property serviceTier Nullable because service tier selection is optional;
 * `null` means use the provider default.
 * @property promptCacheKey Nullable because prompt-cache affinity is optional;
 * `null` means no explicit prompt cache key is stored.
 * @property text Nullable because text controls are optional; `null` means use
 * provider or model defaults for text output.
 * @property clientMetadata Client metadata attached to provider requests.
 */
public data class CodexAgentSettings(
    public val model: String,
    public val instructions: String? = null,
    public val store: Boolean = false,
    public val previousResponseId: String? = null,
    public val tools: List<ToolSpec> = emptyList(),
    public val toolChoice: ToolChoice = ToolChoice.Auto,
    public val parallelToolCalls: Boolean = false,
    public val reasoning: Reasoning? = null,
    public val include: Set<ResponseInclude> = emptySet(),
    public val serviceTier: String? = null,
    public val promptCacheKey: String? = null,
    public val text: TextControls? = null,
    public val clientMetadata: Map<String, String> = emptyMap(),
)
