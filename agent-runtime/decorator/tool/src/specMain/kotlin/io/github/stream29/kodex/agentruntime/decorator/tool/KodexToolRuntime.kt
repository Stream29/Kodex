@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.agentruntime.decorator.tool

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.RequestFinish
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
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
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.CoroutineContext

/**
 * [ResumableAgentLayer] that handles pending local tool calls against a static [tools] catalog.
 *
 * The supplied [tools] are borrowed: this layer neither constructs nor closes them.
 * Function, freeform, and namespace-member routes must be callable and unique.
 *
 * @param logger Agent-scoped logger used to derive each Tool call logger.
 */
public expect class KodexToolRuntime(
    delegate: ResumableAgentLayer,
    tools: List<Tool>,
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
    override suspend fun createAskUser(questions: RequestUserInputArgs): AskUserEntry
    override suspend fun answerAskUser(askId: Int, response: RequestUserInputResponse): AskUserEntry

    /**
     * Handles an existing pending-tool boundary before delegating to the inner
     * layer. Otherwise delegates first and handles any tool calls it leaves
     * pending. Invalid invocations are persisted as invalid completions.
     *
     * A routed local tool invokes its handler directly. Its clean completion is
     * persisted only if the call is still pending, since state-bound tools may
     * have completed it atomically themselves. Unmatched pending calls are left
     * untouched for an outer runtime layer (such as `AgentRuntime` handling
     * `await_event`) or host completion.
     *
     * When handling advances state out of ToolPending, the inner layer resumes.
     * If unmatched calls remain pending, this call returns at the observable
     * ToolPending state boundary. Non-cancellation handler failures become
     * matching failed completions; cancellation, routing and persistence
     * failures propagate.
     *
     * @throws IllegalArgumentException if [tools] has duplicate or empty routes.
     */
    public override suspend fun resume()
}

/**
 * Adds a static tool-execution layer over [tools].
 */
public fun ResumableAgentLayer.toolRuntime(
    tools: List<Tool>,
    logger: KLogger,
): KodexToolRuntime = KodexToolRuntime(
    delegate = this,
    tools = tools,
    logger = logger,
)
