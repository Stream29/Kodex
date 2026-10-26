package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

@Composable
public actual fun HistoryRequestUserInputContent(
    event: StableRequestUserInputToolEvent, elapsed: Duration?, renderer: HistoryFormRenderer,
) {
    renderer.Frame {
        event.requestUserInputHistoryRows(elapsed).forEach { renderer.Row(it) }
    }
}

public actual fun StableRequestUserInputToolEvent.requestUserInputHistoryRows(
    elapsed: Duration?,
): List<RequestUserInputHistoryRowModel> = buildList {
    if (arguments.questions.isEmpty()) {
        add(
            RequestUserInputHistoryRowModel(
                value = "Ask the user" + elapsed.historySuffix(),
                role = RequestUserInputHistoryRowRole.Header,
            ),
        )
    } else {
        val answered = (result as? StableRequestUserInputResult.Answered)?.response?.answers
        arguments.questions.forEachIndexed { index, question ->
            add(
                RequestUserInputHistoryRowModel(
                    value = "${question.header}: ${question.question}" +
                        if (index == 0) elapsed.historySuffix() else "",
                    role = RequestUserInputHistoryRowRole.Header,
                ),
            )
            answered?.get(question.id)?.let { answer ->
                addAll(answer.readOnlyRows(question))
            }
        }
    }

    (result as? StableRequestUserInputResult.Failure)?.let { failure ->
        add(
            RequestUserInputHistoryRowModel(
                value = "Failed to submit: ${failure.message}",
                role = RequestUserInputHistoryRowRole.Error,
            ),
        )
    }
}

private fun RequestUserInputAnswer.readOnlyRows(
    question: RequestUserInputQuestion,
): List<RequestUserInputHistoryRowModel> {
    if (answers.isEmpty()) return emptyList()
    if (question.isSecret) {
        return freeFormRows("[hidden]")
    }

    val options = question.options.orEmpty()
    if (options.isEmpty()) {
        return buildList {
            answers.forEach { value ->
                addAll(freeFormRows(value.removePrefix(UserNotePrefix)))
            }
        }
    }

    return buildList {
        var renderedOther = false
        answers.forEach { value ->
            if (value.startsWith(UserNotePrefix)) {
                if (!renderedOther) {
                    add(selectedOptionRow("Other"))
                    renderedOther = true
                }
                addAll(freeFormRows(value.removePrefix(UserNotePrefix)))
            } else {
                add(selectedOptionRow(value))
                options
                    .firstOrNull { option -> option.label == value }
                    ?.description
                    ?.takeIf(String::isNotBlank)
                    ?.let { description ->
                        add(
                            RequestUserInputHistoryRowModel(
                                value = "  $description",
                                role = RequestUserInputHistoryRowRole.Supporting,
                            ),
                        )
                    }
            }
        }
    }
}

internal fun selectedOptionRow(label: String): RequestUserInputHistoryRowModel =
    RequestUserInputHistoryRowModel(
        value = "[● $label]",
        role = RequestUserInputHistoryRowRole.Body,
    )

internal fun freeFormRows(value: String): List<RequestUserInputHistoryRowModel> =
    value.lines().mapIndexed { index, line ->
        RequestUserInputHistoryRowModel(
            value = "${if (index == 0) "  > " else "    "}$line",
            role = RequestUserInputHistoryRowRole.Body,
        )
    }

internal fun Duration?.historySuffix(): String =
    this?.let { duration -> " +${duration.roundToMilliseconds()}" }.orEmpty()

public actual fun Duration.roundToMilliseconds(): Duration {
    if (!isFinite()) return this
    val ms = inWholeMilliseconds
    val remainder = this - ms.milliseconds
    return (ms + when {
        remainder >= 500.microseconds -> 1
        remainder <= (-500).microseconds -> -1
        else -> 0
    }).milliseconds
}

private const val UserNotePrefix: String = "user_note: "
