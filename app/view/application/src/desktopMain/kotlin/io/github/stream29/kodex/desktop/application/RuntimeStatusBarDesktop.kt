package io.github.stream29.kodex.desktop.application

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.agent.contract.AgentExecutionState
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.cli.agent.AgentRuntimeControl
import io.github.stream29.kodex.cli.agent.runtimeControl
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModeKind
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.availableServiceTiers
import kotlinx.coroutines.launch

/** Bottom status row for one materialized Agent. */
@Composable
internal fun AgentRuntimeStatusBarDesktop(
    viewModel: AgentViewModel,
    onBrowseWorkingDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
): Unit {
    val execution by viewModel.execution.collectAsState()
    val tokenCount by viewModel.tokenCount.collectAsState()
    RuntimeStatusBarDesktop(
        owner = viewModel,
        execution = execution,
        tokenCount = tokenCount,
        onBrowseWorkingDirectory = onBrowseWorkingDirectory,
        onOpenSettings = onOpenSettings,
    )
}

/** Bottom status row for a process-local New Session. */
@Composable
internal fun NewSessionStatusBarDesktop(
    viewModel: AgentSettingsViewModel,
    onBrowseWorkingDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
): Unit {
    RuntimeStatusBarDesktop(
        owner = viewModel,
        execution = null,
        tokenCount = null,
        onBrowseWorkingDirectory = onBrowseWorkingDirectory,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
private fun RuntimeStatusBarDesktop(
    owner: AgentSettingsViewModel,
    execution: AgentExecutionState?,
    tokenCount: Long?,
    onBrowseWorkingDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
): Unit {
    val settings by owner.settings.collectAsState()
    val models by owner.models.collectAsState()
    val scope = rememberCoroutineScope()

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RectangleShape,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
        ) {
            val compactWorkingDirectoryLabel = maxWidth < 680.dp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tokenCount?.let { count ->
                        Text(
                            text = "${count}t",
                            modifier = Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    execution?.let { state ->
                        AgentControlButton(
                            viewModel = owner as AgentViewModel,
                            execution = state,
                        )
                        if (!state.running) {
                            StatusButton(
                                label = "Compact",
                                enabled = state.capabilities.canCompact,
                                onClick = owner::forceCompact,
                            )
                        }
                    }
                    RuntimeConfigurationButton(
                        settings = settings,
                        models = models,
                        onSelect = { model, effort, tier ->
                            scope.launch {
                                owner.updateModelConfiguration(model, effort, tier)
                            }
                        },
                    )
                    RuntimeModeButton(
                        selected = settings.collaborationMode,
                        onSelect = { mode -> scope.launch { owner.updateMode(mode) } },
                    )
                    StatusButton(
                        label = if (compactWorkingDirectoryLabel) {
                            "cwd"
                        } else {
                            settings.cwd.toString()
                        },
                        onClick = onBrowseWorkingDirectory,
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
                StatusButton(label = "Settings", onClick = onOpenSettings)
            }
        }
    }
}

@Composable
private fun AgentControlButton(
    viewModel: AgentViewModel,
    execution: AgentExecutionState,
): Unit {
    val control = execution.runtimeControl()
    StatusButton(
        label = when (control) {
            AgentRuntimeControl.Stop -> "Stop"
            AgentRuntimeControl.ClearPending -> "Clear pending"
            AgentRuntimeControl.Resume -> "Resume"
        },
        enabled = when (control) {
            AgentRuntimeControl.Stop -> execution.capabilities.canCancel
            AgentRuntimeControl.ClearPending -> execution.capabilities.canClearPending
            AgentRuntimeControl.Resume -> execution.capabilities.canResume
        },
        onClick = {
            when (control) {
                AgentRuntimeControl.Stop -> viewModel.cancel()
                AgentRuntimeControl.ClearPending -> viewModel.clearPending()
                AgentRuntimeControl.Resume -> viewModel.resume()
            }
        },
    )
}

@Composable
private fun StatusButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
): Unit {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun RuntimeConfigurationButton(
    settings: KodexAgentSettings,
    models: List<ModelInfo>,
    onSelect: (OpenAiModelId, ReasoningEffort, ServiceTier) -> Unit,
): Unit {
    var expanded by remember { mutableStateOf(false) }
    Box {
        StatusButton(
            label = buildString {
                append(settings.model.value)
                append(" ")
                append(settings.reasoning.effort.desktopLabel())
                if (settings.serviceTier != ServiceTier.Default) {
                    append(" ")
                    append(settings.serviceTier.desktopLabel())
                }
            },
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            val modelOptions = (models.map(ModelInfo::slug) + settings.model).distinct()
            modelOptions.forEach { model ->
                val info = models.firstOrNull { it.slug == model }
                val efforts = info
                    ?.supportedReasoningLevels
                    ?.map { it.effort }
                    .orEmpty()
                    .ifEmpty { listOf(settings.reasoning.effort) }
                val tiers = info
                    ?.availableServiceTiers()
                    .orEmpty()
                    .ifEmpty { listOf(ServiceTier.Default) }
                efforts.forEach { effort ->
                    tiers.forEach { tier ->
                        val selected =
                            model == settings.model &&
                                effort == settings.reasoning.effort &&
                                tier == settings.serviceTier
                        DropdownMenuItem(
                            text = {
                                Text(
                                    buildString {
                                        if (selected) append("✓ ")
                                        append(model.value)
                                        append(" ")
                                        append(effort.desktopLabel())
                                        if (tier != ServiceTier.Default) {
                                            append(" ")
                                            append(tier.desktopLabel())
                                        }
                                    },
                                )
                            },
                            onClick = {
                                expanded = false
                                onSelect(model, effort, tier)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeModeButton(
    selected: ModeKind,
    onSelect: (ModeKind) -> Unit,
): Unit {
    var expanded by remember { mutableStateOf(false) }
    Box {
        StatusButton(
            label = selected.desktopLabel(),
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ModeKind.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (mode == selected) {
                                "✓ ${mode.desktopLabel()}"
                            } else {
                                mode.desktopLabel()
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    },
                )
            }
        }
    }
}

private fun ReasoningEffort.desktopLabel(): String = wireName

private fun ServiceTier.desktopLabel(): String = name.lowercase()

private fun ModeKind.desktopLabel(): String = when (this) {
    ModeKind.Default -> "build"
    ModeKind.Plan -> "plan"
}
