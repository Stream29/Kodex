package io.github.stream29.kodex.agentstate.contextwindow

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.openai.ModelContextWindowTokenStatus
import io.github.stream29.kodex.openai.contextWindowTokenStatus
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog

/**
 * Calculates the current model-context status from one storage snapshot.
 *
 * @return Nullable because OpenAI may not yet have reported a token count for
 * the active context; `null` means remaining context cannot be calculated.
 */
public suspend fun KodexAgentState.contextWindowTokenStatus(
    modelCatalog: OpenAiModelCatalog,
): ModelContextWindowTokenStatus? {
    val snapshotIndex = storage.latestIndex()
    if (snapshotIndex < 0 || storage.tokenCount.latestIndex() < 0) {
        return null
    }
    val settings = storage.settings[snapshotIndex]
    val activeContextTokens = storage.tokenCount[snapshotIndex]
    return modelCatalog.resolve(settings.model).contextWindowTokenStatus(
        activeContextTokens = activeContextTokens,
        configuredAutoCompactionTokenLimit = settings.autoCompactionTokenLimit,
    )
}

/**
 * Returns the remaining token budget before automatic compaction.
 *
 * @return Nullable because [contextWindowTokenStatus] cannot run until the
 * provider has reported the active context token count; `null` means the
 * budget is unknown.
 */
public suspend fun KodexAgentState.tokensUntilCompaction(
    modelCatalog: OpenAiModelCatalog,
): Long? =
    contextWindowTokenStatus(modelCatalog)?.tokensUntilCompaction
