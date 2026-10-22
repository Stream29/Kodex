@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

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
import com.jakewharton.mosaic.ui.Column
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

@Stable
public actual class RuntimeConfigurationDropdowns private constructor(
    public actual val model: TuiDropdownState,
    public actual val requestUserInputMode: TuiDropdownState,
) {
    public actual companion object {
        @Composable
        public actual fun remember(owner: Any?): RuntimeConfigurationDropdowns = key(owner) {
            val model = rememberTuiDropdownState()
            val mode = rememberTuiDropdownState()
            remember(model, mode) { RuntimeConfigurationDropdowns(model, mode) }
        }
    }
}

@Composable
public actual fun RuntimeConfigurationTriggers(
    viewModel: RuntimeConfigurationViewModel,
    dropdowns: RuntimeConfigurationDropdowns,
    enabled: Boolean,
) {
    key(viewModel) {
        val state by viewModel.state.collectAsState()
        RuntimeConfigurationTriggers(state, dropdowns, enabled)
    }
}

@Composable
public actual fun RuntimeConfigurationTriggers(
    state: RuntimeConfigurationState,
    dropdowns: RuntimeConfigurationDropdowns,
    enabled: Boolean,
) {
    if (state.closed) return
    Row { RuntimeConfigurationStatusItems(state, dropdowns, enabled, spacing = true) }
}

@Composable
public actual fun RuntimeConfigurationStatusItemsWithoutSpacing(
    viewModel: RuntimeConfigurationViewModel,
    dropdowns: RuntimeConfigurationDropdowns,
    enabled: Boolean,
) {
    key(viewModel) {
        val state by viewModel.state.collectAsState()
        RuntimeConfigurationStatusItemsWithoutSpacing(state, dropdowns, enabled)
    }
}

@Composable
public actual fun RuntimeConfigurationStatusItemsWithoutSpacing(
    state: RuntimeConfigurationState,
    dropdowns: RuntimeConfigurationDropdowns,
    enabled: Boolean,
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

@Composable
public actual fun BoxScope.RuntimeConfigurationMenus(
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

@Composable
public actual fun BoxScope.RuntimeConfigurationMenus(
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
                                    ) {
                                        Text(runtimeServiceTierLabel(tier))
                                    }
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

public actual fun runtimeConfigurationLabel(
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

public actual fun runtimeRequestUserInputModeLabel(mode: RequestUserInputMode): String = when (mode) {
    RequestUserInputMode.AskUser -> "ask user"
    RequestUserInputMode.NoQuestion -> "no question"
}

private fun runtimeServiceTierLabel(tier: ServiceTier): String = when (tier) {
    ServiceTier.Default -> "default"
    ServiceTier.Fast -> "fast"
    ServiceTier.Flex -> "flex"
    ServiceTier.Ultrafast -> "ultrafast"
}
