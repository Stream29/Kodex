@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentruntime.decorator.compact

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentruntime.steer.SteerProvider
import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.RequestFinish
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalogStore
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/**
 * Default [KodexAgentCompactionRuntime] backed by the supplied Agent state.
 *
 * @param logger Agent-scoped logger for response and compaction operations.
 */
public expect class KodexAgentCompactionRuntimeImpl(
    delegate: KodexAgentState,
    modelCatalog: OpenAiModelCatalogStore,
    logger: KLogger,
    steerProvider: SteerProvider,
) : KodexAgentCompactionRuntime {
    override val coroutineContext: CoroutineContext
    override val state: StateFlow<KodexAgentStateValue>
    override val latestIndex: StateFlow<Int>
    override val storage: KodexAgentStorage
    override suspend fun <T> modify(block: suspend (MutableKodexAgentStorage) -> T): T
    override suspend fun requestResponseApi(): RequestFinish
    override suspend fun compact(trigger: CompactionTrigger, reason: CompactionReason, phase: CompactionPhase): Int
    override suspend fun injectHistory(events: List<StableIndexEvent.Steerable>): Int
    override suspend fun appendUserMessage(content: List<ContentItem>): Int
    override suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int
    override suspend fun updateSettings(settings: KodexAgentSettings): Int
    override suspend fun compareAndSetSettings(expect: KodexAgentSettings, update: KodexAgentSettings): Boolean
    override suspend fun resume()
}

/**
 * Adds automatic compaction, server-requested continuation, and bounded
 * response retry to this state.
 *
 * @param logger Agent-scoped logger for response and compaction operations.
 */
public expect fun KodexAgentState.compactionRuntime(
    modelCatalog: OpenAiModelCatalogStore,
    logger: KLogger,
    steerProvider: SteerProvider,
): KodexAgentCompactionRuntime
