@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.newsessiondefaults

import androidx.compose.runtime.*
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.settings.MosaicSettingsRenderer

public actual class NewSessionDefaultsDropdowns actual constructor(
    public actual val model: TuiDropdownState,
    public actual val reasoning: TuiDropdownState,
    public actual val serviceTier: TuiDropdownState,
    public actual val questions: TuiDropdownState,
) {
    internal val renderer = MosaicSettingsRenderer(mapOf(
        DefaultsChoiceField.Model.name to model,
        DefaultsChoiceField.Reasoning.name to reasoning,
        DefaultsChoiceField.ServiceTier.name to serviceTier,
        DefaultsChoiceField.Questions.name to questions,
    ))
    public actual fun dismissAll() {
        model.dismiss(); reasoning.dismiss(); serviceTier.dismiss(); questions.dismiss()
    }
}

@Composable
public actual fun rememberNewSessionDefaultsDropdowns(): NewSessionDefaultsDropdowns {
    val model = rememberTuiDropdownState()
    val reasoning = rememberTuiDropdownState()
    val tier = rememberTuiDropdownState()
    val questions = rememberTuiDropdownState()
    return remember(model, reasoning, tier, questions) { NewSessionDefaultsDropdowns(model, reasoning, tier, questions) }
}

@Composable
public actual fun BoxScope.NewSessionDefaultsComponent(
    viewModel: NewSessionSettingsViewModel, showOperationFailure: Boolean,
) {
    val dropdowns = rememberNewSessionDefaultsDropdowns()
    NewSessionDefaultsPanel(viewModel, dropdowns, showOperationFailure)
    NewSessionDefaultsDropdownMenus(viewModel, dropdowns)
}

@Composable
public actual fun NewSessionDefaultsPanel(
    viewModel: NewSessionSettingsViewModel, dropdowns: NewSessionDefaultsDropdowns, showOperationFailure: Boolean,
) {
    DisposableEffect(viewModel, dropdowns) { onDispose(dropdowns::dismissAll) }
    NewSessionDefaultsContent(viewModel, dropdowns.renderer, showOperationFailure)
}

@Composable
public actual fun BoxScope.NewSessionDefaultsDropdownMenus(
    viewModel: NewSessionSettingsViewModel, dropdowns: NewSessionDefaultsDropdowns,
) {
    DisposableEffect(viewModel, dropdowns) { onDispose(dropdowns::dismissAll) }
    with(dropdowns.renderer) { Menus() }
}
