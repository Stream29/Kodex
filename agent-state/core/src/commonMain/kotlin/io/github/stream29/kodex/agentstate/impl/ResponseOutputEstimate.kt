package io.github.stream29.kodex.agentstate.impl

import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ReasoningItemContent
import io.github.stream29.kodex.openai.ReasoningItemReasoningSummary
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent

/** Counts only visible strings from this attempt; never reads a persisted history window. */
internal class ResponseOutputEstimate {
    private val completedItems = mutableMapOf<Long, OutputLength>()
    private val textParts = mutableMapOf<Pair<Long, Long>, TextPartLength>()

    fun observe(event: ResponsesStreamEvent) {
        when (event) {
            is ResponsesStreamEvent.OutputItemDone -> completedItems[event.outputIndex] = event.item.outputLength()
            is ResponsesStreamEvent.OutputTextDone -> {
                textParts[event.outputIndex to event.contentIndex] = TextPartLength(event.text.length.toLong(), done = true)
            }
            is ResponsesStreamEvent.OutputTextDelta -> {
                val key = event.outputIndex to event.contentIndex
                val previous = textParts[key]
                if (previous?.done != true) {
                    textParts[key] = TextPartLength((previous?.length ?: 0L) + event.delta.length, done = false)
                }
            }
            else -> Unit
        }
    }

    fun estimatedTokens(response: Response): Long? {
        val items = if (response.output.isEmpty()) completedItems else
            response.output.mapIndexed { index, item -> index.toLong() to item.outputLength() }.toMap()
        val itemTextLengths = items.values.mapNotNull(OutputLength::text)
        val textLengths = textParts.entries.mapNotNull { (key, part) -> part.length.takeUnless { key.first in items } }
        val otherLengths = items.values.mapNotNull(OutputLength::other)
        if (itemTextLengths.isEmpty() && textLengths.isEmpty() && otherLengths.isEmpty() && response.outputText == null) return null
        val textLength = if (itemTextLengths.isEmpty() && response.outputText != null) {
            // The aggregate is an alternate representation, never added to individual text parts.
            response.outputText.orEmpty().length.toLong()
        } else {
            itemTextLengths.sum() + textLengths.sum()
        }
        val chars = textLength + otherLengths.sum()
        // Four UTF-16 code units per token, rounded up once over the whole output.
        return chars / 4 + if (chars % 4 == 0L) 0L else 1L
    }

    private data class TextPartLength(val length: Long, val done: Boolean)
}

private data class OutputLength(val text: Long? = null, val other: Long? = null)

private fun ResponseItem.outputLength(): OutputLength = when (this) {
    is ResponseItem.Message -> if (role == MessageRole.Assistant) {
        val lengths = content.filterIsInstance<ContentItem.OutputText>().map { it.text.length.toLong() }
        OutputLength(text = lengths.takeUnless { it.isEmpty() }?.sum())
    } else {
        OutputLength()
    }
    is ResponseItem.FunctionCall -> OutputLength(other = arguments.length.toLong())
    is ResponseItem.CustomToolCall -> OutputLength(other = input.length.toLong())
    is ResponseItem.ClientToolSearchCall -> OutputLength(other = arguments.toString().length.toLong())
    is ResponseItem.ServerToolSearchCall -> OutputLength(other = arguments.toString().length.toLong())
    is ResponseItem.Reasoning -> OutputLength(other = visibleReasoningLength())
    else -> OutputLength()
}

private fun ResponseItem.Reasoning.visibleReasoningLength(): Long? {
    val contents = content.orEmpty()
    return if (contents.isNotEmpty()) contents.sumOf {
        when (it) {
            is ReasoningItemContent.ReasoningText -> it.text.length.toLong()
            is ReasoningItemContent.Text -> it.text.length.toLong()
        }
    } else summary.takeUnless { it.isEmpty() }?.sumOf {
        when (it) {
            is ReasoningItemReasoningSummary.SummaryText -> it.text.length.toLong()
        }
    }
}
