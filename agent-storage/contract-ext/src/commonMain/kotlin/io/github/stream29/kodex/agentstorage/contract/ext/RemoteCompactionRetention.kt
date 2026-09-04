package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CompactionRetainedItem
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableSuggestSubagentTaskResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.valuesDescending
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestionOption
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskResponse
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.takeWhile

private const val RemoteCompactionV2RetainedItemTokenBudget: Int = 64_000
private const val ApproximateCharactersPerToken: Int = 4

/**
 * Reads the bounded retained prefix before [beforeIndex].
 *
 * Indexes are fetched through the timeline flow in exponentially growing
 * ranges. Once the token budget is exhausted, collection is cancelled and no
 * older payloads are decoded.
 */
public suspend fun KodexAgentStorage.buildCompactionPrefix(
    beforeIndex: Int,
): List<CompactionRetainedItem> {
    require(beforeIndex >= 0) { "Index $beforeIndex must be non-negative." }
    if (beforeIndex == 0) return emptyList()

    var usedTokens = 0L
    val retainedReversed = mutableListOf<CompactionRetainedItem>()
    index.valuesDescending(beforeIndex - 1)
        .mapNotNull { (_, entry) -> entry as? CompactionRetainedItem }
        .takeWhile { item ->
            retainedReversed += item
            usedTokens += item.approximateTokenCount().coerceAtLeast(1)
            if (usedTokens <= RemoteCompactionV2RetainedItemTokenBudget) {
                true
            } else {
                retainedReversed.removeLast()
                false
            }
        }
        .collect()
    retainedReversed.reverse()
    return retainedReversed
}

private fun CompactionRetainedItem.approximateTokenCount(): Int =
    when (this) {
        is StableUserMessage -> content.textTokenCount()
        is StablePlanUpdate ->
            arguments.approximateTokenCount() +
                PlanUpdatedOutput.approximateTokenCount()
        is StableRequestUserInputToolEvent ->
            arguments.approximateTokenCount() +
                result.approximateTokenCount()
        is StableSuggestSubagentTaskToolEvent ->
            arguments.approximateTokenCount() +
                result.approximateTokenCount()
    }

private fun SuggestSubagentTaskArgs.approximateTokenCount(): Int =
    (24L + tasks.sumOf(SuggestedSubagentTask::approximateTokenCount)).toApproximateTokenCount()

private fun SuggestedSubagentTask.approximateTokenCount(): Long =
    24L + name.approximateTokenCount() + prompt.approximateTokenCount()

private fun StableSuggestSubagentTaskResult.approximateTokenCount(): Int =
    when (this) {
        is StableSuggestSubagentTaskResult.Completed ->
            response.approximateTokenCount()
        is StableSuggestSubagentTaskResult.Failure ->
            16 + message.approximateTokenCount()
    }

private fun SuggestSubagentTaskResponse.approximateTokenCount(): Int =
    when (this) {
        is SuggestSubagentTaskResponse.Accepted ->
            24 + (feedback?.approximateTokenCount() ?: 0) +
                sessions.sumOf { session ->
                    16 + session.uri.approximateTokenCount() + session.name.approximateTokenCount()
                }
        is SuggestSubagentTaskResponse.Rejected ->
            16 + (feedback?.approximateTokenCount() ?: 0)
    }

private fun UpdatePlanArgs.approximateTokenCount(): Int =
    (
        16L +
            (explanation?.approximateTokenCount()?.toLong() ?: 0L) +
            plan.sumOf(PlanItemArg::approximateTokenCount)
        ).toApproximateTokenCount()

private fun PlanItemArg.approximateTokenCount(): Long =
    24L +
        step.approximateTokenCount() +
        when (status) {
            StepStatus.Pending -> 2L
            StepStatus.InProgress -> 3L
            StepStatus.Completed -> 3L
        }

private fun RequestUserInputArgs.approximateTokenCount(): Int =
    (
        24L +
            questions.sumOf(RequestUserInputQuestion::approximateTokenCount) +
            (autoResolutionMs?.decimalDigitCount() ?: 0L)
        ).toApproximateTokenCount()

private fun RequestUserInputQuestion.approximateTokenCount(): Long =
    32L +
        id.approximateTokenCount() +
        header.approximateTokenCount() +
        question.approximateTokenCount() +
        (if (isOther) 12L else 0L) +
        (if (isSecret) 12L else 0L) +
        (options?.let { values ->
            12L +
                values.sumOf(RequestUserInputQuestionOption::approximateTokenCount) +
                (values.size - 1).coerceAtLeast(0)
        } ?: 0L)

private fun RequestUserInputQuestionOption.approximateTokenCount(): Long =
    16L + label.approximateTokenCount() + description.approximateTokenCount()

private fun StableRequestUserInputResult.approximateTokenCount(): Int =
    when (this) {
        is StableRequestUserInputResult.Answered ->
            response.approximateTokenCount()
        is StableRequestUserInputResult.Failure ->
            (16L + message.approximateTokenCount()).toApproximateTokenCount()
    }

private fun RequestUserInputResponse.approximateTokenCount(): Int =
    (
        16L +
            answers.entries.sumOf { (key, answer) ->
                4L + key.approximateTokenCount() + answer.approximateTokenCount()
            }
        ).toApproximateTokenCount()

private fun RequestUserInputAnswer.approximateTokenCount(): Long =
    16L +
        answers.sumOf { answer -> answer.approximateTokenCount() } +
        (answers.size - 1).coerceAtLeast(0)

private fun List<ContentItem>.textTokenCount(): Int =
    sumOf { item ->
        when (item) {
            is ContentItem.InputText -> item.text.approximateTokenCount()
            is ContentItem.OutputText -> item.text.approximateTokenCount()
            is ContentItem.InputImage -> 0
        }
    }

private fun String.approximateTokenCount(): Int =
    ((length.toLong() + ApproximateCharactersPerToken - 1) / ApproximateCharactersPerToken)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

private fun Long.toApproximateTokenCount(): Int =
    coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

private fun Long.decimalDigitCount(): Long {
    var value = this
    var digits = 1L
    if (value < 0) {
        digits += 1
        value = -value
    }
    while (value >= 10) {
        value /= 10
        digits += 1
    }
    return digits
}

private const val PlanUpdatedOutput: String = "Plan updated"
