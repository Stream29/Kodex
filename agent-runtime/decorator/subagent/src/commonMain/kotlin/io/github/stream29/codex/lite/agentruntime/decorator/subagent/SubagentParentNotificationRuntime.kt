package io.github.stream29.codex.lite.agentruntime.decorator.subagent

import io.github.stream29.codex.lite.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.codex.lite.agentstorage.contract.indexesDescending
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.openai.AgentMessageInputContent
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow

/**
 * Reports a spawned Agent's resume result to its direct parent.
 *
 * The layer has no Session-tree dependency. It reads its own canonical path
 * from the persisted `threadName`; its caller owns delivery of the clean
 * [StableCleanEvent.AgentMessage].
 * This deliberately keeps parent scheduling and storage outside the runtime
 * layer; the Codex equivalent sends the completion message without triggering
 * a parent turn.
 */
public class SubagentParentNotificationRuntime internal constructor(
    private val delegate: ResumableAgentLayer,
    private val notifyParent: suspend (StableCleanEvent.AgentMessage) -> Unit,
) : ResumableAgentLayer by delegate {
    override fun resume(): Flow<ResponsesStreamEvent> = flow {
        val historyStartIndex = latestIndex.value
        emitAll(delegate.resume())
        val message = latestAssistantMessageSince(historyStartIndex)
        if (message != null) {
            notifyParentBestEffort(message)
        }
    }

    private suspend fun notifyParentBestEffort(message: String) {
        try {
            val childPath = storage.settings.latestValue().threadName
            val parentPath = childPath.substringBeforeLast('/', missingDelimiterValue = "")
            if (parentPath.isEmpty()) return
            notifyParent(message.toParentMessage(childPath, parentPath))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Match Codex: a failed parent delivery must not fail the child turn.
        }
    }

    private suspend fun latestAssistantMessageSince(historyStartIndex: Int): String? =
        storage.stable
            .indexesDescending(latestIndex.value)
            .firstOrNull { index ->
                index > historyStartIndex &&
                    storage.stable[index] is StableCleanEvent.AssistantMessage
            }
            ?.let { index ->
                (storage.stable[index] as StableCleanEvent.AssistantMessage).assistantText()
            }
}

/**
 * Adds parent notification to a spawned Agent runtime.
 *
 * The child path is read from this runtime's persisted settings after its resume
 * flow returns normally. [notifyParent] receives a plaintext
 * [StableCleanEvent.AgentMessage] with the standard `FINAL_ANSWER` envelope; no
 * encrypted-content projection or parent-turn scheduling occurs here.
 */
public fun ResumableAgentLayer.subagentParentNotificationRuntime(
    notifyParent: suspend (StableCleanEvent.AgentMessage) -> Unit,
): SubagentParentNotificationRuntime =
    SubagentParentNotificationRuntime(
        delegate = this,
        notifyParent = notifyParent,
    )

private fun String.toParentMessage(
    childPath: String,
    parentPath: String,
): StableCleanEvent.AgentMessage {
    return StableCleanEvent.AgentMessage(
        author = childPath,
        recipient = parentPath,
        content = listOf(
            AgentMessageInputContent.InputText(
                "Message Type: FINAL_ANSWER\n" +
                    "Task name: $parentPath\n" +
                    "Sender: $childPath\n" +
                    "Payload:\n$this",
            ),
        ),
    )
}

private fun StableCleanEvent.AssistantMessage.assistantText(): String =
    content
        .filterIsInstance<ContentItem.OutputText>()
        .joinToString(separator = "", transform = ContentItem.OutputText::text)
