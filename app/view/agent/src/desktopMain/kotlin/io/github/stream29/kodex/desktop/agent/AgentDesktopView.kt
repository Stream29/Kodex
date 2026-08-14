package io.github.stream29.kodex.desktop.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.agent.contract.RequestUserInputDraftAnswer
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputSubmissionState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModel
import io.github.stream29.kodex.app.agent.contract.allowsOtherAnswer
import io.github.stream29.kodex.cli.agent.AgentRuntimeControl
import io.github.stream29.kodex.cli.agent.runtimeControl
import io.github.stream29.kodex.openai.ModeKind
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.availableServiceTiers
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import kotlinx.coroutines.launch

/** Material 3 Desktop form for the active Agent's blocking input request. */
@Composable
public fun RequestUserInputDesktopPanel(
    viewModel: RequestUserInputViewModel,
    modifier: Modifier = Modifier,
): Unit {
    val state by viewModel.state.collectAsState()
    val pending = state as? RequestUserInputState.Pending ?: return
    val scope = rememberCoroutineScope()
    val submitting = pending.submission is RequestUserInputSubmissionState.Submitting

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RectangleShape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Input requested",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            pending.arguments.questions.forEach { question ->
                RequestUserInputQuestionView(
                    callId = pending.callId,
                    question = question,
                    state = pending,
                    enabled = !submitting,
                    onSelectOption = viewModel::selectOption,
                    onSelectOther = viewModel::selectOther,
                    onFreeFormChanged = viewModel::updateFreeForm,
                )
            }
            (pending.submission as? RequestUserInputSubmissionState.Failed)?.let { failure ->
                Text(
                    text = "Unable to submit: ${failure.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = {
                    scope.launch { viewModel.submit(pending.callId, pending.revision) }
                },
                enabled = pending.canSubmit,
            ) {
                Text(if (submitting) "Submitting…" else "Submit")
            }
        }
    }
}

@Composable
private fun RequestUserInputQuestionView(
    callId: String,
    question: RequestUserInputQuestion,
    state: RequestUserInputState.Pending,
    enabled: Boolean,
    onSelectOption: (String, String, String) -> Boolean,
    onSelectOther: (String, String) -> Boolean,
    onFreeFormChanged: (String, String, String) -> Boolean,
): Unit {
    val options = question.options.orEmpty()
    val answer = state.answers[question.id]
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = "${question.header}: ${question.question}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (options.isNotEmpty()) {
            options.forEach { option ->
                val selected =
                    (answer as? RequestUserInputDraftAnswer.Option)?.label == option.label
                FilterChip(
                    selected = selected,
                    onClick = {
                        onSelectOption(callId, question.id, option.label)
                    },
                    enabled = enabled,
                    label = { Text(option.label) },
                )
                option.description.takeIf(String::isNotBlank)?.let { description ->
                    Text(
                        text = "  $description",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (question.allowsOtherAnswer) {
                FilterChip(
                    selected = answer is RequestUserInputDraftAnswer.FreeForm,
                    onClick = { onSelectOther(callId, question.id) },
                    enabled = enabled,
                    label = { Text("Other") },
                )
            }
        }
        if (options.isEmpty() || answer is RequestUserInputDraftAnswer.FreeForm) {
            OutlinedTextField(
                value = (answer as? RequestUserInputDraftAnswer.FreeForm)?.text.orEmpty(),
                onValueChange = { text ->
                    onFreeFormChanged(callId, question.id, text)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                prefix = { Text(">") },
                minLines = 1,
                maxLines = 4,
            )
        }
    }
}

/** Runtime and configuration controls bound directly to one exact Agent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun AgentRuntimeActionsDesktopRow(
    viewModel: AgentViewModel,
    onOpenWorkingDirectory: (AgentSettingsViewModel) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
): Unit {
    val settings by viewModel.settings.collectAsState()
    val execution by viewModel.execution.collectAsState()
    val models by viewModel.models.collectAsState()
    val tokenCount by viewModel.tokenCount.collectAsState()
    val scope = rememberCoroutineScope()
    var configurationMenuOpen by remember { mutableStateOf(false) }
    var modeMenuOpen by remember { mutableStateOf(false) }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        tokenCount?.let { count ->
            AssistChip(onClick = {}, enabled = false, label = { Text("$count tokens") })
        }
        val control = execution.runtimeControl()
        Button(
            onClick = {
                when (control) {
                    AgentRuntimeControl.Stop -> viewModel.cancel()
                    AgentRuntimeControl.ClearPending -> viewModel.clearPending()
                    AgentRuntimeControl.Resume -> viewModel.resume()
                }
            },
            enabled = when (control) {
                AgentRuntimeControl.Stop -> execution.capabilities.canCancel
                AgentRuntimeControl.ClearPending -> execution.capabilities.canClearPending
                AgentRuntimeControl.Resume -> execution.capabilities.canResume
            },
        ) {
            Text(
                when (control) {
                    AgentRuntimeControl.Stop -> "Stop"
                    AgentRuntimeControl.ClearPending -> "Clear pending"
                    AgentRuntimeControl.Resume -> "Resume"
                },
            )
        }
        if (!execution.running) {
            OutlinedButton(
                onClick = viewModel::forceCompact,
                enabled = execution.capabilities.canCompact,
            ) {
                Text("Compact")
            }
        }
        Column {
            OutlinedButton(onClick = { configurationMenuOpen = true }) {
                Text(
                    buildString {
                        append(settings.model.value)
                        append(" · ")
                        append(settings.reasoning.effort.displayName())
                        if (settings.serviceTier != ServiceTier.Default) {
                            append(" · ")
                            append(settings.serviceTier.displayName())
                        }
                    },
                )
            }
            RuntimeConfigurationMenu(
                expanded = configurationMenuOpen,
                currentModel = settings.model,
                currentEffort = settings.reasoning.effort,
                currentTier = settings.serviceTier,
                models = models,
                onDismissRequest = { configurationMenuOpen = false },
                onSelected = { model, effort, tier ->
                    configurationMenuOpen = false
                    scope.launch {
                        viewModel.updateModelConfiguration(model, effort, tier)
                    }
                },
            )
        }
        Column {
            OutlinedButton(onClick = { modeMenuOpen = true }) {
                Text(settings.collaborationMode.displayName())
            }
            DropdownMenu(
                expanded = modeMenuOpen,
                onDismissRequest = { modeMenuOpen = false },
            ) {
                ModeKind.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.displayName()) },
                        trailingIcon = {
                            RadioButton(
                                selected = mode == settings.collaborationMode,
                                onClick = null,
                            )
                        },
                        onClick = {
                            modeMenuOpen = false
                            scope.launch { viewModel.updateMode(mode) }
                        },
                    )
                }
            }
        }
        TextButton(onClick = { onOpenWorkingDirectory(viewModel) }) {
            Text("cwd: ${settings.cwd}")
        }
        TextButton(onClick = onOpenSettings) {
            Text("Settings")
        }
    }
}

@Composable
private fun RuntimeConfigurationMenu(
    expanded: Boolean,
    currentModel: OpenAiModelId,
    currentEffort: ReasoningEffort,
    currentTier: ServiceTier,
    models: List<ModelInfo>,
    onDismissRequest: () -> Unit,
    onSelected: (OpenAiModelId, ReasoningEffort, ServiceTier) -> Unit,
): Unit {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        val modelOptions = (models.map(ModelInfo::slug) + currentModel).distinct()
        modelOptions.forEach { model ->
            val info = models.firstOrNull { candidate -> candidate.slug == model }
            val efforts = info
                ?.supportedReasoningLevels
                ?.map { preset -> preset.effort }
                .orEmpty()
                .ifEmpty { listOf(currentEffort) }
            val tiers = info
                ?.availableServiceTiers()
                .orEmpty()
                .ifEmpty { listOf(ServiceTier.Default) }
            efforts.forEach { effort ->
                tiers.forEach { tier ->
                    val selected =
                        model == currentModel && effort == currentEffort && tier == currentTier
                    DropdownMenuItem(
                        text = {
                            Text(
                                "${model.value} · ${effort.displayName()}" +
                                    if (tier == ServiceTier.Default) {
                                        ""
                                    } else {
                                        " · ${tier.displayName()}"
                                    },
                            )
                        },
                        trailingIcon = {
                            RadioButton(selected = selected, onClick = null)
                        },
                        onClick = { onSelected(model, effort, tier) },
                    )
                }
            }
        }
    }
}

private fun ModeKind.displayName(): String = when (this) {
    ModeKind.Default -> "Build"
    ModeKind.Plan -> "Plan"
}

private fun ReasoningEffort.displayName(): String = wireName.replaceFirstChar(Char::uppercase)

private fun ServiceTier.displayName(): String = when (this) {
    ServiceTier.Default -> "Default"
    ServiceTier.Fast -> "Fast"
    ServiceTier.Flex -> "Flex"
}
