package io.github.stream29.kodex.agentstate.impl

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.SerializationException

private const val RemoteCompactionV2RetainedItemTokenBudget: Int = 64_000
private const val ApproximateBytesPerToken: Int = 4

internal fun buildRemoteCompactionV2Prefix(
    input: List<ResponseItem>,
): List<StableCleanEvent> =
    input.retainedRemoteCompactionItems()
        .truncateForRemoteCompaction(RemoteCompactionV2RetainedItemTokenBudget)
        .map(RemoteCompactionV2RetainedItem::stableEvent)

private fun List<ResponseItem>.retainedRemoteCompactionItems(): List<RemoteCompactionV2RetainedItem> =
    buildList {
        var index = 0
        while (index < this@retainedRemoteCompactionItems.size) {
            when (val item = this@retainedRemoteCompactionItems[index]) {
                is ResponseItem.Message -> {
                    if (item.role == MessageRole.User) {
                        add(RemoteCompactionV2RetainedItem.UserMessage(item))
                    }
                }

                is ResponseItem.FunctionCall -> {
                    val output = this@retainedRemoteCompactionItems.getOrNull(index + 1)
                        as? ResponseItem.FunctionCallOutput
                    item.toStablePlanUpdate(output)?.let { planUpdate ->
                        add(
                            RemoteCompactionV2RetainedItem.PlanUpdate(
                                stableEvent = planUpdate,
                                arguments = item.arguments,
                            ),
                        )
                        index += 1
                    }
                }

                else -> Unit
            }
            index += 1
        }
    }

private fun ResponseItem.FunctionCall.toStablePlanUpdate(
    output: ResponseItem.FunctionCallOutput?,
): StablePlanUpdate? {
    if (namespace != null || name != UpdatePlanToolName || output?.callId != callId) {
        return null
    }
    if (output.output.success == false) {
        return null
    }
    val plan = try {
        OpenAiJsonCodec.decodeFromString(UpdatePlanArgs.serializer(), arguments)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    return StablePlanUpdate(
        callId = callId,
        itemId = id,
        arguments = plan,
    )
}

private fun List<RemoteCompactionV2RetainedItem>.truncateForRemoteCompaction(
    maxTokens: Int,
): List<RemoteCompactionV2RetainedItem> {
    var remaining = maxTokens
    val retainedReversed = ArrayList<RemoteCompactionV2RetainedItem>(size)
    for (item in asReversed()) {
        if (remaining == 0) {
            continue
        }

        val tokenCount = item.approximateTokenCount().coerceAtLeast(1)
        if (tokenCount <= remaining) {
            retainedReversed += item
            remaining -= tokenCount
        } else {
            item.truncateToTokenBudget(remaining)?.let(retainedReversed::add)
            remaining = 0
        }
    }
    retainedReversed.reverse()
    return retainedReversed
}

private sealed interface RemoteCompactionV2RetainedItem {
    val stableEvent: StableCleanEvent

    fun approximateTokenCount(): Int

    fun truncateToTokenBudget(maxTokens: Int): RemoteCompactionV2RetainedItem? = null

    data class UserMessage(
        val message: ResponseItem.Message,
    ) : RemoteCompactionV2RetainedItem {
        override val stableEvent: StableCleanEvent =
            StableCleanEvent.UserMessage(message.content)

        override fun approximateTokenCount(): Int =
            message.messageTextTokenCount()

        override fun truncateToTokenBudget(maxTokens: Int): RemoteCompactionV2RetainedItem? =
            message.truncateTextToTokenBudget(maxTokens)?.let(::UserMessage)
    }

    data class PlanUpdate(
        override val stableEvent: StablePlanUpdate,
        val arguments: String,
    ) : RemoteCompactionV2RetainedItem {
        override fun approximateTokenCount(): Int =
            arguments.approximateTokenCount() + PlanUpdatedOutput.approximateTokenCount()
    }
}

private fun ResponseItem.Message.messageTextTokenCount(): Int =
    content.sumOf { item ->
        when (item) {
            is ContentItem.InputText -> item.text.approximateTokenCount()
            is ContentItem.OutputText -> item.text.approximateTokenCount()
            is ContentItem.InputImage -> 0
        }
    }

private fun ResponseItem.Message.truncateTextToTokenBudget(maxTokens: Int): ResponseItem.Message? {
    var remaining = maxTokens
    val truncatedContent = buildList {
        for (item in content) {
            when (item) {
                is ContentItem.InputText -> {
                    if (remaining == 0) {
                        continue
                    }
                    val text = item.text
                    val tokenCount = text.approximateTokenCount()
                    val truncatedText = if (tokenCount <= remaining) {
                        remaining -= tokenCount
                        text
                    } else {
                        val budget = remaining
                        remaining = 0
                        text.truncateToTokenBudget(maxTokens = budget)
                    }
                    if (truncatedText.isNotEmpty()) {
                        add(item.copy(text = truncatedText))
                    }
                }

                is ContentItem.OutputText -> {
                    if (remaining == 0) {
                        continue
                    }
                    val text = item.text
                    val tokenCount = text.approximateTokenCount()
                    val truncatedText = if (tokenCount <= remaining) {
                        remaining -= tokenCount
                        text
                    } else {
                        val budget = remaining
                        remaining = 0
                        text.truncateToTokenBudget(maxTokens = budget)
                    }
                    if (truncatedText.isNotEmpty()) {
                        add(item.copy(text = truncatedText))
                    }
                }

                is ContentItem.InputImage -> add(item)
            }
        }
    }
    return takeIf { truncatedContent.isNotEmpty() }?.copy(content = truncatedContent)
}

private fun String.approximateTokenCount(): Int {
    return encodeToByteArray().size.approximateTokenCount()
}

private fun String.truncateToTokenBudget(maxTokens: Int): String {
    if (isEmpty()) {
        return this
    }

    val bytes = encodeToByteArray()
    val maxBytes = maxTokens * ApproximateBytesPerToken
    if (maxTokens > 0 && bytes.size <= maxBytes) {
        return this
    }
    if (maxBytes == 0) {
        return "…${bytes.size.approximateTokenCount()} tokens truncated…"
    }

    val prefixEnd = bytes.previousUtf8Boundary(maxBytes / 2)
    var suffixStart = bytes.nextUtf8Boundary(bytes.size - (maxBytes - maxBytes / 2))
    if (suffixStart < prefixEnd) {
        suffixStart = prefixEnd
    }
    val removedTokenCount = (bytes.size - maxBytes).coerceAtLeast(0).approximateTokenCount()
    return bytes.decodeToString(0, prefixEnd) +
        "…$removedTokenCount tokens truncated…" +
        bytes.decodeToString(suffixStart, bytes.size)
}

private fun Int.approximateTokenCount(): Int =
    this / ApproximateBytesPerToken + if (this % ApproximateBytesPerToken == 0) 0 else 1

private fun ByteArray.previousUtf8Boundary(atMost: Int): Int {
    var boundary = atMost.coerceIn(0, size)
    while (boundary in 1 until size && this[boundary].isUtf8ContinuationByte()) {
        boundary -= 1
    }
    return boundary
}

private fun ByteArray.nextUtf8Boundary(atLeast: Int): Int {
    var boundary = atLeast.coerceIn(0, size)
    while (boundary < size && this[boundary].isUtf8ContinuationByte()) {
        boundary += 1
    }
    return boundary
}

private fun Byte.isUtf8ContinuationByte(): Boolean =
    (toInt() and 0b1100_0000) == 0b1000_0000

private const val UpdatePlanToolName: String = "update_plan"
private const val PlanUpdatedOutput: String = "Plan updated"
