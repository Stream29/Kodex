package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
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
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.components.verticalScroll
import io.github.stream29.kodex.cli.components.wrapToTerminalWidth
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestionOption
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
    TuiPopupHost(modifier = Modifier.width(columns.coerceAtLeast(1)).height(rows)) {
        RequestUserInputPanelContent(
            viewModel = viewModel,
            state = state,
            columns = columns,
            rows = rows,
        )
    }
}

@Composable
private fun BoxScope.RequestUserInputPanelContent(
    viewModel: RequestUserInputViewModel,
    state: RequestUserInputState.Pending,
    columns: Int,
    rows: Int,
) {
    val scope = rememberCoroutineScope()
    val scrollState = remember(state.callId) { ScrollState() }
    val submitting = state.submission is RequestUserInputSubmissionState.Submitting
    val questionDropdowns = state.arguments.questions.map { question ->
        key(state.callId, question.id) {
            rememberTuiDropdownState()
        }
    }
    Column(
        modifier = Modifier
            .width(columns.coerceAtLeast(1))
            .height(rows)
            .verticalScroll(scrollState),
    ) {
        RequestUserInputText("Input requested", columns, TextStyle.Bold)
        state.arguments.questions.forEachIndexed { index, question ->
            RequestUserInputQuestionView(
                callId = state.callId,
                question = question,
                state = state,
                columns = columns,
                autoFocus = index == 0,
                enabled = !submitting,
                dropdownState = questionDropdowns[index],
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
    state.arguments.questions.forEachIndexed { index, question ->
        RequestUserInputQuestionDropdownMenu(
            callId = state.callId,
            question = question,
            draft = state.answers[question.id],
            dropdownState = questionDropdowns[index],
            enabled = !submitting,
            onSelectOption = viewModel::selectOption,
            onSelectOther = viewModel::selectOther,
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
    dropdownState: TuiDropdownState,
    onFreeFormChanged: (String, String, String) -> Boolean,
) {
    val options = question.options.orEmpty()
    val draft = state.answers[question.id]
    RequestUserInputText(
        value = "${question.header}: ${question.question}",
        columns = columns,
        textStyle = TextStyle.Bold,
    )
    if (options.isNotEmpty()) {
        val selectedChoice = question.choices().firstOrNull { choice -> choice.matches(draft) }
        TuiDropdownTrigger(
            dropdownState = dropdownState,
            label = selectedChoice?.triggerLabel() ?: ChooseOptionLabel,
            enabled = enabled,
            autoFocus = autoFocus && draft !is RequestUserInputDraftAnswer.FreeForm,
        )
        selectedChoice?.description()?.takeIf(String::isNotBlank)?.let { description ->
            RequestUserInputText("  $description", columns, TextStyle.Dim)
        }
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
internal fun BoxScope.RequestUserInputQuestionDropdownMenu(
    callId: String,
    question: RequestUserInputQuestion,
    draft: RequestUserInputDraftAnswer?,
    dropdownState: TuiDropdownState,
    enabled: Boolean,
    onSelectOption: (String, String, String) -> Boolean,
    onSelectOther: (String, String) -> Boolean,
) {
    val choices = question.choices()
    if (choices.isEmpty()) return
    TuiDropdownMenu(
        dropdownState = dropdownState,
        backgroundColor = TuiTheme.colorScheme.surfaceContainerHigh,
    ) {
        choices.forEach { choice ->
            TuiPopupMenuItem(
                key = choice,
                enabled = enabled,
                selected = choice.matches(draft),
                onClick = {
                    when (choice) {
                        is RequestUserInputChoice.ProvidedOption ->
                            onSelectOption(callId, question.id, choice.option.label)

                        RequestUserInputChoice.Other ->
                            onSelectOther(callId, question.id)
                    }
                },
            ) {
                Text(choice.menuLabel())
            }
        }
    }
}

private fun RequestUserInputQuestion.choices(): List<RequestUserInputChoice> = buildList {
    options.orEmpty().forEachIndexed { index, option ->
        add(RequestUserInputChoice.ProvidedOption(index, option))
    }
    if (options.orEmpty().isNotEmpty() && allowsOtherAnswer) {
        add(RequestUserInputChoice.Other)
    }
}

private fun RequestUserInputChoice.matches(draft: RequestUserInputDraftAnswer?): Boolean =
    when (this) {
        is RequestUserInputChoice.ProvidedOption ->
            (draft as? RequestUserInputDraftAnswer.Option)?.label == option.label

        RequestUserInputChoice.Other -> draft is RequestUserInputDraftAnswer.FreeForm
    }

private fun RequestUserInputChoice.triggerLabel(): String = when (this) {
    is RequestUserInputChoice.ProvidedOption -> option.label
    RequestUserInputChoice.Other -> OtherLabel
}

private fun RequestUserInputChoice.menuLabel(): String = when (this) {
    is RequestUserInputChoice.ProvidedOption -> if (option.description.isBlank()) {
        option.label
    } else {
        "${option.label} · ${option.description}"
    }

    RequestUserInputChoice.Other -> OtherLabel
}

private fun RequestUserInputChoice.description(): String = when (this) {
    is RequestUserInputChoice.ProvidedOption -> option.description
    RequestUserInputChoice.Other -> ""
}

private sealed interface RequestUserInputChoice {
    data class ProvidedOption(
        val index: Int,
        val option: RequestUserInputQuestionOption,
    ) : RequestUserInputChoice

    data object Other : RequestUserInputChoice
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

private const val ChooseOptionLabel: String = "Choose an option"
private const val OtherLabel: String = "Other"
