package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
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
        if (arguments.questions.isEmpty()) {
            HistoryItemHeader(
                value = "Ask the user",
                elapsed = elapsed,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle.Bold,
            )
        } else {
            arguments.questions.forEachIndexed { index, question ->
                val elapsedSuffix = if (index == 0) {
                    elapsed?.let { duration -> " +${duration.roundToMilliseconds()}" }.orEmpty()
                } else {
                    ""
                }
                WrappedHistoryText(
                    value = "${question.header}: ${question.question}$elapsedSuffix",
                    textStyle = TextStyle.Bold,
                )
                val answered = result as? StableRequestUserInputResult.Answered
                answered?.response?.answers?.get(question.id)?.renderReadOnly(question)
            }
        }

        (result as? StableRequestUserInputResult.Failure)?.let { failure ->
            WrappedHistoryText(
                value = "Failed to submit: ${failure.message}",
                textStyle = TextStyle.Dim,
                color = TuiTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RequestUserInputAnswer.renderReadOnly(
    question: RequestUserInputQuestion,
) {
    if (answers.isEmpty()) return
    if (question.isSecret) {
        ReadOnlyFreeFormAnswer("[hidden]")
        return
    }

    val options = question.options.orEmpty()
    if (options.isEmpty()) {
        answers.forEach { value ->
            ReadOnlyFreeFormAnswer(value.removePrefix(UserNotePrefix))
        }
        return
    }

    var renderedOther = false
    answers.forEach { value ->
        if (value.startsWith(UserNotePrefix)) {
            if (!renderedOther) {
                ReadOnlySelectedOption("Other")
                renderedOther = true
            }
            ReadOnlyFreeFormAnswer(value.removePrefix(UserNotePrefix))
        } else {
            ReadOnlySelectedOption(value)
            options
                .firstOrNull { option -> option.label == value }
                ?.description
                ?.takeIf(String::isNotBlank)
                ?.let { description ->
                    WrappedHistoryText(
                        value = "  $description",
                        textStyle = TextStyle.Dim,
                    )
                }
        }
    }
}

@Composable
private fun ReadOnlySelectedOption(
    label: String,
) {
    WrappedHistoryText("[● $label]")
}

@Composable
private fun ReadOnlyFreeFormAnswer(
    value: String,
) {
    value.lines().forEachIndexed { index, line ->
        WrappedHistoryText("${if (index == 0) "  > " else "    "}$line")
    }
}

private const val UserNotePrefix: String = "user_note: "
