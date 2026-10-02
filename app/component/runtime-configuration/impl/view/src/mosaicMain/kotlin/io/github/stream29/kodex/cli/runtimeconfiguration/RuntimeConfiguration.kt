package io.github.stream29.kodex.cli.runtimeconfiguration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationState
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiPopupSubmenuItem
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.settings.PopupMenuBackground
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.coroutines.launch

/** Renderer-owned handles shared by the two triggers and direct popup-host menu children. */
@Stable
public class RuntimeConfigurationDropdowns private constructor(
    public val model: TuiDropdownState,
    public val requestUserInputMode: TuiDropdownState,
) {
    public companion object {
        /** Use the exact stable child as [owner]; switching targets discards all menu/focus state. */
        @Composable
        public fun remember(owner: Any?): RuntimeConfigurationDropdowns = key(owner) {
            val model = rememberTuiDropdownState()
            val mode = rememberTuiDropdownState()
            remember(model, mode) { RuntimeConfigurationDropdowns(model, mode) }
        }
    }
}

/** Standalone row with one cell between triggers; no lifecycle ownership of [viewModel]. */
@Composable
public fun RuntimeConfigurationTriggers(
    viewModel: RuntimeConfigurationViewModel,
    dropdowns: RuntimeConfigurationDropdowns,
    enabled: Boolean = true,
) {
    key(viewModel) {
        val state by viewModel.state.collectAsState()
        RuntimeConfigurationTriggers(state, dropdowns, enabled)
    }
}

/** State-only standalone renderer, also useful in preview/fake-ViewModel interaction tests. */
@Composable
public fun RuntimeConfigurationTriggers(
    state: RuntimeConfigurationState,
    dropdowns: RuntimeConfigurationDropdowns,
    enabled: Boolean = true,
) {
    if (state.closed) return
    Row { RuntimeConfigurationStatusItems(state, dropdowns, enabled, spacing = true) }
}

/**
 * Emits TWO direct measurables without a Row or spacer so the host's status-bar layout can wrap
 * each whole trigger and apply its own spacing. Does not move token/cwd/settings/runtime controls.
 */
@Composable
public fun RuntimeConfigurationStatusItemsWithoutSpacing(
    viewModel: RuntimeConfigurationViewModel,
    dropdowns: RuntimeConfigurationDropdowns,
    enabled: Boolean = true,
) {
    key(viewModel) {
        val state by viewModel.state.collectAsState()
        RuntimeConfigurationStatusItemsWithoutSpacing(state, dropdowns, enabled)
    }
}

/** State-only version retaining the two-direct-measurables host layout contract. */
@Composable
public fun RuntimeConfigurationStatusItemsWithoutSpacing(
    state: RuntimeConfigurationState,
    dropdowns: RuntimeConfigurationDropdowns,
    enabled: Boolean = true,
) {
    RuntimeConfigurationStatusItems(state, dropdowns, enabled, spacing = false)
}

@Composable
private fun RuntimeConfigurationStatusItems(
    state: RuntimeConfigurationState,
    dropdowns: RuntimeConfigurationDropdowns,
    enabled: Boolean,
    spacing: Boolean,
) {
    if (state.closed) return
    val configuration = state.configuration
    TuiDropdownTrigger(
        dropdownState = dropdowns.model,
        label = runtimeConfigurationLabel(configuration.model, configuration.reasoning, configuration.tier),
        modifier = Modifier.background(TuiTheme.colorScheme.primaryContainer),
        color = TuiTheme.colorScheme.onPrimaryContainer,
        enabled = enabled,
    )
    if (spacing) Text(" ")
    TuiDropdownTrigger(
        dropdownState = dropdowns.requestUserInputMode,
        label = runtimeRequestUserInputModeLabel(configuration.requestUserInputMode),
        modifier = Modifier.background(TuiTheme.colorScheme.primaryContainer),
        color = TuiTheme.colorScheme.onPrimaryContainer,
        enabled = enabled,
    )
}

/**
 * Render after persistent content as a DIRECT child of TuiPopupHost, sharing [dropdowns] with
 * triggers. Waits belong to this composition's scope, so unmount cancels them, not the borrowed
 * ViewModel/owner. Captures the exact ViewModel and leaf tuple; never resolves a replacement target.
 * Port exceptions retain the baseline composition-scope/host reporting path.
 */
@Composable
public fun BoxScope.RuntimeConfigurationMenus(
    viewModel: RuntimeConfigurationViewModel,
    dropdowns: RuntimeConfigurationDropdowns,
) {
    key(viewModel) {
        val state by viewModel.state.collectAsState()
        val scope = rememberCoroutineScope()
        RuntimeConfigurationMenus(
            state = state,
            dropdowns = dropdowns,
            onConfigurationSelected = { model, effort, tier ->
                scope.launch { viewModel.updateModelConfiguration(model, effort, tier) }
            },
            onRequestUserInputModeSelected = { mode ->
                scope.launch { viewModel.updateRequestUserInputMode(mode) }
            },
        )
    }
}

/** Complete three-level and questions menus; navigation/Escape never invoke a write callback. */
@Composable
public fun BoxScope.RuntimeConfigurationMenus(
    state: RuntimeConfigurationState,
    dropdowns: RuntimeConfigurationDropdowns,
    onConfigurationSelected: (OpenAiModelId, ReasoningEffort, ServiceTier) -> Unit,
    onRequestUserInputModeSelected: (RequestUserInputMode) -> Unit,
) {
    if (state.closed) return
    val configuration = state.configuration
    val background = PopupMenuBackground
    TuiDropdownMenu(dropdownState = dropdowns.model, backgroundColor = background) {
        state.modelOptions.forEach { option ->
            val model = option.model
            TuiPopupSubmenuItem(
                key = model,
                selected = model == configuration.model,
                initialSubmenuFocusedKey = configuration.reasoning
                    .takeIf { model == configuration.model && it in option.efforts }
                    ?: option.efforts.first(),
                backgroundColor = background,
                submenuContent = {
                    option.efforts.forEach { effort ->
                        TuiPopupSubmenuItem(
                            key = effort,
                            selected = model == configuration.model && effort == configuration.reasoning,
                            initialSubmenuFocusedKey = configuration.tier.takeIf {
                                model == configuration.model && effort == configuration.reasoning &&
                                    it in option.tiers
                            } ?: ServiceTier.Default,
                            backgroundColor = background,
                            submenuContent = {
                                option.tiers.forEach { tier ->
                                    TuiPopupMenuItem(
                                        key = tier,
                                        selected = model == configuration.model &&
                                            effort == configuration.reasoning && tier == configuration.tier,
                                        onClick = { onConfigurationSelected(model, effort, tier) },
                                    ) { Text(runtimeServiceTierLabel(tier)) }
                                }
                            },
                        ) { Text(effort.wireName) }
                    }
                },
            ) { Text(model.value) }
        }
    }
    TuiDropdownMenu(
        dropdownState = dropdowns.requestUserInputMode,
        options = RequestUserInputMode.entries.toList(),
        selected = configuration.requestUserInputMode,
        optionLabel = ::runtimeRequestUserInputModeLabel,
        backgroundColor = background,
        onSelect = onRequestUserInputModeSelected,
    )
}

/** Exact trigger text for host layout measurement; only Default omits the tier suffix. */
public fun runtimeConfigurationLabel(
    model: OpenAiModelId,
    reasoning: ReasoningEffort,
    tier: ServiceTier,
): String = buildString {
    append(model.value)
    append(' ')
    append(reasoning.wireName)
    if (tier != ServiceTier.Default) {
        append(' ')
        append(runtimeServiceTierLabel(tier))
    }
}

/** Exact questions trigger/menu text, also shared with host layout measurement. */
public fun runtimeRequestUserInputModeLabel(mode: RequestUserInputMode): String = when (mode) {
    RequestUserInputMode.AskUser -> "ask user"
    RequestUserInputMode.NoQuestion -> "no question"
}

private fun runtimeServiceTierLabel(tier: ServiceTier): String = when (tier) {
    ServiceTier.Default -> "default"
    ServiceTier.Fast -> "fast"
    ServiceTier.Flex -> "flex"
}
