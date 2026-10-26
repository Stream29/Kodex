@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.sessiontitlesettings.SessionTitleSettingsViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState

public actual class SessionTitleSettingsDropdowns actual constructor(
    public actual val model: TuiDropdownState,
    public actual val reasoning: TuiDropdownState,
) {
    internal val renderer = MosaicSettingsRenderer(mapOf(
        TitleChoiceField.Model.name to model,
        TitleChoiceField.Reasoning.name to reasoning,
    ))
}

@Composable
public actual fun rememberSessionTitleSettingsDropdowns(): SessionTitleSettingsDropdowns {
    val model = rememberTuiDropdownState()
    val reasoning = rememberTuiDropdownState()
    return remember(model, reasoning) { SessionTitleSettingsDropdowns(model, reasoning) }
}

@Composable
public actual fun BoxScope.SessionTitleSettingsComponent(
    viewModel: SessionTitleSettingsViewModel, showOperationFailure: Boolean,
) {
    val dropdowns = rememberSessionTitleSettingsDropdowns()
    SessionTitleSettingsPanel(viewModel, dropdowns, showOperationFailure)
    SessionTitleSettingsDropdownMenus(viewModel, dropdowns)
}

@Composable
public actual fun SessionTitleSettingsPanel(
    viewModel: SessionTitleSettingsViewModel, dropdowns: SessionTitleSettingsDropdowns, showOperationFailure: Boolean,
) {
    SessionTitleSettingsContent(viewModel, dropdowns.renderer, showOperationFailure)
}

@Composable
public actual fun BoxScope.SessionTitleSettingsDropdownMenus(
    viewModel: SessionTitleSettingsViewModel, dropdowns: SessionTitleSettingsDropdowns,
) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    with(dropdowns.renderer) { Menus() }
}
