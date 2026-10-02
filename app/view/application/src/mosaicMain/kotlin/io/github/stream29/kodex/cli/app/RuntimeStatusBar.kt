package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.clipToBounds
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.cli.agent.canCompact
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfiguration
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationStatusItemsWithoutSpacing
import io.github.stream29.kodex.cli.runtimeconfiguration.runtimeConfigurationLabel
import io.github.stream29.kodex.cli.runtimeconfiguration.runtimeRequestUserInputModeLabel
import io.github.stream29.kodex.cli.agent.AgentRuntimeControl
import io.github.stream29.kodex.cli.agent.runtimeControl
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.utils.terminaltext.takeLastFittingTerminalWidth
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.io.files.Path

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

@Composable
internal fun NewSessionStatusBar(
    columns: Int,
    settings: KodexAgentSettings,
    runtimeConfiguration: RuntimeConfigurationViewModel,
    dropdowns: RuntimeConfigurationDropdowns,
    onBrowseWorkingDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    StatusBarLayout(
        columns = columns,
        regularContent = {
            RuntimeConfigurationStatusItemsWithoutSpacing(runtimeConfiguration, dropdowns)
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

@Composable
private fun StatusBarLayout(
    columns: Int,
    regularContent: @Composable () -> Unit,
    settingsContent: (@Composable () -> Unit)? = null,
) {
    val width = statusBarWidth(columns)
    Layout(
        content = {
            regularContent()
            settingsContent?.invoke()
        },
        // Sidebars can leave less space than one natural-width control. Keep both drawing and
        // hit testing inside this status bar instead of overflowing the terminal or adjacent pane.
        modifier = Modifier.width(width).clipToBounds(),
        debugInfo = { "StatusBarLayout(columns=$width)" },
    ) { measurables, constraints ->
        val childConstraints = Constraints(
            minWidth = 0,
            maxWidth = Constraints.Infinity,
            minHeight = 0,
            maxHeight = 1,
        )
        val placeables = measurables.map { measurable -> measurable.measure(childConstraints) }
        val settings = if (settingsContent != null) placeables.last() else null
        val regular = if (settings != null) placeables.dropLast(1) else placeables
        val plan = statusBarLayoutPlan(
            width = width,
            itemWidths = regular.map { placeable -> placeable.width },
            settingsWidth = settings?.width ?: 0,
        )
        layout(width = width, height = plan.rowCount) {
            regular.zip(plan.itemPositions).forEach { (placeable, position) ->
                placeable.place(position.x, position.y)
            }
            settings?.place(plan.settingsPosition.x, plan.settingsPosition.y)
        }
    }
}

@Composable
private fun SettingsStatusButton(onOpenSettings: () -> Unit) {
    TuiButton(
        label = SettingsLabel,
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionButtonForeground,
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
        color = SessionButtonForeground,
        enabled = enabled,
        onClick = onBrowse,
    )
}

internal fun agentRuntimeStatusBarRows(
    columns: Int,
    state: AgentStateValue,
    running: Boolean,
    settings: KodexAgentSettings,
    tokenCount: Long?,
): Int {
    val configuration = settings.configuration()
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

internal fun newSessionStatusBarRows(
    columns: Int,
    settings: KodexAgentSettings,
): Int {
    val widths = runtimeConfigurationButtonWidths(settings.configuration()) +
        buttonWidth(workingDirectoryStatusLabel(settings.cwd, columns))
    return statusBarLayoutPlan(
        width = statusBarWidth(columns),
        itemWidths = widths,
        settingsWidth = buttonWidth(SettingsLabel),
    ).rowCount
}

private fun runtimeConfigurationButtonWidths(
    configuration: RuntimeConfiguration,
): List<Int> = listOf(
    buttonWidth(
        runtimeConfigurationLabel(
            model = configuration.model,
            reasoning = configuration.reasoning,
            tier = configuration.tier,
        ),
    ),
    buttonWidth(runtimeRequestUserInputModeLabel(configuration.requestUserInputMode)),
)

private fun buttonWidth(label: String): Int = label.terminalCellWidth() + ButtonBorderColumns

private fun statusBarWidth(columns: Int): Int = (columns - 1).coerceAtLeast(1)

internal data class StatusBarLayoutPlan(
    val itemPositions: List<IntOffset>,
    val settingsPosition: IntOffset,
    val rowCount: Int,
)

internal fun statusBarLayoutPlan(
    width: Int,
    itemWidths: List<Int>,
    settingsWidth: Int,
): StatusBarLayoutPlan {
    require(width > 0) { "Status bar width must be positive." }
    require(settingsWidth >= 0) { "Settings width must be nonnegative." }
    require(itemWidths.all { itemWidth -> itemWidth > 0 }) {
        "Status bar item widths must be positive."
    }
    val settingsX = (width - settingsWidth).coerceAtLeast(0)
    val firstRowLimit = if (settingsWidth == 0) width else
        (settingsX - StatusBarItemSpacing).coerceAtLeast(0)
    var row = 0
    var rowWidth = 0
    val positions = buildList(itemWidths.size) {
        itemWidths.forEach { itemWidth ->
            while (true) {
                val rowLimit = if (row == 0) firstRowLimit else width
                val itemX = if (rowWidth == 0) 0 else rowWidth + StatusBarItemSpacing
                val fits = itemX + itemWidth <= rowLimit
                if (fits || (row > 0 && rowWidth == 0)) {
                    add(IntOffset(itemX, row))
                    rowWidth = itemX + itemWidth
                    break
                }
                row++
                rowWidth = 0
            }
        }
    }
    return StatusBarLayoutPlan(
        itemPositions = positions,
        settingsPosition = IntOffset(settingsX, 0),
        rowCount = maxOf(1, positions.maxOfOrNull(IntOffset::y)?.plus(1) ?: 1),
    )
}

private fun KodexAgentSettings.configuration(): RuntimeConfiguration = RuntimeConfiguration(
    model = model,
    reasoning = reasoning.effort,
    tier = serviceTier,
    requestUserInputMode = requestUserInputMode,
)

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

internal fun compactVisible(running: Boolean): Boolean = !running

private fun AgentRuntimeControl.label(): String = when (this) {
    AgentRuntimeControl.Stop -> "Stop"
    AgentRuntimeControl.ClearPending -> "Clear pending"
    AgentRuntimeControl.Resume -> "Resume"
}

private const val WorkingDirectoryExpandedLabelMinimumColumns: Int = 72
private const val WorkingDirectoryWideLabelMinimumColumns: Int = 112
private const val WorkingDirectoryPathMaximumWidth: Int = 16
private const val WorkingDirectoryWidePathMaximumWidth: Int = 28
private const val SettingsLabel: String = "Settings"
private const val ButtonBorderColumns: Int = 2
private const val StatusBarItemSpacing: Int = 1
