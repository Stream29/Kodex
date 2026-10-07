package io.github.stream29.kodex.openai.modelcatalog

import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelServiceTier
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ReasoningEffortPreset
import io.github.stream29.kodex.openai.ServiceTier

private val standardReasoningLevels: List<ReasoningEffortPreset> = listOf(
    ReasoningEffortPreset(ReasoningEffort.Low, "Fast responses with lighter reasoning"),
    ReasoningEffortPreset(ReasoningEffort.Medium, "Balances speed and reasoning depth for everyday tasks"),
    ReasoningEffortPreset(ReasoningEffort.High, "Greater reasoning depth for complex problems"),
    ReasoningEffortPreset(ReasoningEffort.XHigh, "Extra high reasoning depth for complex problems"),
)

private val maxReasoningLevels: List<ReasoningEffortPreset> = standardReasoningLevels +
    ReasoningEffortPreset(ReasoningEffort.Max, "Maximum reasoning depth for the hardest problems")

private val fastServiceTier: List<ModelServiceTier> = listOf(
    ModelServiceTier(
        id = ServiceTier.Fast.requestValue,
        name = "Fast",
        description = "1.5x speed",
    ),
)

private val fastIncreasedUsageServiceTier: List<ModelServiceTier> = listOf(
    ModelServiceTier(
        id = ServiceTier.Fast.requestValue,
        name = "Fast",
        description = "1.5x speed, increased usage",
    ),
)

private val fastTwoXServiceTier: List<ModelServiceTier> = listOf(
    ModelServiceTier(
        id = ServiceTier.Fast.requestValue,
        name = "Fast",
        description = "2x speed, increased usage",
    ),
)

/** Relevant model metadata mirrored from Codex's bundled `models.json`. */
internal val BuiltInModelCatalog: List<ModelInfo> = listOf(
    ModelInfo(
        slug = OpenAiModelId("gpt-6-astra"),
        displayName = "GPT-6-Astra",
        defaultReasoningLevel = ReasoningEffort.Low,
        supportedReasoningLevels = maxReasoningLevels,
        serviceTiers = fastTwoXServiceTier,
        contextWindow = 272_000L,
        maxContextWindow = 872_000L,
        compHash = "3000",
    ),
    ModelInfo(
        slug = OpenAiModelId("gpt-6.1-sol"),
        displayName = "GPT-6.1-Sol",
        defaultReasoningLevel = ReasoningEffort.Low,
        supportedReasoningLevels = maxReasoningLevels,
        serviceTiers = fastTwoXServiceTier,
        contextWindow = 272_000L,
        maxContextWindow = 872_000L,
        compHash = "3000",
    ),
    ModelInfo(
        slug = OpenAiModelId("gpt-6-sol"),
        displayName = "GPT-6-Sol",
        defaultReasoningLevel = ReasoningEffort.Medium,
        supportedReasoningLevels = maxReasoningLevels,
        serviceTiers = fastServiceTier,
        contextWindow = 272_000L,
        maxContextWindow = 872_000L,
        compHash = "3000",
    ),
    ModelInfo(
        slug = OpenAiModelId("gpt-6-luna"),
        displayName = "GPT-6-Luna",
        defaultReasoningLevel = ReasoningEffort.Medium,
        supportedReasoningLevels = maxReasoningLevels,
        serviceTiers = fastServiceTier,
        contextWindow = 272_000L,
        maxContextWindow = 872_000L,
        compHash = "3000",
    ),
    ModelInfo(
        slug = OpenAiModelId("gpt-5.6-sol"),
        displayName = "GPT-5.6-Sol",
        defaultReasoningLevel = ReasoningEffort.Low,
        supportedReasoningLevels = maxReasoningLevels,
        serviceTiers = fastIncreasedUsageServiceTier,
        contextWindow = 272_000L,
        maxContextWindow = 872_000L,
        compHash = "3000",
    ),
    ModelInfo(
        slug = OpenAiModelId("gpt-5.6-terra"),
        displayName = "GPT-5.6-Terra",
        defaultReasoningLevel = ReasoningEffort.Medium,
        supportedReasoningLevels = maxReasoningLevels,
        serviceTiers = fastIncreasedUsageServiceTier,
        contextWindow = 272_000L,
        maxContextWindow = 872_000L,
        compHash = "3000",
    ),
    ModelInfo(
        slug = OpenAiModelId("gpt-5.6-luna"),
        displayName = "GPT-5.6-Luna",
        defaultReasoningLevel = ReasoningEffort.Medium,
        supportedReasoningLevels = maxReasoningLevels,
        serviceTiers = fastIncreasedUsageServiceTier,
        contextWindow = 272_000L,
        maxContextWindow = 872_000L,
        compHash = "3000",
    ),
    ModelInfo(
        slug = OpenAiModelId("gpt-5.5"),
        displayName = "GPT-5.5",
        defaultReasoningLevel = ReasoningEffort.Medium,
        supportedReasoningLevels = standardReasoningLevels,
        serviceTiers = fastIncreasedUsageServiceTier,
        contextWindow = 272_000L,
        maxContextWindow = 272_000L,
        compHash = "2911",
    ),
    ModelInfo(
        slug = OpenAiModelId("codex-auto-review"),
        displayName = "Codex Auto Review",
        defaultReasoningLevel = ReasoningEffort.Medium,
        supportedReasoningLevels = maxReasoningLevels,
        serviceTiers = fastIncreasedUsageServiceTier,
        contextWindow = 272_000L,
        maxContextWindow = 872_000L,
        compHash = "3000",
    ),
)
