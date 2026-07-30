package io.github.stream29.codex.lite.cli.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.codex.lite.cli.components.ScrollState
import io.github.stream29.codex.lite.cli.components.TextInput
import io.github.stream29.codex.lite.cli.components.TextInputLayout
import io.github.stream29.codex.lite.cli.components.TextInputState
import io.github.stream29.codex.lite.cli.components.TextInputValue
import io.github.stream29.codex.lite.cli.components.TuiButton
import io.github.stream29.codex.lite.cli.components.verticalScroll
import io.github.stream29.codex.lite.cli.components.wrapToTerminalWidth
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputQuestion

/** Renders one Agent's current typed runtime state without interpreting SSE in the UI layer. */
@Composable
public fun AgentRuntimeStatus(state: AgentRuntimeViewState) {
    Text(state.toRenderState().label())
}

/**
 * Renders the active `request_user_input` form in the Agent screen's content flow.
 *
 * The caller reserves this panel between history and the ordinary composer. It does not appear
 * for other pending tools or for multiple simultaneous pending calls.
 */
@Composable
public fun RequestUserInputPanel(
    viewModel: AgentRuntimeViewModel,
    state: RequestUserInputViewState,
    columns: Int,
    rows: Int,
) {
    val arguments = state.arguments ?: return
    val callId = state.callId ?: return
    if (rows <= 0) return
    val scrollState = remember(state.callId) { ScrollState() }

    Column(
        modifier = Modifier
            .width(columns.coerceAtLeast(1))
            .height(rows)
            .verticalScroll(scrollState),
    ) {
        RequestUserInputText(
            value = "Input requested",
            columns = columns,
            textStyle = TextStyle.Bold,
        )
        arguments.questions.forEachIndexed { index, question ->
            RequestUserInputQuestionView(
                callId = callId,
                question = question,
                state = state,
                columns = columns,
                autoFocus = index == 0,
                onSelectOption = viewModel.requestUserInput::selectOption,
                onSelectOther = viewModel.requestUserInput::selectOther,
                onFreeFormChanged = viewModel.requestUserInput::updateFreeForm,
            )
        }
        state.failureMessage?.let { failure ->
            RequestUserInputText(
                value = "Unable to submit: $failure",
                columns = columns,
                textStyle = TextStyle.Dim,
            )
        }
        TuiButton(
            label = if (state.isSubmitting) "Submitting…" else "Submit",
            enabled = state.canSubmit,
            onClick = { viewModel.submitRequestUserInput() },
        )
    }
}

@Composable
private fun RequestUserInputQuestionView(
    callId: String,
    question: RequestUserInputQuestion,
    state: RequestUserInputViewState,
    columns: Int,
    autoFocus: Boolean,
    onSelectOption: (questionId: String, label: String) -> Unit,
    onSelectOther: (questionId: String) -> Unit,
    onFreeFormChanged: (questionId: String, text: String) -> Unit,
) {
    val options = question.options.orEmpty()
    val draft = state.answers[question.id]
    val hasOptions = options.isNotEmpty()

    RequestUserInputText(
        value = "${question.header}: ${question.question}",
        columns = columns,
        textStyle = TextStyle.Bold,
    )
    options.forEachIndexed { index, option ->
        val selected = (draft as? RequestUserInputDraftAnswer.Option)?.label == option.label
        TuiButton(
            label = "${if (selected) "●" else "○"} ${option.label}",
            enabled = !state.isSubmitting,
            autoFocus = autoFocus && index == 0 && draft !is RequestUserInputDraftAnswer.FreeForm,
            onClick = { onSelectOption(question.id, option.label) },
        )
        option.description.takeIf(String::isNotBlank)?.let { description ->
            RequestUserInputText(
                value = "  $description",
                columns = columns,
                textStyle = TextStyle.Dim,
            )
        }
    }

    if (hasOptions && question.allowsOtherAnswer) {
        val selected = draft is RequestUserInputDraftAnswer.FreeForm
        TuiButton(
            label = "${if (selected) "●" else "○"} Other",
            enabled = !state.isSubmitting,
            onClick = { onSelectOther(question.id) },
        )
    }

    if (!hasOptions || draft is RequestUserInputDraftAnswer.FreeForm) {
        RequestUserInputFreeForm(
            callId = callId,
            question = question,
            text = (draft as? RequestUserInputDraftAnswer.FreeForm)?.text.orEmpty(),
            columns = columns,
            autoFocus = autoFocus && (!hasOptions || draft is RequestUserInputDraftAnswer.FreeForm),
            enabled = !state.isSubmitting,
            onValueChanged = { value -> onFreeFormChanged(question.id, value) },
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
