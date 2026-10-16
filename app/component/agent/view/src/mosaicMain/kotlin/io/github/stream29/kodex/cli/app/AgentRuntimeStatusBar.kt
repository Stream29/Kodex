package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.cli.agent.canCompact
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfiguration
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationStatusItemsWithoutSpacing
import io.github.stream29.kodex.cli.runtimeconfiguration.runtimeConfigurationButtonWidths
import io.github.stream29.kodex.cli.agent.AgentRuntimeControl
import io.github.stream29.kodex.cli.agent.runtimeControl
import io.github.stream29.kodex.cli.components.SessionButtonBackground
import io.github.stream29.kodex.cli.components.SessionButtonForeground
import io.github.stream29.kodex.cli.components.SettingsLabel
import io.github.stream29.kodex.cli.components.SettingsStatusButton
import io.github.stream29.kodex.cli.components.StatusBarLayout
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.WorkingDirectoryStatusButton
import io.github.stream29.kodex.cli.components.buttonWidth
import io.github.stream29.kodex.cli.components.statusBarLayoutPlan
import io.github.stream29.kodex.cli.components.statusBarWidth
import io.github.stream29.kodex.cli.components.workingDirectoryStatusLabel
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth

@Composable
internal fun AgentRuntimeStatusBar(
    columns: Int,
    viewModel: AgentViewModel,
    state: AgentStateValue,
    running: Boolean,
    settings: KodexAgentSettings,
    tokenCount: Long?,
    dropdowns: RuntimeConfigurationDropdowns,
    onBrowseWorkingDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    StatusBarLayout(
        columns = columns,
        regularContent = {
            tokenCount?.let { Text("${it}t") }
            val control = state.runtimeControl(running)
            TuiButton(
                label = control.label(),
                modifier = Modifier.background(SessionButtonBackground),
                color = SessionButtonForeground,
                onClick = {
                    when (control) {
                        AgentRuntimeControl.Stop -> viewModel.cancel()
                        AgentRuntimeControl.ClearPending -> viewModel.clearPending()
                        AgentRuntimeControl.Resume -> viewModel.resume()
                    }
                },
            )
            if (compactVisible(running)) {
                TuiButton(
                    label = "Compact",
                    modifier = Modifier.background(SessionButtonBackground),
                    color = SessionButtonForeground,
                    enabled = state.canCompact(running),
                    onClick = viewModel::forceCompact,
                )
            }
            RuntimeConfigurationStatusItemsWithoutSpacing(
                viewModel = viewModel.runtimeConfiguration,
                dropdowns = dropdowns,
            )
            WorkingDirectoryStatusButton(
                columns = columns,
                workingDirectory = settings.cwd,
                enabled = true,
                onBrowse = onBrowseWorkingDirectory,
            )
        },
        settingsContent = {
            SettingsStatusButton(onOpenSettings)
        },
    )
}

internal fun agentRuntimeStatusBarRows(
    columns: Int,
    state: AgentStateValue,
    running: Boolean,
    settings: KodexAgentSettings,
    tokenCount: Long?,
): Int {
    val configuration = RuntimeConfiguration(
        model = settings.model,
        reasoning = settings.reasoning.effort,
        tier = settings.serviceTier,
        requestUserInputMode = settings.requestUserInputMode,
    )
    val widths = buildList {
        tokenCount?.let { add("${it}t".terminalCellWidth()) }
        add(buttonWidth(state.runtimeControl(running).label()))
        if (compactVisible(running)) add(buttonWidth("Compact"))
        addAll(runtimeConfigurationButtonWidths(configuration))
        add(buttonWidth(workingDirectoryStatusLabel(settings.cwd, columns)))
    }
    return statusBarLayoutPlan(
        width = statusBarWidth(columns),
        itemWidths = widths,
        settingsWidth = buttonWidth(SettingsLabel),
    ).rowCount
}

internal fun compactVisible(running: Boolean): Boolean = !running

private fun AgentRuntimeControl.label(): String = when (this) {
    AgentRuntimeControl.Stop -> "Stop"
    AgentRuntimeControl.ClearPending -> "Clear pending"
    AgentRuntimeControl.Resume -> "Resume"
}
