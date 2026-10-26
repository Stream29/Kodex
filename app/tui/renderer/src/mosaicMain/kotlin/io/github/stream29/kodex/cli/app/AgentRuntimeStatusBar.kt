package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Text as MosaicText
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfiguration
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationStatusItemsWithoutSpacing
import io.github.stream29.kodex.cli.runtimeconfiguration.runtimeConfigurationButtonWidths
import io.github.stream29.kodex.cli.agent.runtimeControl
import io.github.stream29.kodex.cli.agent.label
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
    AgentRuntimeStatusContent(
        viewModel, state, running, settings, tokenCount,
        MosaicAgentStatusRenderer(columns, dropdowns),
        onBrowseWorkingDirectory, onOpenSettings,
    )
}

internal class MosaicAgentStatusRenderer(
    private val columns: Int,
    private val dropdowns: RuntimeConfigurationDropdowns,
) : AgentStatusRenderer {
    @Composable
    override fun Frame(regular: @Composable () -> Unit, settings: @Composable () -> Unit) {
        StatusBarLayout(columns = columns, regularContent = regular, settingsContent = settings)
    }

    @Composable
    override fun Text(value: String) { MosaicText(value) }

    @Composable
    override fun Action(label: String, enabled: Boolean, onClick: () -> Unit) {
        TuiButton(
            label = label,
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionButtonForeground,
            enabled = enabled,
            onClick = onClick,
        )
    }

    @Composable
    override fun Configuration(viewModel: io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel) {
        RuntimeConfigurationStatusItemsWithoutSpacing(viewModel, dropdowns)
    }

    @Composable
    override fun Directory(directory: kotlinx.io.files.Path, onBrowse: () -> Unit) {
        WorkingDirectoryStatusButton(columns, directory, enabled = true, onBrowse = onBrowse)
    }

    @Composable
    override fun Settings(onOpen: () -> Unit) { SettingsStatusButton(onOpen) }
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
