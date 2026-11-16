package io.github.stream29.kodex.agentstate.contextwindow

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.openai.ModelContextWindowTokenStatus
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalogStore
import kotlinx.coroutines.CancellationException

/**
 * Calculates the current model-context status from one storage snapshot.
 *
 * @return Nullable because storage may not yet contain a token count for the
 * active context; `null` means remaining context cannot be calculated. A
 * successful compaction contributes its synthetic `0` reset as a usable count.
 * Storage failures propagate; this does not lock or mutate the State.
 *
 * @throws CancellationException when a storage read is cancelled.
 */
public expect suspend fun KodexAgentState.contextWindowTokenStatus(
    modelCatalog: OpenAiModelCatalogStore,
): ModelContextWindowTokenStatus?

/**
 * Returns the remaining token budget before automatic compaction.
 *
 * @return Nullable because [contextWindowTokenStatus] cannot run until storage
 * contains an active context token count; `null` means the budget is unknown.
 * Storage failures propagate.
 *
 * @throws CancellationException when a storage read is cancelled.
 */
public expect suspend fun KodexAgentState.tokensUntilCompaction(
    modelCatalog: OpenAiModelCatalogStore,
): Long?
