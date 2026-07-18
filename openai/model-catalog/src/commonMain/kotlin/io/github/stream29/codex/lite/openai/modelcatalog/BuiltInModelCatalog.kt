package io.github.stream29.codex.lite.openai.modelcatalog

import io.github.stream29.codex.lite.openai.ModelInfo
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.ReasoningEffort
import io.github.stream29.codex.lite.openai.ReasoningEffortPreset

private val standardReasoningLevels: List<ReasoningEffortPreset> = listOf(
    ReasoningEffortPreset(ReasoningEffort.Low, "Fast responses with lighter reasoning"),
    ReasoningEffortPreset(ReasoningEffort.Medium, "Balances speed and reasoning depth for everyday tasks"),
    ReasoningEffortPreset(ReasoningEffort.High, "Greater reasoning depth for complex problems"),
    ReasoningEffortPreset(ReasoningEffort.XHigh, "Extra high reasoning depth for complex problems"),
)

private val maxReasoningLevels: List<ReasoningEffortPreset> = standardReasoningLevels +
    ReasoningEffortPreset(ReasoningEffort.Max, "Maximum reasoning depth for the hardest problems")

private val ultraReasoningLevels: List<ReasoningEffortPreset> = maxReasoningLevels +
    ReasoningEffortPreset(ReasoningEffort.Ultra, "Maximum reasoning with automatic task delegation")

private val gpt52ReasoningLevels: List<ReasoningEffortPreset> = listOf(
    ReasoningEffortPreset(
        ReasoningEffort.Low,
        "Balances speed with some reasoning; useful for straightforward queries and short explanations",
    ),
    ReasoningEffortPreset(
        ReasoningEffort.Medium,
        "Provides a solid balance of reasoning depth and latency for general-purpose tasks",
    ),
    ReasoningEffortPreset(
        ReasoningEffort.High,
        "Maximizes reasoning depth for complex or ambiguous problems",
    ),
    ReasoningEffortPreset(ReasoningEffort.XHigh, "Extra high reasoning for complex problems"),
)

/** Relevant model metadata mirrored from Codex's bundled `models.json`. */
internal val BuiltInModelCatalog: List<ModelInfo> = listOf(
    ModelInfo(
        slug = OpenAiModelId("gpt-5.6-sol"),
        displayName = "GPT-5.6-Sol",
        defaultReasoningLevel = ReasoningEffort.Low,
        supportedReasoningLevels = ultraReasoningLevels,
        contextWindow = 372_000L,
        maxContextWindow = 372_000L,
    ),
    ModelInfo(
        slug = OpenAiModelId("gpt-5.6-terra"),
        displayName = "GPT-5.6-Terra",
        defaultReasoningLevel = ReasoningEffort.Medium,
        supportedReasoningLevels = ultraReasoningLevels,
        contextWindow = 372_000L,
        maxContextWindow = 372_000L,
    ),
    ModelInfo(
        slug = OpenAiModelId("gpt-5.6-luna"),
        displayName = "GPT-5.6-Luna",
        defaultReasoningLevel = ReasoningEffort.Medium,
        supportedReasoningLevels = maxReasoningLevels,
        contextWindow = 372_000L,
        maxContextWindow = 372_000L,
    ),
    ModelInfo(
        slug = OpenAiModelId("gpt-5.5"),
        displayName = "GPT-5.5",
        defaultReasoningLevel = ReasoningEffort.Medium,
        supportedReasoningLevels = standardReasoningLevels,
        contextWindow = 272_000L,
        maxContextWindow = 272_000L,
    ),
    ModelInfo(
        slug = OpenAiModelId("gpt-5.4"),
        displayName = "GPT-5.4",
        defaultReasoningLevel = ReasoningEffort.Medium,
        supportedReasoningLevels = standardReasoningLevels,
        contextWindow = 272_000L,
        maxContextWindow = 1_000_000L,
    ),
    ModelInfo(
        slug = OpenAiModelId("gpt-5.4-mini"),
        displayName = "GPT-5.4-Mini",
        defaultReasoningLevel = ReasoningEffort.Medium,
        supportedReasoningLevels = standardReasoningLevels,
        contextWindow = 272_000L,
        maxContextWindow = 272_000L,
    ),
    ModelInfo(
        slug = OpenAiModelId("gpt-5.2"),
        displayName = "GPT-5.2",
        defaultReasoningLevel = ReasoningEffort.Medium,
        supportedReasoningLevels = gpt52ReasoningLevels,
        contextWindow = 272_000L,
        maxContextWindow = 272_000L,
    ),
    ModelInfo(
        slug = OpenAiModelId("codex-auto-review"),
        displayName = "Codex Auto Review",
        defaultReasoningLevel = ReasoningEffort.Medium,
        supportedReasoningLevels = standardReasoningLevels,
        contextWindow = 272_000L,
        maxContextWindow = 1_000_000L,
    ),
)
