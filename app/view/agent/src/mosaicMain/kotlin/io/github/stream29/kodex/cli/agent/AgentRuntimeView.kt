package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.app.agent.contract.AgentExecutionState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputDraftAnswer
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputSubmissionState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModel
import io.github.stream29.kodex.app.agent.contract.allowsOtherAnswer
import io.github.stream29.kodex.cli.components.ScrollState
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.verticalScroll
import io.github.stream29.kodex.cli.components.wrapToTerminalWidth
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import kotlinx.coroutines.launch

@Composable
public fun AgentRuntimeStatus(state: AgentExecutionState) {
    Text(state.phase.label())
}

@Composable
public fun RequestUserInputPanel(
    viewModel: RequestUserInputViewModel,
    state: RequestUserInputState.Pending,
    columns: Int,
    rows: Int,
) {
    if (rows <= 0) return
    val scope = rememberCoroutineScope()
    val scrollState = remember(state.callId) { ScrollState() }
    val submitting = state.submission is RequestUserInputSubmissionState.Submitting
    Column(
        modifier = Modifier
            .width(columns.coerceAtLeast(1))
            .height(rows)
            .verticalScroll(scrollState),
    ) {
        state.arguments.questions.forEachIndexed { index, question ->
            RequestUserInputQuestionView(
                callId = state.callId,
                question = question,
                state = state,
                columns = columns,
                autoFocus = index == 0,
                enabled = !submitting,
                onSelectOption = viewModel::selectOption,
                onSelectOther = viewModel::selectOther,
                onFreeFormChanged = viewModel::updateFreeForm,
            )
        }
        (state.submission as? RequestUserInputSubmissionState.Failed)?.let { failure ->
            RequestUserInputText(
                value = "Unable to submit: ${failure.message}",
                columns = columns,
                textStyle = TextStyle.Dim,
            )
        }
        TuiButton(
            label = if (submitting) "Submitting…" else "Submit",
            enabled = state.canSubmit,
            onClick = {
                scope.launch { viewModel.submit(state.callId, state.revision) }
            },
        )
    }
}

@Composable
internal fun RequestUserInputQuestionView(
    callId: String,
    question: RequestUserInputQuestion,
    state: RequestUserInputState.Pending,
    columns: Int,
    autoFocus: Boolean,
    enabled: Boolean,
    onSelectOption: (String, String, String) -> Boolean,
    onSelectOther: (String, String) -> Boolean,
    onFreeFormChanged: (String, String, String) -> Boolean,
) {
    val options = question.options.orEmpty()
    val draft = state.answers[question.id]
    RequestUserInputText(
        value = "${question.header}: ${question.question}",
        columns = columns,
        textStyle = TextStyle.Bold,
    )
    options.forEachIndexed { index, option ->
        val selected = (draft as? RequestUserInputDraftAnswer.Option)?.label == option.label
        TuiButton(
            label = "${if (selected) "●" else "○"} ${option.label}",
            enabled = enabled,
            autoFocus = autoFocus && index == 0 &&
                draft !is RequestUserInputDraftAnswer.FreeForm,
            onClick = { onSelectOption(callId, question.id, option.label) },
        )
        option.description.takeIf(String::isNotBlank)?.let { description ->
            RequestUserInputText("  $description", columns, TextStyle.Dim)
        }
    }
    if (options.isNotEmpty() && question.allowsOtherAnswer) {
        val selected = draft is RequestUserInputDraftAnswer.FreeForm
        TuiButton(
            label = "${if (selected) "●" else "○"} Other",
            enabled = enabled,
            onClick = { onSelectOther(callId, question.id) },
        )
    }
    if (options.isEmpty() || draft is RequestUserInputDraftAnswer.FreeForm) {
        RequestUserInputFreeForm(
            callId = callId,
            question = question,
            text = (draft as? RequestUserInputDraftAnswer.FreeForm)?.text.orEmpty(),
            columns = columns,
            autoFocus = autoFocus,
            enabled = enabled,
            onValueChanged = { text -> onFreeFormChanged(callId, question.id, text) },
        )
    }
}

@Composable
private fun RequestUserInputFreeForm(
    callId: String,
    question: RequestUserInputQuestion,
    text: String,
    columns: Int,
    autoFocus: Boolean,
    enabled: Boolean,
    onValueChanged: (String) -> Unit,
) {
    val input = remember(callId, question.id) {
        TextInputState(TextInputValue(text = text, cursorOffset = text.length))
    }
    LaunchedEffect(input, text) {
        if (input.value.text != text) {
            input.reset(TextInputValue(text = text, cursorOffset = text.length))
        }
    }
    TextInput(
        state = input,
        layout = TextInputLayout.create(
            value = input.value,
            width = columns,
            firstLinePrefix = "  > ",
            continuationLinePrefix = "    ",
        ),
        autoFocus = autoFocus,
        enabled = enabled,
        onValueChanged = { value -> onValueChanged(value.text) },
    )
}

@Composable
private fun RequestUserInputText(
    value: String,
    columns: Int,
    textStyle: TextStyle,
) {
    value.wrapToTerminalWidth(columns.coerceAtLeast(1)).forEach { line ->
        Text(value = line, textStyle = textStyle)
    }
}
