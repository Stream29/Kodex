package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.RowScope
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.agent.contract.AgentExecutionState
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.cli.agent.AgentRuntimeControl
import io.github.stream29.kodex.cli.agent.runtimeControl
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiPopupSubmenuItem
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.openai.AgentMode
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.availableServiceTiers
import io.github.stream29.kodex.utils.terminaltext.takeLastFittingTerminalWidth
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

/** Target-scoped presentation state shared by triggers and host-level menus. */
@Stable
internal class RuntimeConfigurationDropdowns private constructor(
    val model: TuiDropdownState,
    val agentMode: TuiDropdownState,
    val requestUserInputMode: TuiDropdownState,
) {
    companion object {
        @Composable
        fun remember(owner: Any?): RuntimeConfigurationDropdowns = key(owner) {
            val model = rememberTuiDropdownState()
            val agentMode = rememberTuiDropdownState()
            val requestUserInputMode = rememberTuiDropdownState()
            remember(model, agentMode, requestUserInputMode) {
                RuntimeConfigurationDropdowns(model, agentMode, requestUserInputMode)
            }
        }
    }
}

@Composable
internal fun AgentRuntimeStatusBar(
    columns: Int,
    viewModel: AgentViewModel,
    execution: AgentExecutionState,
    settings: KodexAgentSettings,
    tokenCount: Long?,
    dropdowns: RuntimeConfigurationDropdowns,
    onBrowseWorkingDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(modifier = Modifier.width((columns - 1).coerceAtLeast(1))) {
        tokenCount?.let { Text("${it}t ") }
        val control = execution.runtimeControl()
        TuiButton(
            label = control.label(),
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionForeground,
            onClick = {
                when (control) {
                    AgentRuntimeControl.Stop -> viewModel.cancel()
                    AgentRuntimeControl.ClearPending -> viewModel.clearPending()
                    AgentRuntimeControl.Resume -> viewModel.resume()
                }
            },
        )
        Text(" ")
        if (compactVisible(execution)) {
            TuiButton(
                label = "Compact",
                modifier = Modifier.background(SessionButtonBackground),
                color = SessionForeground,
                enabled = execution.capabilities.canCompact,
                onClick = viewModel::forceCompact,
            )
            Text(" ")
        }
        RuntimeConfigurationTriggers(
            configuration = settings.configuration(),
            dropdowns = dropdowns,
        )
        StatusBarEndActions(
            columns = columns,
            workingDirectory = settings.cwd,
            workingDirectoryEnabled = true,
            onBrowseWorkingDirectory = onBrowseWorkingDirectory,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
internal fun NewSessionStatusBar(
    columns: Int,
    settings: KodexAgentSettings,
    dropdowns: RuntimeConfigurationDropdowns,
    onBrowseWorkingDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(modifier = Modifier.width((columns - 1).coerceAtLeast(1))) {
        RuntimeConfigurationTriggers(settings.configuration(), dropdowns)
        StatusBarEndActions(
            columns = columns,
            workingDirectory = settings.cwd,
            workingDirectoryEnabled = true,
            onBrowseWorkingDirectory = onBrowseWorkingDirectory,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
internal fun RuntimeConfigurationTriggers(
    configuration: RuntimeConfiguration,
    dropdowns: RuntimeConfigurationDropdowns,
    enabled: Boolean = true,
) {
    TuiDropdownTrigger(
        dropdownState = dropdowns.model,
        label = runtimeConfigurationLabel(
            model = configuration.model,
            reasoning = configuration.reasoning,
            tier = configuration.tier,
        ),
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionForeground,
        enabled = enabled,
    )
    Text(" ")
    TuiDropdownTrigger(
        dropdownState = dropdowns.requestUserInputMode,
        label = configuration.requestUserInputMode.displayName(),
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionForeground,
        enabled = enabled,
    )
    Text(" ")
    TuiDropdownTrigger(
        dropdownState = dropdowns.agentMode,
        label = configuration.agentMode.displayName(),
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionForeground,
        enabled = enabled,
    )
}

@Composable
private fun RowScope.StatusBarEndActions(
    columns: Int,
    workingDirectory: Path,
    workingDirectoryEnabled: Boolean,
    onBrowseWorkingDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Text(" ")
    WorkingDirectoryStatusButton(
        columns = columns,
        workingDirectory = workingDirectory,
        enabled = workingDirectoryEnabled,
        onBrowse = onBrowseWorkingDirectory,
    )
    Spacer(Modifier.width(1))
    Spacer(Modifier.weight(1f))
    TuiButton(
        label = "Settings",
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionForeground,
        onClick = onOpenSettings,
    )
}

@Composable
internal fun WorkingDirectoryStatusButton(
    columns: Int,
    workingDirectory: Path,
    enabled: Boolean,
    onBrowse: () -> Unit,
) {
    TuiButton(
        label = workingDirectoryStatusLabel(workingDirectory, columns),
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionForeground,
        enabled = enabled,
        onClick = onBrowse,
    )
}

@Composable
internal fun BoxScope.RuntimeConfigurationMenus(
    viewModel: AgentSettingsViewModel,
    settings: KodexAgentSettings,
    models: List<ModelInfo>,
    dropdowns: RuntimeConfigurationDropdowns,
) {
    val scope = rememberCoroutineScope()
    val configuration = settings.configuration()
    RuntimeConfigurationMenus(
        configuration = configuration,
        models = models,
        modelOptions = (models.map(ModelInfo::slug) + settings.model).distinct(),
        dropdowns = dropdowns,
        onConfigurationSelected = { model, effort, tier ->
            scope.launch {
                viewModel.updateModelConfiguration(model, effort, tier)
            }
        },
        onAgentModeSelected = { agentMode ->
            scope.launch { viewModel.updateAgentMode(agentMode) }
        },
        onRequestUserInputModeSelected = { mode ->
            scope.launch { viewModel.updateRequestUserInputMode(mode) }
        },
    )
}

@Composable
internal fun BoxScope.RuntimeConfigurationMenus(
    configuration: RuntimeConfiguration,
    models: List<ModelInfo>,
    modelOptions: List<OpenAiModelId>,
    dropdowns: RuntimeConfigurationDropdowns,
    onConfigurationSelected: (OpenAiModelId, ReasoningEffort, ServiceTier) -> Unit,
    onAgentModeSelected: (AgentMode) -> Unit,
    onRequestUserInputModeSelected: (RequestUserInputMode) -> Unit,
) {
    TuiDropdownMenu(
        dropdownState = dropdowns.model,
        backgroundColor = PopupMenuBackground,
    ) {
        modelOptions.forEach { model ->
            val modelInfo = models.firstOrNull { info -> info.slug == model }
            val efforts = modelInfo
                ?.supportedReasoningLevels
                ?.map { preset -> preset.effort }
                .orEmpty()
                .ifEmpty { listOf(configuration.reasoning) }
            val tiers = modelInfo
                ?.availableServiceTiers()
                .orEmpty()
                .ifEmpty { listOf(ServiceTier.Default) }
            TuiPopupSubmenuItem(
                key = model,
                selected = model == configuration.model,
                initialSubmenuFocusedKey = configuration.reasoning
                    .takeIf { model == configuration.model && it in efforts }
                    ?: efforts.first(),
                backgroundColor = PopupMenuBackground,
                submenuContent = {
                    efforts.forEach { effort ->
                        TuiPopupSubmenuItem(
                            key = effort,
                            selected = model == configuration.model &&
                                effort == configuration.reasoning,
                            initialSubmenuFocusedKey = configuration.tier
                                .takeIf {
                                    model == configuration.model &&
                                        effort == configuration.reasoning &&
                                        it in tiers
                                }
                                ?: ServiceTier.Default,
                            backgroundColor = PopupMenuBackground,
                            submenuContent = {
                                tiers.forEach { tier ->
                                    TuiPopupMenuItem(
                                        key = tier,
                                        selected = model == configuration.model &&
                                            effort == configuration.reasoning &&
                                            tier == configuration.tier,
                                        onClick = {
                                            onConfigurationSelected(model, effort, tier)
                                        },
                                    ) {
                                        Text(tier.displayName())
                                    }
                                }
                            },
                        ) {
                            Text(effort.displayName())
                        }
                    }
                },
            ) {
                Text(model.value)
            }
        }
    }
    TuiDropdownMenu(
        dropdownState = dropdowns.agentMode,
        options = AgentMode.entries.toList(),
        selected = configuration.agentMode,
        optionLabel = AgentMode::displayName,
        backgroundColor = PopupMenuBackground,
        onSelect = onAgentModeSelected,
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.requestUserInputMode,
        options = RequestUserInputMode.entries.toList(),
        selected = configuration.requestUserInputMode,
        optionLabel = RequestUserInputMode::displayName,
        backgroundColor = PopupMenuBackground,
        onSelect = onRequestUserInputModeSelected,
    )
}

internal data class RuntimeConfiguration(
    val model: OpenAiModelId,
    val reasoning: ReasoningEffort,
    val tier: ServiceTier,
    val agentMode: AgentMode,
    val requestUserInputMode: RequestUserInputMode,
)

private fun KodexAgentSettings.configuration(): RuntimeConfiguration = RuntimeConfiguration(
    model = model,
    reasoning = reasoning.effort,
    tier = serviceTier,
    agentMode = agentMode,
    requestUserInputMode = requestUserInputMode,
)

internal fun runtimeConfigurationLabel(
    model: OpenAiModelId,
    reasoning: ReasoningEffort,
    tier: ServiceTier,
): String = buildString {
    append(model.value)
    append(' ')
    append(reasoning.displayName())
    if (tier != ServiceTier.Default) {
        append(' ')
        append(tier.displayName())
    }
}

internal fun workingDirectoryStatusLabel(
    workingDirectory: Path,
    columns: Int,
): String {
    if (columns < WorkingDirectoryExpandedLabelMinimumColumns) return "cwd"
    val path = workingDirectory.toString()
    val maximumPathWidth = if (columns >= WorkingDirectoryWideLabelMinimumColumns) {
        WorkingDirectoryWidePathMaximumWidth
    } else {
        WorkingDirectoryPathMaximumWidth
    }
    val displayPath = if (path.terminalCellWidth() <= maximumPathWidth) {
        path
    } else {
        "…" + path.takeLastFittingTerminalWidth(maximumPathWidth - 1)
    }
    return displayPath
}

internal fun compactVisible(execution: AgentExecutionState): Boolean = !execution.running

private fun AgentRuntimeControl.label(): String = when (this) {
    AgentRuntimeControl.Stop -> "Stop"
    AgentRuntimeControl.ClearPending -> "Clear pending"
    AgentRuntimeControl.Resume -> "Resume"
}

private const val WorkingDirectoryExpandedLabelMinimumColumns: Int = 72
private const val WorkingDirectoryWideLabelMinimumColumns: Int = 112
private const val WorkingDirectoryPathMaximumWidth: Int = 16
private const val WorkingDirectoryWidePathMaximumWidth: Int = 28
