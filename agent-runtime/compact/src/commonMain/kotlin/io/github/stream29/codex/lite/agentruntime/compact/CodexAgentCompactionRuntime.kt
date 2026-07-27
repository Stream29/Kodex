package io.github.stream29.codex.lite.agentruntime.compact

import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentruntime.contextwindow.tokensUntilCompaction
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.hook.contract.compaction.CompactionHookRequest
import io.github.stream29.codex.lite.hook.contract.compaction.CompactionHooks
import io.github.stream29.codex.lite.hook.contract.compaction.HookCompactionTrigger
import io.github.stream29.codex.lite.hook.contract.toHookTurnContext
import io.github.stream29.codex.lite.openai.CompactionPhase
import io.github.stream29.codex.lite.openai.CompactionReason
import io.github.stream29.codex.lite.openai.CompactionTrigger
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.modelcatalog.OpenAiModelCatalog
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow

/**
 * Basic runtime with no environment-side effects.
 *
 * It handles token-limit compaction and server-requested continuation before
 * returning control. A pending tool call ends this flow so a higher runtime
 * can execute the tool through the inherited atomic state API.
 *
 * @param compactionHooks Nullable because Hooks are an optional host feature;
 * `null` runs the compaction core without PreCompact or PostCompact.
 */
public class CodexAgentCompactionRuntime(
    private val delegate: CodexAgentState,
    private val modelCatalog: OpenAiModelCatalog,
    private val compactionHooks: CompactionHooks? = null,
) : CodexAgentRuntime, CodexAgentState by delegate {

    public override fun resume(): Flow<ResponsesStreamEvent> = channelFlow {
        if (state.value is CodexAgentStateValue.ToolPending) {
            return@channelFlow
        }

        if (shouldAutoCompact()) {
            compactForContextLimit(CompactionPhase.PreTurn)
        }

        while (true) {
            var needsFollowUp = false
            requestResponseApi().collect { event ->
                if (event is ResponsesStreamEvent.Completed && event.response.endTurn == false) {
                    needsFollowUp = true
                }
                send(event)
            }

            if (state.value is CodexAgentStateValue.ToolPending || !needsFollowUp) {
                return@channelFlow
            }

            if (shouldAutoCompact()) {
                compactForContextLimit(CompactionPhase.MidTurn)
            }
        }
    }.buffer(Channel.UNLIMITED)

    override suspend fun compact(
        trigger: CompactionTrigger,
        reason: CompactionReason,
        phase: CompactionPhase,
    ): Int {
        val hooks = compactionHooks ?: return delegate.compact(trigger, reason, phase)
        val context = storage.settings[latestIndex.value].toHookTurnContext(storage.id)
        val request = CompactionHookRequest(
            context = context,
            trigger = trigger.toHookTrigger(),
        )
        hooks.onPreCompact(request)

        val index = delegate.compact(trigger, reason, phase)
        hooks.onPostCompact(request)
        return index
    }

    private suspend fun compactForContextLimit(phase: CompactionPhase) {
        compact(
            trigger = CompactionTrigger.Auto,
            reason = CompactionReason.ContextLimit,
            phase = phase,
        )
    }

    private suspend fun shouldAutoCompact(): Boolean {
        return tokensUntilCompaction(modelCatalog) == 0L
    }
}

/**
 * Adds automatic compaction and server-requested continuation to this state.
 *
 * @param compactionHooks Nullable because Hooks are optional; `null` disables
 * both compaction Hook boundaries.
 */
public fun CodexAgentState.compactionRuntime(
    modelCatalog: OpenAiModelCatalog,
    compactionHooks: CompactionHooks? = null,
): CodexAgentRuntime =
    CodexAgentCompactionRuntime(
        delegate = this,
        modelCatalog = modelCatalog,
        compactionHooks = compactionHooks,
    )

private fun CompactionTrigger.toHookTrigger(): HookCompactionTrigger = when (this) {
    CompactionTrigger.Auto -> HookCompactionTrigger.Auto
    CompactionTrigger.Manual -> HookCompactionTrigger.Manual
}
