package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableRequestUserInputToolEvent
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import kotlin.time.Duration

/** Renders a completed request-user-input exchange as the read-only form the user answered. */
@Composable
internal fun StableRequestUserInputToolEvent.renderRequestUserInput(
    elapsed: Duration?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        requestUserInputHistoryRows(elapsed).forEach { row ->
            RequestUserInputHistoryRow(row)
        }
    }
}

/** One renderer-neutral row in the completed read-only request-user-input form. */
@Immutable
public data class RequestUserInputHistoryRowModel(
    public val value: String,
    public val role: RequestUserInputHistoryRowRole,
)

/** Visual role shared by the main History and History Index hover renderers. */
public enum class RequestUserInputHistoryRowRole {
    Header,
    Body,
    Supporting,
    Error,
}

/** Projects the completed exchange into the exact rows used by the read-only History form. */
public fun StableRequestUserInputToolEvent.requestUserInputHistoryRows(
    elapsed: Duration? = null,
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

/** Renders one shared read-only request-user-input row. */
@Composable
public fun RequestUserInputHistoryRow(row: RequestUserInputHistoryRowModel) {
    val textStyle = when (row.role) {
        RequestUserInputHistoryRowRole.Header -> TextStyle.Bold
        RequestUserInputHistoryRowRole.Supporting,
        RequestUserInputHistoryRowRole.Error,
            -> TextStyle.Dim

        RequestUserInputHistoryRowRole.Body -> TextStyle.Unspecified
    }
    val color = if (row.role == RequestUserInputHistoryRowRole.Error) {
        TuiTheme.colorScheme.error
    } else {
        Color.Unspecified
    }
    WrappedHistoryText(
        value = row.value,
        textStyle = textStyle,
        color = color,
    )
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

private fun selectedOptionRow(label: String): RequestUserInputHistoryRowModel =
    RequestUserInputHistoryRowModel(
        value = "[● $label]",
        role = RequestUserInputHistoryRowRole.Body,
    )

private fun freeFormRows(value: String): List<RequestUserInputHistoryRowModel> =
    value.lines().mapIndexed { index, line ->
        RequestUserInputHistoryRowModel(
            value = "${if (index == 0) "  > " else "    "}$line",
            role = RequestUserInputHistoryRowRole.Body,
        )
    }

private fun Duration?.historySuffix(): String =
    this?.let { duration -> " +${duration.roundToMilliseconds()}" }.orEmpty()

private const val UserNotePrefix: String = "user_note: "
