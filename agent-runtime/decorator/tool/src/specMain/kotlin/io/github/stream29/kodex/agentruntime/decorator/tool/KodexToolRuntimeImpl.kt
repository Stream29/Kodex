@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentruntime.decorator.tool

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
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
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.toolsearch.ToolSearchEngine
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/**
 * Default [KodexToolRuntime] over borrowed tool catalogs.
 */
public expect class KodexToolRuntimeImpl : KodexToolRuntime {
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
 * Adds a tool-execution layer over externally owned tool state.
 *
 * This runtime neither creates nor closes any supplied tool or state flow.
 *
 * @param logger Agent-scoped logger used to derive each Tool call logger.
 */
public expect fun ResumableAgentLayer.toolRuntime(
    fixedTools: List<Tool>,
    dynamicTools: StateFlow<List<Tool>>,
    toolSearch: StateFlow<ToolSearchEngine>,
    logger: KLogger,
): KodexToolRuntime
