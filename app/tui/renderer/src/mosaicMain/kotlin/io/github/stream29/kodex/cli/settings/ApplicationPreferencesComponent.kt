@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.applicationpreferences.ApplicationPreferencesViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState

public actual class ApplicationPreferencesDropdowns actual constructor(
    public actual val newLineKey: TuiDropdownState,
    public actual val submitKey: TuiDropdownState,
) {
    internal val renderer = MosaicSettingsRenderer(mapOf(
        PreferencesChoiceField.NewLineKey.name to newLineKey,
        PreferencesChoiceField.SubmitKey.name to submitKey,
    ))
}

@Composable
public actual fun rememberApplicationPreferencesDropdowns(): ApplicationPreferencesDropdowns {
    val newLineKey = rememberTuiDropdownState()
    val submitKey = rememberTuiDropdownState()
    return remember(newLineKey, submitKey) { ApplicationPreferencesDropdowns(newLineKey, submitKey) }
}

@Composable
public actual fun BoxScope.ApplicationPreferencesComponent(
    viewModel: ApplicationPreferencesViewModel,
    showOperationFailure: Boolean,
) {
    val dropdowns = rememberApplicationPreferencesDropdowns()
    ApplicationPreferencesPanel(viewModel, dropdowns, showOperationFailure)
    ApplicationPreferencesDropdownMenus(viewModel, dropdowns)
}

@Composable
public actual fun ApplicationPreferencesPanel(
    viewModel: ApplicationPreferencesViewModel,
    dropdowns: ApplicationPreferencesDropdowns,
    showOperationFailure: Boolean,
) {
    ApplicationPreferencesContent(viewModel, dropdowns.renderer, showOperationFailure)
}

@Composable
public actual fun BoxScope.ApplicationPreferencesDropdownMenus(
    viewModel: ApplicationPreferencesViewModel,
    dropdowns: ApplicationPreferencesDropdowns,
) {
    val state by viewModel.state.collectAsState()
    if (state.closed) return
    with(dropdowns.renderer) { Menus() }
}
