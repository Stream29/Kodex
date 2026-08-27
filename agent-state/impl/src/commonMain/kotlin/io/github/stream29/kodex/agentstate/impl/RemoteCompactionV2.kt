package io.github.stream29.kodex.agentstate.impl

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.RemoteCompactionV2RetainedItem
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import kotlinx.serialization.json.Json

private const val RemoteCompactionV2RetainedItemTokenBudget: Int = 64_000
private const val ApproximateBytesPerToken: Int = 4

internal fun buildRemoteCompactionV2Prefix(
    events: List<StableCleanEvent>,
): List<RemoteCompactionV2RetainedItem> =
    events.filterIsInstance<RemoteCompactionV2RetainedItem>()
        .truncateForRemoteCompaction(RemoteCompactionV2RetainedItemTokenBudget)

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

private fun RemoteCompactionV2RetainedItem.approximateTokenCount(): Int =
    when (this) {
        is StableCleanEvent.UserMessage -> content.textTokenCount()
        is StablePlanUpdate ->
            Json.encodeToString(UpdatePlanArgs.serializer(), arguments).approximateTokenCount() +
                PlanUpdatedOutput.approximateTokenCount()

        is StableRequestUserInputToolEvent ->
            Json.encodeToString(RequestUserInputArgs.serializer(), arguments).approximateTokenCount() +
                result.outputText().approximateTokenCount()
    }

private fun RemoteCompactionV2RetainedItem.truncateToTokenBudget(
    maxTokens: Int,
): RemoteCompactionV2RetainedItem? =
    when (this) {
        is StableCleanEvent.UserMessage ->
            content.truncateTextToTokenBudget(maxTokens)?.let(::copy)

        is StablePlanUpdate,
        is StableRequestUserInputToolEvent,
            -> null
    }

private fun StableRequestUserInputResult.outputText(): String =
    when (this) {
        is StableRequestUserInputResult.Answered ->
            Json.encodeToString(RequestUserInputResponse.serializer(), response)

        is StableRequestUserInputResult.Failure -> message
    }

private fun List<ContentItem>.textTokenCount(): Int =
    sumOf { item ->
        when (item) {
            is ContentItem.InputText -> item.text.approximateTokenCount()
            is ContentItem.OutputText -> item.text.approximateTokenCount()
            is ContentItem.InputImage -> 0
        }
    }

private fun List<ContentItem>.truncateTextToTokenBudget(
    maxTokens: Int,
): List<ContentItem>? {
    var remaining = maxTokens
    val truncatedContent = buildList {
        for (item in this@truncateTextToTokenBudget) {
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
    return truncatedContent.takeIf(List<ContentItem>::isNotEmpty)
}

private fun String.approximateTokenCount(): Int =
    encodeToByteArray().size.approximateTokenCount()

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

private const val PlanUpdatedOutput: String = "Plan updated"
