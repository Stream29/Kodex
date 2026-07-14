package io.github.stream29.codex.lite.agentstate.impl

import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ResponseItem

private const val RemoteCompactionV2RetainedMessageTokenBudget: Int = 64_000
private const val ApproximateBytesPerToken: Int = 4

internal fun buildRemoteCompactionV2Prefix(
    input: List<ResponseItem>,
    compactionOutput: ResponseItem.Compaction,
): List<ResponseItem.HistoryItem> =
    input.asSequence()
        .filterIsInstance<ResponseItem.Message>()
        .filter { it.role == MessageRole.User }
        .toList()
        .truncateForRemoteCompaction(RemoteCompactionV2RetainedMessageTokenBudget)
        .plus(compactionOutput)

private fun List<ResponseItem.Message>.truncateForRemoteCompaction(
    maxTokens: Int,
): List<ResponseItem.Message> {
    var remaining = maxTokens
    val retainedReversed = ArrayList<ResponseItem.Message>(size)
    for (item in asReversed()) {
        if (remaining == 0) {
            continue
        }

        val tokenCount = item.messageTextTokenCount().coerceAtLeast(1)
        if (tokenCount <= remaining) {
            retainedReversed += item
            remaining -= tokenCount
        } else {
            item.truncateTextToTokenBudget(remaining)?.let(retainedReversed::add)
            remaining = 0
        }
    }
    retainedReversed.reverse()
    return retainedReversed
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
