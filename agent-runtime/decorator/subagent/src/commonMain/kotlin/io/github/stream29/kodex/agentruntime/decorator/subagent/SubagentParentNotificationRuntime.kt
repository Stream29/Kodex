package io.github.stream29.kodex.agentruntime.decorator.subagent

import io.github.oshai.kotlinlogging.KLogger
import io.github.stream29.kodex.agentruntime.contract.ResumableAgentLayer
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.indexesDescending
import io.github.stream29.kodex.agentstorage.contract.latestValue
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
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
    private val logger: KLogger,
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
            logger.info { "Parent Agent notification started." }
            notifyParent(message.toParentMessage(childPath, parentPath))
            logger.info { "Parent Agent notification completed." }
        } catch (cancellation: CancellationException) {
            logger.info { "Parent Agent notification cancelled." }
            throw cancellation
        } catch (failure: Exception) {
            logger.warn(failure) { "Failed to notify parent Agent." }
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
 *
 * @param logger Agent-scoped logger for best-effort delivery failures.
 */
public fun ResumableAgentLayer.subagentParentNotificationRuntime(
    logger: KLogger,
    notifyParent: suspend (StableCleanEvent.AgentMessage) -> Unit,
): SubagentParentNotificationRuntime =
    SubagentParentNotificationRuntime(
        delegate = this,
        logger = logger,
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
