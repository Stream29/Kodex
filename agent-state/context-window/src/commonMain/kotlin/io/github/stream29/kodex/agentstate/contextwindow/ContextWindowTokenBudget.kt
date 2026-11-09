package io.github.stream29.kodex.agentstate.contextwindow

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.agentstorage.contract.getLlmRequestMetaAtOrNull
import io.github.stream29.kodex.openai.ModelContextWindowTokenStatus
import io.github.stream29.kodex.openai.contextWindowTokenStatus
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalogStore

public actual suspend fun KodexAgentState.contextWindowTokenStatus(
    modelCatalog: OpenAiModelCatalogStore,
): ModelContextWindowTokenStatus? {
    val snapshotIndex = storage.latestIndex()
    if (snapshotIndex < 0) {
        return null
    }
    val tokenCount = storage.getLlmRequestMetaAtOrNull(snapshotIndex) ?: return null
    val settings = storage.getSettingsAt(snapshotIndex)
    val activeContextTokens = tokenCount.totalTokens
    return modelCatalog.resolve(settings.model).contextWindowTokenStatus(
        activeContextTokens = activeContextTokens,
        configuredAutoCompactionTokenLimit = settings.autoCompactionTokenLimit,
    )
}

public actual suspend fun KodexAgentState.tokensUntilCompaction(
    modelCatalog: OpenAiModelCatalogStore,
): Long? =
    contextWindowTokenStatus(modelCatalog)?.tokensUntilCompaction
