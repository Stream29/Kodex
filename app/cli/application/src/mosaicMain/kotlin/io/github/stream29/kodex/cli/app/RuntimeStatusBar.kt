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
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.agent.AgentRuntimeControl
import io.github.stream29.kodex.cli.agent.AgentRuntimeViewModel
import io.github.stream29.kodex.cli.agent.AgentRuntimeViewState
import io.github.stream29.kodex.cli.agent.runtimeControl
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiPopupSubmenuItem
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.newsession.NewSessionViewModel
import io.github.stream29.kodex.cli.newsession.NewSessionViewState
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModeKind
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.coroutines.launch

/** Target-scoped presentation state shared by persistent triggers and host-level popup menus. */
@Stable
internal class RuntimeConfigurationDropdowns private constructor(
    val model: TuiDropdownState,
    val tier: TuiDropdownState,
    val mode: TuiDropdownState,
) {
    companion object {
        @Composable
        fun remember(owner: Any?): RuntimeConfigurationDropdowns = key(owner) {
            val model = rememberTuiDropdownState()
            val tier = rememberTuiDropdownState()
            val mode = rememberTuiDropdownState()
            remember(model, tier, mode) {
                RuntimeConfigurationDropdowns(model, tier, mode)
            }
        }
    }
}

@Composable
internal fun AgentRuntimeStatusBar(
    columns: Int,
    viewModel: AgentRuntimeViewModel,
    state: AgentRuntimeViewState,
    fallbackSettings: KodexNewSessionSettings,
    dropdowns: RuntimeConfigurationDropdowns,
    forkEnabled: Boolean,
    onFork: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val configuration = state.durable.settings?.configuration()
        ?: fallbackSettings.configuration()
    Row(modifier = Modifier.width((columns - 1).coerceAtLeast(1))) {
        state.durable.tokenCount?.let { tokenCount -> Text("${tokenCount}t ") }
        val control = state.runtimeControl()
        TuiButton(
            label = control.label(),
            onClick = {
                when (control) {
                    AgentRuntimeControl.Stop -> viewModel.cancel()
                    AgentRuntimeControl.ClearPending -> viewModel.clearPending()
                    AgentRuntimeControl.Resume -> viewModel.resume()
                }
            },
        )
        Text(" ")
        RuntimeConfigurationTriggers(configuration, dropdowns)
        Text(" ")
        TuiButton(
            label = "Settings",
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionForeground,
            onClick = onOpenSettings,
        )
        Text(" ")
        TuiButton(
            label = "Fork",
            enabled = forkEnabled,
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionForeground,
            onClick = onFork,
        )
        state.failureMessage?.let { failure -> Text(" [error] $failure") }
    }
}

@Composable
internal fun NewSessionStatusBar(
    columns: Int,
    state: NewSessionViewState,
    dropdowns: RuntimeConfigurationDropdowns,
    onOpenSettings: () -> Unit,
) {
    Row(modifier = Modifier.width((columns - 1).coerceAtLeast(1))) {
        RuntimeConfigurationTriggers(state.settings.configuration(), dropdowns)
        Text(" ")
        TuiButton(
            label = "Settings",
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionForeground,
            onClick = onOpenSettings,
        )
        state.failureMessage?.let { failure -> Text(" [notice] $failure") }
    }
}

@Composable
internal fun BoxScope.AgentRuntimeStatusMenus(
    viewModel: AgentRuntimeViewModel,
    state: AgentRuntimeViewState,
    fallbackSettings: KodexNewSessionSettings,
    models: List<ModelInfo>,
    modelOptions: List<OpenAiModelId>,
    dropdowns: RuntimeConfigurationDropdowns,
) {
    val configuration = state.durable.settings?.configuration()
        ?: fallbackSettings.configuration()
    val scope = rememberCoroutineScope()
    RuntimeConfigurationMenus(
        configuration = configuration,
        models = models,
        modelOptions = modelOptions,
        dropdowns = dropdowns,
        onModelSelected = { model, effort ->
            scope.launch {
                viewModel.updateSettings { current ->
                    current.copy(
                        model = model,
                        reasoning = current.reasoning.copy(effort = effort),
                    )
                }
            }
        },
        onTierSelected = { tier ->
            scope.launch {
                viewModel.updateSettings { current -> current.copy(serviceTier = tier) }
            }
        },
        onModeSelected = { mode ->
            scope.launch {
                viewModel.updateSettings { current -> current.copy(collaborationMode = mode) }
            }
        },
    )
}

@Composable
internal fun BoxScope.NewSessionStatusMenus(
    viewModel: NewSessionViewModel,
    state: NewSessionViewState,
    models: List<ModelInfo>,
    modelOptions: List<OpenAiModelId>,
    dropdowns: RuntimeConfigurationDropdowns,
) {
    val scope = rememberCoroutineScope()
    RuntimeConfigurationMenus(
        configuration = state.settings.configuration(),
        models = models,
        modelOptions = modelOptions,
        dropdowns = dropdowns,
        onModelSelected = { model, effort ->
            scope.launch {
                viewModel.updateSettings { current ->
                    current.copy(model = model, reasoningEffort = effort)
                }
            }
        },
        onTierSelected = { tier ->
            scope.launch {
                viewModel.updateSettings { current -> current.copy(serviceTier = tier) }
            }
        },
        onModeSelected = { mode ->
            scope.launch {
                viewModel.updateSettings { current -> current.copy(mode = mode) }
            }
        },
    )
}

@Composable
private fun RuntimeConfigurationTriggers(
    configuration: RuntimeConfiguration,
    dropdowns: RuntimeConfigurationDropdowns,
) {
    TuiDropdownTrigger(
        dropdownState = dropdowns.model,
        label = "${configuration.model.value} ${configuration.reasoning.displayName()}",
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionForeground,
    )
    Text(" ")
    TuiDropdownTrigger(
        dropdownState = dropdowns.tier,
        label = "tier: ${configuration.tier.displayName()}",
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionForeground,
    )
    Text(" ")
    TuiDropdownTrigger(
        dropdownState = dropdowns.mode,
        label = "${configuration.mode.displayName()} mode",
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionForeground,
    )
}

@Composable
private fun BoxScope.RuntimeConfigurationMenus(
    configuration: RuntimeConfiguration,
    models: List<ModelInfo>,
    modelOptions: List<OpenAiModelId>,
    dropdowns: RuntimeConfigurationDropdowns,
    onModelSelected: (OpenAiModelId, ReasoningEffort) -> Unit,
    onTierSelected: (ServiceTier) -> Unit,
    onModeSelected: (ModeKind) -> Unit,
) {
    TuiDropdownMenu(
        dropdownState = dropdowns.model,
        backgroundColor = PopupMenuBackground,
    ) {
        modelOptions.forEach { model ->
            val efforts = models
                .firstOrNull { info -> info.slug == model }
                ?.supportedReasoningLevels
                ?.map { preset -> preset.effort }
                .orEmpty()
                .ifEmpty { listOf(configuration.reasoning) }
            if (efforts.size == 1) {
                val effort = efforts.single()
                TuiPopupMenuItem(
                    key = model,
                    selected = model == configuration.model,
                    onClick = { onModelSelected(model, effort) },
                ) {
                    Text(model.value)
                }
            } else {
                TuiPopupSubmenuItem(
                    key = model,
                    selected = model == configuration.model,
                    initialSubmenuFocusedKey = configuration.reasoning,
                    submenuContent = {
                        efforts.forEach { effort ->
                            TuiPopupMenuItem(
                                key = effort,
                                selected = model == configuration.model &&
                                    effort == configuration.reasoning,
                                onClick = { onModelSelected(model, effort) },
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
    }
    TuiDropdownMenu(
        dropdownState = dropdowns.tier,
        options = ServiceTier.entries.toList(),
        selected = configuration.tier,
        optionLabel = ServiceTier::displayName,
        backgroundColor = PopupMenuBackground,
        onSelect = onTierSelected,
    )
    TuiDropdownMenu(
        dropdownState = dropdowns.mode,
        options = ModeKind.entries.toList(),
        selected = configuration.mode,
        optionLabel = { mode -> "${mode.displayName()} mode" },
        backgroundColor = PopupMenuBackground,
        onSelect = onModeSelected,
    )
}

private data class RuntimeConfiguration(
    val model: OpenAiModelId,
    val reasoning: ReasoningEffort,
    val tier: ServiceTier,
    val mode: ModeKind,
)

private fun KodexAgentSettings.configuration(): RuntimeConfiguration = RuntimeConfiguration(
    model = model,
    reasoning = reasoning.effort,
    tier = serviceTier,
    mode = collaborationMode,
)

private fun KodexNewSessionSettings.configuration(): RuntimeConfiguration = RuntimeConfiguration(
    model = model,
    reasoning = reasoningEffort,
    tier = serviceTier,
    mode = mode,
)

private fun AgentRuntimeControl.label(): String = when (this) {
    AgentRuntimeControl.Stop -> "Stop"
    AgentRuntimeControl.ClearPending -> "Clear pending"
    AgentRuntimeControl.Resume -> "Resume"
}
