package io.github.stream29.kodex.agentruntime.decorator.turnhook

import io.github.stream29.kodex.agentcontext.promptdsl.promptXml
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent
import io.github.stream29.kodex.hook.contract.turn.HookPromptFragment
import io.github.stream29.kodex.openai.ContentItem

/**
 * Pure event projections used by this turn Hook implementation.
 *
 * Renders stored user and assistant content plus Stop continuation fragments.
 */
internal object TurnHookProjection {
    /** Concatenates text items from the persisted user message in original order. */
    public fun userPromptText(content: List<ContentItem>): String =
        content.filterIsInstance<ContentItem.InputText>()
            .joinToString(separator = "", transform = ContentItem.InputText::text)

    /** Returns nonblank output text, or `null` if the assistant produced none. */
    public fun assistantOutputText(message: StableAssistantMessage): String? {
        val output = message.content.filterIsInstance<ContentItem.OutputText>()
        return output
            .joinToString(separator = "", transform = ContentItem.OutputText::text)
            .takeIf(String::isNotBlank)
    }

    /** Selects host-owned interactions still waiting for completion. */
    public fun pendingHostInteractions(state: KodexAgentStateValue): List<PendingToolEvent> =
        (state as? KodexAgentStateValue.ToolPending)
            ?.events
            ?.filter { event ->
                event is PendingRequestUserInputToolEvent ||
                    event is PendingSuggestSubagentTaskToolEvent
            }
            .orEmpty()

    /** Fallback Stop Hook message for a pending host interaction. */
    public fun stopHookMessage(pending: PendingToolEvent): String? =
        when (pending) {
            is PendingRequestUserInputToolEvent ->
                pending.arguments.questions
                    .map { question -> question.question }
                    .filter(String::isNotBlank)
                    .joinToString(separator = "\n")
                    .takeIf(String::isNotEmpty)
            is PendingSuggestSubagentTaskToolEvent ->
                pending.arguments.tasks
                    .joinToString(separator = "\n") { task -> "${task.name}: ${task.prompt}" }
                    .takeIf(String::isNotEmpty)
            else -> null
        }

    /** Failure text persisted when Stop cancels a host-owned interaction. */
    public fun stopHookFailureMessage(pending: PendingToolEvent): String =
        when (pending) {
            is PendingRequestUserInputToolEvent -> RequestUserInputCancelledByStopHook
            is PendingSuggestSubagentTaskToolEvent -> SuggestSubagentTaskCancelledByStopHook
            else -> error("Unsupported host-owned pending tool: ${pending.toolName}")
        }

    /** Encodes Stop continuation fragments as a single persisted user message. */
    public fun toHookPromptEvent(fragments: List<HookPromptFragment>): StableUserMessage =
        StableUserMessage(
            content = fragments.map { fragment ->
                ContentItem.InputText(fragment.toHookPromptXml())
            },
        )

    private fun HookPromptFragment.toHookPromptXml(): String =
        promptXml(indented = false) {
            tag(
                name = "hook_prompt",
                attributes = mapOf("hook_run_id" to hookRunId),
            ) {
                text(this@toHookPromptXml.text)
            }
        }

    private const val RequestUserInputCancelledByStopHook: String =
        "request_user_input cancelled by Stop hook"

    private const val SuggestSubagentTaskCancelledByStopHook: String =
        "suggest_subagent_task cancelled by Stop hook"
}
