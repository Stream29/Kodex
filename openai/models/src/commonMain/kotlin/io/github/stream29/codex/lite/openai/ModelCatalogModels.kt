package io.github.stream29.codex.lite.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
public data class ModelsResponse(
    public val models: List<ModelInfo> = emptyList(),
)

/**
 * Model metadata returned by the Codex backend `/models` endpoint.
 *
 * @property slug Stable backend model identifier.
 * @property displayName Human-readable model name.
 * @property defaultReasoningLevel Nullable because legacy or provider-defined
 * model metadata may not advertise a default; `null` means the catalog has no
 * suggested reasoning effort.
 * @property supportedReasoningLevels Ordered reasoning efforts advertised by
 * the backend. An empty list means the backend did not expose discrete choices
 * for this model.
 * @property contextWindow Nullable because older or provider-defined models may
 * omit their nominal context window; `null` means only
 * [maxContextWindow] may describe the available window.
 * @property maxContextWindow Nullable because a provider may not advertise a
 * maximum context window; `null` means [contextWindow] is the only known
 * window value.
 * @property autoCompactionTokenLimit Nullable because the backend may leave
 * the compaction threshold to the client default; `null` means use 90% of the
 * resolved context window when one is known.
 * @property effectiveContextWindowPercent Fraction of the resolved context
 * window usable for active conversation before the hard context limit.
 */
@Serializable
public data class ModelInfo(
    public val slug: OpenAiModelId,
    @SerialName("display_name")
    public val displayName: String,
    @SerialName("default_reasoning_level")
    public val defaultReasoningLevel: ReasoningEffort? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("supported_reasoning_levels")
    public val supportedReasoningLevels: List<ReasoningEffortPreset> = emptyList(),
    @SerialName("context_window")
    public val contextWindow: Long? = null,
    @SerialName("max_context_window")
    public val maxContextWindow: Long? = null,
    @SerialName("auto_compact_token_limit")
    public val autoCompactionTokenLimit: Long? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("effective_context_window_percent")
    public val effectiveContextWindowPercent: Long = DefaultEffectiveContextWindowPercent,
)

/** One server-advertised reasoning effort and its UI-facing description. */
@Serializable
public data class ReasoningEffortPreset(
    public val effort: ReasoningEffort,
    public val description: String,
)

/** Matches Codex's default effective fraction for model context windows. */
public const val DefaultEffectiveContextWindowPercent: Long = 95L

/**
 * Context-window accounting for one model snapshot.
 *
 * @property activeContextTokens Latest provider-reported token count for the
 * active conversation context.
 * @property autoCompactionTokenLimit Nullable because no automatic threshold
 * can be derived when both model metadata and the caller omit a context limit;
 * `null` means auto-compaction is not bounded by this component.
 * @property effectiveContextWindow Nullable because model metadata may omit
 * both context-window fields; `null` means the hard active-context boundary is
 * unavailable.
 * @property tokensUntilCompaction Nullable because neither a compaction
 * threshold nor an effective context-window boundary may be known; `null`
 * means remaining context cannot be calculated.
 */
public data class ModelContextWindowTokenStatus(
    public val activeContextTokens: Long,
    public val autoCompactionTokenLimit: Long?,
    public val effectiveContextWindow: Long?,
    public val tokensUntilCompaction: Long?,
)

/** Returns the model's nominal context window, preferring its explicit value. */
public fun ModelInfo.resolvedContextWindow(): Long? =
    contextWindow ?: maxContextWindow

/** Returns the context window available to the active conversation. */
public fun ModelInfo.effectiveContextWindow(): Long? =
    resolvedContextWindow()?.let { window ->
        window * effectiveContextWindowPercent / 100L
    }

/**
 * Resolves the auto-compaction threshold using Codex's 90%-of-context cap.
 *
 * @param configuredLimit Nullable because an agent may not override the model
 * policy; `null` means use [ModelInfo.autoCompactionTokenLimit], then the 90%
 * default when a context window is known.
 */
public fun ModelInfo.resolvedAutoCompactionTokenLimit(configuredLimit: Long?): Long? {
    val contextBound = resolvedContextWindow()?.let { it * 9L / 10L }
    val requestedLimit = configuredLimit ?: autoCompactionTokenLimit
    return when {
        contextBound == null -> requestedLimit
        requestedLimit == null -> contextBound
        else -> minOf(requestedLimit, contextBound)
    }
}

/**
 * Calculates the remaining token budget before a runtime should compact.
 *
 * The result is the smaller remaining amount from the auto-compaction policy
 * and the effective context window, matching Codex's total-context policy.
 */
public fun ModelInfo.contextWindowTokenStatus(
    activeContextTokens: Long,
    configuredAutoCompactionTokenLimit: Long?,
): ModelContextWindowTokenStatus {
    val autoCompactionLimit = resolvedAutoCompactionTokenLimit(configuredAutoCompactionTokenLimit)
    val contextWindow = effectiveContextWindow()
    val autoRemaining = autoCompactionLimit?.let { limit ->
        (limit - activeContextTokens).coerceAtLeast(0L)
    }
    val contextRemaining = contextWindow?.let { limit ->
        (limit - activeContextTokens).coerceAtLeast(0L)
    }
    return ModelContextWindowTokenStatus(
        activeContextTokens = activeContextTokens,
        autoCompactionTokenLimit = autoCompactionLimit,
        effectiveContextWindow = contextWindow,
        tokensUntilCompaction = when {
            autoRemaining == null -> contextRemaining
            contextRemaining == null -> autoRemaining
            else -> minOf(autoRemaining, contextRemaining)
        },
    )
}
