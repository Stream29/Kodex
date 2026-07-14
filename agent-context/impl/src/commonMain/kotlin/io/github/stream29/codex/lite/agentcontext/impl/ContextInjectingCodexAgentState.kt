package io.github.stream29.codex.lite.agentcontext.impl

import io.github.stream29.codex.lite.agentcontext.contract.AgentContextProvider
import io.github.stream29.codex.lite.agentcontext.contract.AgentContextSnapshot
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstorage.contract.CodexAgentStorage
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Phase
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Reason
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Trigger
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Decorates this state with source-specific model context.
 *
 * The decorator owns each source's lifecycle. It injects AGENTS.md-derived
 * context before a user message and reinjects it after remote compaction.
 * The decorator converts source content to history and persists it through
 * [CodexAgentState.injectHistory]. That primitive is delegated directly so
 * injected context cannot recursively request more context.
 */
public fun CodexAgentState.withContextInjection(provider: AgentContextProvider): CodexAgentState =
    ContextInjectingCodexAgentState(
        delegate = this,
        provider = provider,
    )

private class ContextInjectingCodexAgentState(
    private val delegate: CodexAgentState,
    private val provider: AgentContextProvider,
) : CodexAgentState {
    override val state: StateFlow<CodexAgentStateValue> = delegate.state
    override val latestIndex: StateFlow<Int> = delegate.latestIndex
    override val storage: CodexAgentStorage = delegate.storage

    override fun requestResponseApi(): Flow<ResponsesStreamEvent> = delegate.requestResponseApi()

    override suspend fun compact(
        trigger: RemoteCompactionV2Trigger,
        reason: RemoteCompactionV2Reason,
        phase: RemoteCompactionV2Phase,
    ): Int {
        val index = delegate.compact(trigger, reason, phase)
        injectAgentMd()
        return index
    }

    override suspend fun injectHistory(items: List<ResponseItem.HistoryItem>): Int =
        delegate.injectHistory(items)

    override suspend fun appendUserMessage(content: List<ContentItem>): Int {
        injectAgentMd()
        return delegate.appendUserMessage(content)
    }

    override suspend fun completeToolCall(output: ResponseItem.ToolCallOutput): Int =
        delegate.completeToolCall(output)

    override suspend fun appendPlanUpdate(
        output: ResponseItem.FunctionCallOutput,
        plan: UpdatePlanArgs,
    ): Int = delegate.appendPlanUpdate(output, plan)

    override suspend fun updateSettings(settings: CodexAgentSettings): Int =
        delegate.updateSettings(settings)

    private suspend fun injectAgentMd() {
        val text = provider.provideAgentMd(
            AgentContextSnapshot(
                state = state.value,
                latestIndex = latestIndex.value,
                storage = storage,
            ),
        ) ?: return
        delegate.injectHistory(
            listOf(
                ResponseItem.Message(
                    role = MessageRole.User,
                    content = listOf(ContentItem.InputText(text)),
                ),
            ),
        )
    }
}
