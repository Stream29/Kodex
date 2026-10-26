package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import kotlinx.coroutines.launch

@Composable
public actual fun RequestUserInputContent(
    viewModel: RequestUserInputViewModel, state: RequestUserInputState.Pending, renderer: UserInputRenderer,
) {
    key(viewModel, state.callId) {
        val scope = rememberCoroutineScope()
        val submitting = state.submission is RequestUserInputSubmissionState.Submitting
        renderer.Frame(state.callId, state.revision, state.arguments.questions.size) { controls ->
            state.arguments.questions.forEachIndexed { index, question ->
                val next = when {
                    index + 1 >= state.arguments.questions.size -> UserInputFocusTarget.Submit
                    state.arguments.questions[index + 1].options.orEmpty().isEmpty() -> UserInputFocusTarget.FreeForm(index + 1)
                    else -> UserInputFocusTarget.Option(index + 1)
                }
                RequestUserInputQuestionContent(
                    question, state.answers[question.id], controls.question(index, question.id), !submitting, index == 0,
                    onSelectOption = { label ->
                        viewModel.selectOption(state.callId, question.id, label).also { if (it) controls.requestFocus(next) }
                    },
                    onSelectOther = {
                        viewModel.selectOther(state.callId, question.id).also {
                            if (it) controls.requestFocus(UserInputFocusTarget.FreeForm(index))
                        }
                    },
                    onFreeFormChanged = { viewModel.updateFreeForm(state.callId, question.id, it) },
                    onFreeFormSubmitted = {
                        val current = viewModel.state.value as? RequestUserInputState.Pending
                        if (current?.callId == state.callId &&
                            (current.answers[question.id] as? RequestUserInputDraftAnswer.FreeForm)?.text?.isNotBlank() == true
                        ) controls.requestFocus(next)
                    },
                )
            }
            (state.submission as? RequestUserInputSubmissionState.Failed)?.let {
                controls.Failure("Unable to submit: ${it.message}")
            }
            controls.Submit(if (submitting) "Submitting…" else "Submit", state.canSubmit) {
                scope.launch { viewModel.submit(state.callId, state.revision) }
            }
        }
    }
}

@Composable
public actual fun RequestUserInputQuestionContent(
    question: RequestUserInputQuestion, draft: RequestUserInputDraftAnswer?,
    renderer: UserInputQuestionRenderer, enabled: Boolean, autoFocus: Boolean,
    onSelectOption: (String) -> Boolean, onSelectOther: () -> Boolean,
    onFreeFormChanged: (String) -> Boolean, onFreeFormSubmitted: () -> Unit,
) {
    val options = question.options.orEmpty()
    renderer.Header("${question.header}: ${question.question}")
    options.forEachIndexed { index, option ->
        renderer.Option(
            option.label, option.description, (draft as? RequestUserInputDraftAnswer.Option)?.label == option.label,
            enabled, autoFocus && index == 0 && draft !is RequestUserInputDraftAnswer.FreeForm, index == 0,
        ) { onSelectOption(option.label) }
    }
    if (options.isNotEmpty() && question.allowsOtherAnswer) {
        renderer.Other(draft is RequestUserInputDraftAnswer.FreeForm, enabled) { onSelectOther() }
    }
    if (options.isEmpty() || draft is RequestUserInputDraftAnswer.FreeForm) {
        renderer.FreeForm(
            (draft as? RequestUserInputDraftAnswer.FreeForm)?.text.orEmpty(), enabled, autoFocus,
            onFreeFormChanged, onFreeFormSubmitted,
        )
    }
}
