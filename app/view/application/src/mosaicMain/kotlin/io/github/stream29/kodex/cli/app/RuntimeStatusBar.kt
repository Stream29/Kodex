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
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.Constraints
import com.jakewharton.mosaic.ui.unit.IntOffset
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
    StatusBarLayout(
        columns = columns,
        regularContent = {
            tokenCount?.let { Text("${it}t") }
            val control = execution.runtimeControl()
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
            if (compactVisible(execution)) {
                TuiButton(
                    label = "Compact",
                    modifier = Modifier.background(SessionButtonBackground),
                    color = SessionButtonForeground,
                    enabled = execution.capabilities.canCompact,
                    onClick = viewModel::forceCompact,
                )
            }
            RuntimeConfigurationStatusItemsWithoutSpacing(
                configuration = settings.configuration(),
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
    dropdowns: RuntimeConfigurationDropdowns,
    onBrowseWorkingDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    StatusBarLayout(
        columns = columns,
        regularContent = {
            RuntimeConfigurationStatusItemsWithoutSpacing(settings.configuration(), dropdowns)
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
    settingsContent: @Composable () -> Unit,
) {
    val width = statusBarWidth(columns)
    Layout(
        content = {
            regularContent()
            settingsContent()
        },
        modifier = Modifier.width(width),
        debugInfo = { "StatusBarLayout(columns=$width)" },
    ) { measurables, constraints ->
        check(measurables.isNotEmpty()) {
            "StatusBarLayout requires a trailing Settings item."
        }
        val childConstraints = Constraints(
            minWidth = 0,
            maxWidth = Constraints.Infinity,
            minHeight = 0,
            maxHeight = 1,
        )
        val placeables = measurables.map { measurable -> measurable.measure(childConstraints) }
        val settings = placeables.last()
        val regular = placeables.dropLast(1)
        val plan = statusBarLayoutPlan(
            width = width,
            itemWidths = regular.map { placeable -> placeable.width },
            settingsWidth = settings.width,
        )
        layout(width = width, height = plan.rowCount) {
            regular.zip(plan.itemPositions).forEach { (placeable, position) ->
                placeable.place(position.x, position.y)
            }
            settings.place(plan.settingsPosition.x, plan.settingsPosition.y)
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
internal fun RuntimeConfigurationTriggers(
    configuration: RuntimeConfiguration,
    dropdowns: RuntimeConfigurationDropdowns,
    enabled: Boolean = true,
) {
    Row {
        RuntimeConfigurationStatusItems(configuration, dropdowns, enabled)
    }
}

@Composable
private fun RuntimeConfigurationStatusItems(
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
        color = SessionButtonForeground,
        enabled = enabled,
    )
    Text(" ")
    TuiDropdownTrigger(
        dropdownState = dropdowns.requestUserInputMode,
        label = configuration.requestUserInputMode.displayName(),
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionButtonForeground,
        enabled = enabled,
    )
    Text(" ")
    TuiDropdownTrigger(
        dropdownState = dropdowns.agentMode,
        label = configuration.agentMode.displayName(),
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionButtonForeground,
        enabled = enabled,
    )
}

@Composable
private fun RuntimeConfigurationStatusItemsWithoutSpacing(
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
        color = SessionButtonForeground,
        enabled = enabled,
    )
    TuiDropdownTrigger(
        dropdownState = dropdowns.requestUserInputMode,
        label = configuration.requestUserInputMode.displayName(),
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionButtonForeground,
        enabled = enabled,
    )
    TuiDropdownTrigger(
        dropdownState = dropdowns.agentMode,
        label = configuration.agentMode.displayName(),
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionButtonForeground,
        enabled = enabled,
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
    execution: AgentExecutionState,
    settings: KodexAgentSettings,
    tokenCount: Long?,
): Int {
    val configuration = settings.configuration()
    val widths = buildList {
        tokenCount?.let { add("${it}t".terminalCellWidth()) }
        add(buttonWidth(execution.runtimeControl().label()))
        if (compactVisible(execution)) add(buttonWidth("Compact"))
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
    buttonWidth(configuration.requestUserInputMode.displayName()),
    buttonWidth(configuration.agentMode.displayName()),
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
    require(settingsWidth > 0) { "Settings width must be positive." }
    require(itemWidths.all { itemWidth -> itemWidth > 0 }) {
        "Status bar item widths must be positive."
    }
    val settingsX = (width - settingsWidth).coerceAtLeast(0)
    val firstRowLimit = (settingsX - StatusBarItemSpacing).coerceAtLeast(0)
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
    val popupMenuBackground = PopupMenuBackground
    TuiDropdownMenu(
        dropdownState = dropdowns.model,
        backgroundColor = popupMenuBackground,
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
                backgroundColor = popupMenuBackground,
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
                            backgroundColor = popupMenuBackground,
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
        backgroundColor = popupMenuBackground,
        onSelect = onAgentModeSelected,
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.requestUserInputMode,
        options = RequestUserInputMode.entries.toList(),
        selected = configuration.requestUserInputMode,
        optionLabel = RequestUserInputMode::displayName,
        backgroundColor = popupMenuBackground,
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
private const val SettingsLabel: String = "Settings"
private const val ButtonBorderColumns: Int = 2
private const val StatusBarItemSpacing: Int = 1
