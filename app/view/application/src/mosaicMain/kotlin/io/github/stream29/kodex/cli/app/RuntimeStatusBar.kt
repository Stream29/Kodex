package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.RowScope
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.agent.contract.AgentExecutionState
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.cli.agent.AgentRuntimeControl
import io.github.stream29.kodex.cli.agent.runtimeControl
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModeKind
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.terminaltext.takeLastFittingTerminalWidth
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

@Composable
internal fun AgentRuntimeStatusBar(
    columns: Int,
    viewModel: AgentViewModel,
    execution: AgentExecutionState,
    settings: KodexAgentSettings,
    tokenCount: Long?,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
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
                    AgentRuntimeControl.ClearPending ->
                        scope.launch { viewModel.clearPending() }
                    AgentRuntimeControl.Resume ->
                        scope.launch { viewModel.resume() }
                }
            },
        )
        Text(" ")
        TuiButton(
            label = "Compact",
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionForeground,
            enabled = execution.capabilities.canCompact,
            onClick = { scope.launch { viewModel.forceCompact() } },
        )
        Text(" ")
        RuntimeConfigurationLabel(settings)
        StatusBarEndActions(
            columns = columns,
            workingDirectory = settings.cwd,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
internal fun NewSessionStatusBar(
    columns: Int,
    viewModel: NewSessionViewModel,
    settings: KodexAgentSettings,
    onOpenSettings: () -> Unit,
) {
    Row(modifier = Modifier.width((columns - 1).coerceAtLeast(1))) {
        RuntimeConfigurationLabel(settings)
        StatusBarEndActions(
            columns = columns,
            workingDirectory = settings.cwd,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun RuntimeConfigurationLabel(settings: KodexAgentSettings) {
    Text(
        runtimeConfigurationLabel(
            model = settings.model,
            reasoning = settings.reasoning.effort,
            tier = settings.serviceTier,
        ),
    )
    Text(" · ${settings.collaborationMode.displayName()}")
}

@Composable
private fun RowScope.StatusBarEndActions(
    columns: Int,
    workingDirectory: Path,
    onOpenSettings: () -> Unit,
) {
    Text(" ")
    Text(workingDirectoryStatusLabel(workingDirectory, columns))
    Spacer(Modifier.width(1))
    Spacer(Modifier.weight(1f))
    TuiButton(
        label = "Settings",
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionForeground,
        onClick = onOpenSettings,
    )
}

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
    return "cwd: $displayPath"
}

private fun AgentRuntimeControl.label(): String = when (this) {
    AgentRuntimeControl.Stop -> "Stop"
    AgentRuntimeControl.ClearPending -> "Clear pending"
    AgentRuntimeControl.Resume -> "Resume"
}

private const val WorkingDirectoryExpandedLabelMinimumColumns: Int = 72
private const val WorkingDirectoryWideLabelMinimumColumns: Int = 112
private const val WorkingDirectoryPathMaximumWidth: Int = 16
private const val WorkingDirectoryWidePathMaximumWidth: Int = 28
