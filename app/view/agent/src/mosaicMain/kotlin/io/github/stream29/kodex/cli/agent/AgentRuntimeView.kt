package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.onPlaced
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.app.agent.contract.AgentExecutionState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputDraftAnswer
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputSubmissionState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModel
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskViewModel
import io.github.stream29.kodex.app.agent.contract.allowsOtherAnswer
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.components.ScrollState
import io.github.stream29.kodex.cli.components.TextInput
import io.github.stream29.kodex.cli.components.TextInputLayout
import io.github.stream29.kodex.cli.components.TextInputState
import io.github.stream29.kodex.cli.components.TextInputValue
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.verticalScroll
import io.github.stream29.kodex.cli.components.wrapToTerminalWidth
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.availableServiceTiers
import io.github.stream29.kodex.openai.ReasoningEffort
import kotlinx.io.files.Path
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
    val questionOptionFocusRequesters = remember(state.callId) {
        List(state.arguments.questions.size) { FocusRequester() }
    }
    val questionFreeFormFocusRequesters = remember(state.callId) {
        List(state.arguments.questions.size) { FocusRequester() }
    }
    val submitFocusRequester = remember(state.callId) { FocusRequester() }
    var pendingFocusTarget by remember(state.callId) {
        mutableStateOf<RequestUserInputFocusTarget?>(null)
    }
    val submitting = state.submission is RequestUserInputSubmissionState.Submitting
    fun focusRequesterFor(target: RequestUserInputFocusTarget): FocusRequester? =
        when (target) {
            is RequestUserInputFocusTarget.QuestionOption ->
                questionOptionFocusRequesters.getOrNull(target.index)

            is RequestUserInputFocusTarget.QuestionFreeForm ->
                questionFreeFormFocusRequesters.getOrNull(target.index)

            RequestUserInputFocusTarget.Submit -> submitFocusRequester
        }

    fun requestFocus(target: RequestUserInputFocusTarget) {
        pendingFocusTarget = target
        focusRequesterFor(target)?.let { requester ->
            if (requester.requestFocus()) pendingFocusTarget = null
        }
    }

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
                optionFocusRequester = questionOptionFocusRequesters[index],
                freeFormFocusRequester = questionFreeFormFocusRequesters[index],
                focusFreeFormOnPlacement =
                    pendingFocusTarget == RequestUserInputFocusTarget.QuestionFreeForm(index),
                onFreeFormFocusRequested = { pendingFocusTarget = null },
                enabled = !submitting,
                onSelectOption = { callId, questionId, label ->
                    viewModel.selectOption(callId, questionId, label).also { accepted ->
                        if (accepted) {
                            requestFocus(
                                nextFocusTarget(
                                    questionIndex = index,
                                    questions = state.arguments.questions,
                                ),
                            )
                        }
                    }
                },
                onSelectOther = { callId, questionId ->
                    viewModel.selectOther(callId, questionId).also { accepted ->
                        if (accepted) {
                            requestFocus(RequestUserInputFocusTarget.QuestionFreeForm(index))
                        }
                    }
                },
                onFreeFormChanged = viewModel::updateFreeForm,
                onFreeFormSubmitted = {
                    requestFocus(
                        nextFocusTarget(
                            questionIndex = index,
                            questions = state.arguments.questions,
                        ),
                    )
                },
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
            modifier = Modifier
                .onPlaced {
                    if (
                        pendingFocusTarget == RequestUserInputFocusTarget.Submit &&
                            submitFocusRequester.requestFocus()
                    ) {
                        pendingFocusTarget = null
                    }
                },
            enabled = state.canSubmit,
            focusRequester = submitFocusRequester,
            onClick = {
                scope.launch { viewModel.submit(state.callId, state.revision) }
            },
        )
    }
    SideEffect {
        val target = pendingFocusTarget ?: return@SideEffect
        val focusRequester = focusRequesterFor(target) ?: return@SideEffect
        if (focusRequester.requestFocus()) pendingFocusTarget = null
    }
    LaunchedEffect(pendingFocusTarget, state.revision) {
        val target = pendingFocusTarget ?: return@LaunchedEffect
        val focusRequester = focusRequesterFor(target) ?: return@LaunchedEffect
        if (focusRequester.requestFocus()) {
            pendingFocusTarget = null
            return@LaunchedEffect
        }
        // A newly enabled Submit button or a newly materialized Other input may not be in the
        // focus tree until the next layout pass.
        repeat(3) {
            withFrameNanos { }
            if (focusRequester.requestFocus()) {
                pendingFocusTarget = null
                return@LaunchedEffect
            }
        }
    }
}

@Composable
public fun SuggestSubagentTaskPanel(
    viewModel: SuggestSubagentTaskViewModel,
    state: SuggestSubagentTaskState.Pending,
    models: List<ModelInfo>,
    columns: Int,
    rows: Int,
) {
    if (rows <= 0) return
    val scope = rememberCoroutineScope()
    val configurationDropdown = rememberTuiDropdownState()
    val requestUserInputModeDropdown = rememberTuiDropdownState()
    val feedback = remember(state.callId) {
        TextInputState(TextInputValue(state.feedback, state.feedback.length))
    }
    val cwd = remember(state.callId) {
        TextInputState(
            TextInputValue(
                state.configuration.cwd.toString(),
                state.configuration.cwd.toString().length,
            ),
        )
    }
    LaunchedEffect(state.feedback) {
        if (feedback.value.text != state.feedback) {
            feedback.reset(TextInputValue(state.feedback, state.feedback.length))
        }
    }
    val configurationOptions = remember(models, state.configuration) {
        suggestedConfigurationOptions(models, state.configuration)
    }
    Box(modifier = Modifier.width(columns.coerceAtLeast(1)).height(rows)) {
        Column {
            Text("Suggested Sessions", textStyle = TextStyle.Bold)
            state.arguments.tasks.forEach { task ->
                Text(
                    "${task.name}: ${task.prompt}"
                        .wrapToTerminalWidth(columns)
                        .joinToString("\n"),
                )
            }
            Row {
                TuiDropdownTrigger(
                    dropdownState = configurationDropdown,
                    label = "Config: ${state.configuration.model.value} " +
                        "${state.configuration.reasoningEffort.suggestedDisplayName()} " +
                        state.configuration.serviceTier.suggestedDisplayName(),
                    enabled = !state.submitting,
                )
                TuiDropdownTrigger(
                    dropdownState = requestUserInputModeDropdown,
                    label = "Ask: ${state.configuration.requestUserInputMode.suggestedDisplayName()}",
                    enabled = !state.submitting,
                )
            }
            TextInput(
                state = cwd,
                layout = TextInputLayout.create(
                    value = cwd.value,
                    width = columns,
                    firstLinePrefix = "  cwd: ",
                    continuationLinePrefix = "       ",
                ),
                enabled = !state.submitting,
                onValueChanged = { value ->
                    cwd.reset(TextInputValue(value.text, value.cursorOffset))
                    viewModel.updateConfiguration(
                        state.callId,
                        state.configuration.copy(cwd = Path(value.text)),
                    )
                },
            )
            TextInput(
                state = feedback,
                layout = TextInputLayout.create(
                    value = feedback.value,
                    width = columns,
                    firstLinePrefix = "  Note: ",
                    continuationLinePrefix = "        ",
                ),
                enabled = !state.submitting,
                onValueChanged = { value ->
                    viewModel.updateFeedback(state.callId, value.text)
                },
            )
            Row {
                TuiButton(
                    label = if (state.submitting) "Submitting…" else "Accept",
                    enabled = !state.submitting,
                    onClick = {
                        scope.launch {
                            viewModel.submit(state.callId, state.revision, accepted = true)
                        }
                    },
                )
                TuiButton(
                    label = "Reject",
                    enabled = !state.submitting,
                    onClick = {
                        scope.launch {
                            viewModel.submit(state.callId, state.revision, accepted = false)
                        }
                    },
                )
            }
        }
        TuiDropdownMenu(
            dropdownState = configurationDropdown,
            options = configurationOptions,
            selected = state.configuration.toMenuOption(),
            optionLabel = SuggestedConfigurationOption::label,
            onSelect = { option ->
                configurationDropdown.dismiss()
                viewModel.updateConfiguration(state.callId, option.configuration)
            },
        )
        TuiDropdownMenu(
            dropdownState = requestUserInputModeDropdown,
            options = RequestUserInputMode.entries.toList(),
            selected = state.configuration.requestUserInputMode,
            optionLabel = RequestUserInputMode::suggestedDisplayName,
            onSelect = { mode ->
                requestUserInputModeDropdown.dismiss()
                viewModel.updateConfiguration(
                    state.callId,
                    state.configuration.copy(requestUserInputMode = mode),
                )
            },
        )
    }
}

private data class SuggestedConfigurationOption(
    val configuration: io.github.stream29.kodex.app.agent.contract.SuggestedSessionConfiguration,
) {
    val label: String
        get() = buildString {
            append(configuration.model)
            append(' ')
            append(configuration.reasoningEffort.suggestedDisplayName())
            if (configuration.serviceTier != ServiceTier.Default) {
                append(' ')
                append(configuration.serviceTier.suggestedDisplayName())
            }
        }
}

private fun suggestedConfigurationOptions(
    models: List<ModelInfo>,
    current: io.github.stream29.kodex.app.agent.contract.SuggestedSessionConfiguration,
): List<SuggestedConfigurationOption> {
    val modelOptions = (models.map(ModelInfo::slug) + current.model).distinct()
    return modelOptions.flatMap { model ->
        val info = models.firstOrNull { it.slug == model }
        val efforts = info?.supportedReasoningLevels
            ?.map { it.effort }
            .orEmpty()
            .ifEmpty { listOf(current.reasoningEffort) }
        val tiers = info?.availableServiceTiers()
            .orEmpty()
            .ifEmpty { listOf(ServiceTier.Default) }
        efforts.flatMap { effort ->
            tiers.map { tier ->
                SuggestedConfigurationOption(
                    current.copy(
                        model = model,
                        reasoningEffort = effort,
                        serviceTier = tier,
                    ),
                )
            }
        }
    }
}

private fun io.github.stream29.kodex.app.agent.contract.SuggestedSessionConfiguration.toMenuOption():
    SuggestedConfigurationOption = SuggestedConfigurationOption(this)

private fun ReasoningEffort.suggestedDisplayName(): String = when (this) {
    ReasoningEffort.None -> "none"
    ReasoningEffort.Minimal -> "minimal"
    ReasoningEffort.Low -> "low"
    ReasoningEffort.Medium -> "medium"
    ReasoningEffort.High -> "high"
    ReasoningEffort.XHigh -> "xhigh"
    ReasoningEffort.Max -> "max"
    is ReasoningEffort.Custom -> wireName
}

private fun ServiceTier.suggestedDisplayName(): String = when (this) {
    ServiceTier.Default -> "default"
    ServiceTier.Fast -> "fast"
    ServiceTier.Flex -> "flex"
}

private fun RequestUserInputMode.suggestedDisplayName(): String = when (this) {
    RequestUserInputMode.AskUser -> "ask user"
    RequestUserInputMode.NoQuestion -> "no question"
}

@Composable
internal fun RequestUserInputQuestionView(
    callId: String,
    question: RequestUserInputQuestion,
    state: RequestUserInputState.Pending,
    columns: Int,
    autoFocus: Boolean,
    optionFocusRequester: FocusRequester,
    freeFormFocusRequester: FocusRequester,
    focusFreeFormOnPlacement: Boolean,
    onFreeFormFocusRequested: () -> Unit,
    enabled: Boolean,
    onSelectOption: (String, String, String) -> Boolean,
    onSelectOther: (String, String) -> Boolean,
    onFreeFormChanged: (String, String, String) -> Boolean,
    onFreeFormSubmitted: () -> Unit,
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
            focusRequester = optionFocusRequester.takeIf { index == 0 },
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
            focusRequester = freeFormFocusRequester,
            focusOnPlacement = focusFreeFormOnPlacement,
            enabled = enabled,
            onValueChanged = { text -> onFreeFormChanged(callId, question.id, text) },
            onSubmitted = onFreeFormSubmitted,
            onFocusRequested = onFreeFormFocusRequested,
        )
    }
}

private sealed interface RequestUserInputFocusTarget {
    data class QuestionOption(val index: Int) : RequestUserInputFocusTarget

    data class QuestionFreeForm(val index: Int) : RequestUserInputFocusTarget

    data object Submit : RequestUserInputFocusTarget
}

private fun nextFocusTarget(
    questionIndex: Int,
    questions: List<RequestUserInputQuestion>,
): RequestUserInputFocusTarget =
    when {
        questionIndex + 1 >= questions.size -> RequestUserInputFocusTarget.Submit
        questions[questionIndex + 1].options.orEmpty().isEmpty() ->
            RequestUserInputFocusTarget.QuestionFreeForm(questionIndex + 1)
        else -> RequestUserInputFocusTarget.QuestionOption(questionIndex + 1)
    }

@Composable
private fun RequestUserInputFreeForm(
    callId: String,
    question: RequestUserInputQuestion,
    text: String,
    columns: Int,
    autoFocus: Boolean,
    focusRequester: FocusRequester,
    focusOnPlacement: Boolean,
    enabled: Boolean,
    onValueChanged: (String) -> Unit,
    onSubmitted: () -> Unit,
    onFocusRequested: () -> Unit,
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
        modifier = Modifier.onPlaced {
            if (focusOnPlacement && focusRequester.requestFocus()) onFocusRequested()
        },
        layout = TextInputLayout.create(
            value = input.value,
            width = columns,
            firstLinePrefix = "  > ",
            continuationLinePrefix = "    ",
        ),
        autoFocus = autoFocus,
        focusRequester = focusRequester,
        enabled = enabled,
        onValueChanged = { value -> onValueChanged(value.text) },
        onKeyEvent = { event ->
            if (
                event.key.equals("Enter", ignoreCase = true) &&
                !event.alt &&
                !event.ctrl &&
                !event.shift &&
                input.value.text.trim().isNotEmpty()
            ) {
                onSubmitted()
                true
            } else {
                false
            }
        },
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
