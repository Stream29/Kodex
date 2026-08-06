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
import io.github.stream29.kodex.agentstate.contract.canCompact
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
import io.github.stream29.kodex.openai.availableServiceTiers
import kotlinx.coroutines.launch

/** Target-scoped presentation state shared by persistent triggers and host-level popup menus. */
@Stable
internal class RuntimeConfigurationDropdowns private constructor(
    val model: TuiDropdownState,
    val mode: TuiDropdownState,
) {
    companion object {
        @Composable
        fun remember(owner: Any?): RuntimeConfigurationDropdowns = key(owner) {
            val model = rememberTuiDropdownState()
            val mode = rememberTuiDropdownState()
            remember(model, mode) {
                RuntimeConfigurationDropdowns(model, mode)
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
    onOpenSettings: () -> Unit,
) {
    val configuration = state.durable.settings?.configuration()
        ?: fallbackSettings.configuration()
    Row(modifier = Modifier.width((columns - 1).coerceAtLeast(1))) {
        state.durable.tokenCount?.let { tokenCount -> Text("${tokenCount}t ") }
        val control = state.runtimeControl()
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
        TuiButton(
            label = "Compact",
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionForeground,
            enabled = !state.running && state.agentState.canCompact,
            onClick = { viewModel.forceCompact() },
        )
        Text(" ")
        RuntimeConfigurationTriggers(configuration, dropdowns)
        state.failureMessage?.let { failure -> Text(" [error] $failure") }
        RightAlignedSettingsButton(onClick = onOpenSettings)
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
        state.failureMessage?.let { failure -> Text(" [notice] $failure") }
        RightAlignedSettingsButton(onClick = onOpenSettings)
    }
}

@Composable
private fun RowScope.RightAlignedSettingsButton(onClick: () -> Unit) {
    Spacer(Modifier.width(1))
    Spacer(Modifier.weight(1f))
    TuiButton(
        label = "Settings",
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionForeground,
        onClick = onClick,
    )
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
        onConfigurationSelected = { model, effort, tier ->
            scope.launch {
                viewModel.updateSettings { current ->
                    current.copy(
                        model = model,
                        reasoning = current.reasoning.copy(effort = effort),
                        serviceTier = tier,
                    )
                }
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
        onConfigurationSelected = { model, effort, tier ->
            scope.launch {
                viewModel.updateSettings { current ->
                    current.copy(
                        model = model,
                        reasoningEffort = effort,
                        serviceTier = tier,
                    )
                }
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
internal fun RuntimeConfigurationTriggers(
    configuration: RuntimeConfiguration,
    dropdowns: RuntimeConfigurationDropdowns,
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
    )
    Text(" ")
    TuiDropdownTrigger(
        dropdownState = dropdowns.mode,
        label = configuration.mode.displayName(),
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionForeground,
    )
}

@Composable
internal fun BoxScope.RuntimeConfigurationMenus(
    configuration: RuntimeConfiguration,
    models: List<ModelInfo>,
    modelOptions: List<OpenAiModelId>,
    dropdowns: RuntimeConfigurationDropdowns,
    onConfigurationSelected: (OpenAiModelId, ReasoningEffort, ServiceTier) -> Unit,
    onModeSelected: (ModeKind) -> Unit,
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
        dropdownState = dropdowns.mode,
        options = ModeKind.entries.toList(),
        selected = configuration.mode,
        optionLabel = ModeKind::displayName,
        backgroundColor = PopupMenuBackground,
        onSelect = onModeSelected,
    )
}

internal data class RuntimeConfiguration(
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

private fun AgentRuntimeControl.label(): String = when (this) {
    AgentRuntimeControl.Stop -> "Stop"
    AgentRuntimeControl.ClearPending -> "Clear pending"
    AgentRuntimeControl.Resume -> "Resume"
}
