package io.github.stream29.codex.lite.openai.modelcatalog

import io.github.stream29.codex.lite.openai.ModelInfo
import io.github.stream29.codex.lite.openai.OpenAiModelId
import kotlinx.coroutines.flow.StateFlow

/** Observable OpenAI model metadata used by Agent composition and configuration. */
public interface OpenAiModelCatalog : AutoCloseable {
    /** Latest model catalog snapshot in provider order. */
    public val models: StateFlow<List<ModelInfo>>

    /** Refreshes [models] from the configured provider. */
    public suspend fun refresh(): List<ModelInfo>

    /** Resolves metadata for [model], including implementation-defined fallback behavior. */
    public fun resolve(model: OpenAiModelId): ModelInfo
}
