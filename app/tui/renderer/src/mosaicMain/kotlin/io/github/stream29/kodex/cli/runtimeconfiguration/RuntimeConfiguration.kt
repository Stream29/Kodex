@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.runtimeconfiguration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationState
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiPopupMenuScope
import io.github.stream29.kodex.cli.components.TuiPopupSubmenuItem
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.settings.PopupMenuBackground
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier

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
    RuntimeConfigurationMenuContent(viewModel) { menu ->
        RuntimeConfigurationMenus(menu, dropdowns)
    }
}

@Composable
public actual fun BoxScope.RuntimeConfigurationMenus(
    state: RuntimeConfigurationState,
    dropdowns: RuntimeConfigurationDropdowns,
    onConfigurationSelected: (OpenAiModelId, ReasoningEffort, ServiceTier) -> Unit,
    onRequestUserInputModeSelected: (RequestUserInputMode) -> Unit,
) {
    val menu = runtimeConfigurationMenu(state, onConfigurationSelected, onRequestUserInputModeSelected) ?: return
    RuntimeConfigurationMenus(menu, dropdowns)
}

@Composable
private fun BoxScope.RuntimeConfigurationMenus(menu: RuntimeConfigurationMenu, dropdowns: RuntimeConfigurationDropdowns) {
    val background = PopupMenuBackground
    TuiDropdownMenu(dropdownState = dropdowns.model, backgroundColor = background) {
        RuntimeNodes(menu.models, background)
    }
    TuiDropdownMenu(dropdownState = dropdowns.requestUserInputMode, backgroundColor = background) {
        RuntimeNodes(menu.modes, background)
    }
}

internal fun TuiPopupMenuScope.RuntimeNodes(entries: List<RuntimeMenuEntry>, background: Color, enabled: Boolean = true) {
    entries.forEach { entry ->
        when (entry) {
            is RuntimeMenuEntry.Branch -> TuiPopupSubmenuItem(
                key = entry.key, selected = entry.selected, initialSubmenuFocusedKey = entry.initialChildKey,
                enabled = enabled,
                backgroundColor = background,
                submenuContent = { RuntimeNodes(entry.children(), background, enabled) },
            ) { Text(entry.label) }
            is RuntimeMenuEntry.Command -> TuiPopupMenuItem(
                key = entry.key, selected = entry.selected, enabled = enabled,
                onClick = { if (enabled) entry.onSelect() },
            ) { Text(entry.label) }
        }
    }
}
