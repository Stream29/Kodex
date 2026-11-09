@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentruntime.decorator.tool

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.RequestFinish
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.openai.CompactionPhase
import io.github.stream29.kodex.openai.CompactionReason
import io.github.stream29.kodex.openai.CompactionTrigger
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.toolsearch.ToolSearchEngine
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/**
 * [ResumableAgentLayer] that handles pending local tool and client tool-search calls.
 *
 * The supplied fixed tools, dynamic tool catalog, tool-search engine, and their
 * state flows are borrowed: this layer neither constructs nor closes them.
 * Fixed callable routes are established when the layer is composed; dynamic
 * routes are sampled when a [KodexAgentStateValue.ToolPending] boundary is
 * handled. Function, freeform, and namespace-member routes must be callable
 * and unique within and across the fixed and dynamic catalogs.
 *
 * @param logger Agent-scoped logger used to derive each Tool call logger.
 */
public expect class KodexToolRuntime(
    delegate: ResumableAgentLayer,
    fixedTools: List<Tool>,
    dynamicTools: StateFlow<List<Tool>>,
    toolSearch: StateFlow<ToolSearchEngine>,
    logger: KLogger,
) : ResumableAgentLayer {
    override val coroutineContext: CoroutineContext
    override val state: StateFlow<KodexAgentStateValue>
    override val latestIndex: StateFlow<Int>
    override val storage: KodexAgentStorage
    override suspend fun <T> modify(block: suspend (MutableKodexAgentStorage) -> T): T
    override suspend fun requestResponseApi(): RequestFinish
    override suspend fun compact(trigger: CompactionTrigger, reason: CompactionReason, phase: CompactionPhase): Int
    override suspend fun steer(events: List<StableIndexEvent.Steerable>): Int
    override suspend fun consumeSteer(): List<StableIndexEvent.Steerable>
    override suspend fun injectHistory(events: List<StableIndexEvent.Steerable>): Int
    override suspend fun appendUserMessage(content: List<ContentItem>): Int
    override suspend fun completeToolCall(completed: StableCleanEvent.CompletedTool): Int
    override suspend fun updateSettings(settings: KodexAgentSettings): Int
    override suspend fun compareAndSetSettings(expect: KodexAgentSettings, update: KodexAgentSettings): Boolean

    /**
     * Handles an existing pending-tool boundary before delegating to the inner
     * layer. Otherwise delegates first and handles any tool calls it leaves
     * pending. Invalid invocations are persisted as invalid completions, and
     * client tool-search calls use the current search engine.
     *
     * A routed local tool invokes its handler directly, without a script control
     * route. Its clean completion is persisted only if the
     * call is still pending, since state-bound tools may have completed it
     * atomically themselves. A missing `mcp__` route is completed as a failure;
     * an unowned non-MCP route remains pending.
     *
     * When handling advances state out of ToolPending, the inner layer resumes.
     * If calls remain unhandled, this call returns at the observable state
     * boundary. Non-cancellation handler failures become matching failed
     * completions; cancellation, routing and persistence failures propagate.
     *
     * @throws IllegalArgumentException if the sampled dynamic catalog has
     * duplicate, empty, or fixed-colliding routes, or a pending local call
     * does not identify a route name.
     */
    public override suspend fun resume()
}

/**
 * Adds a tool-execution layer over externally owned tool state.
 */
public fun ResumableAgentLayer.toolRuntime(
    fixedTools: List<Tool>,
    dynamicTools: StateFlow<List<Tool>>,
    toolSearch: StateFlow<ToolSearchEngine>,
    logger: KLogger,
): KodexToolRuntime = KodexToolRuntime(
    delegate = this,
    fixedTools = fixedTools,
    dynamicTools = dynamicTools,
    toolSearch = toolSearch,
    logger = logger,
)
