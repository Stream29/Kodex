@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.sessionsettings

import androidx.compose.runtime.*
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.settings.contract.SessionSettingsViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.sessionrename.SessionRenamePopup
import io.github.stream29.kodex.cli.sessionrename.SessionRenamePresentation
import io.github.stream29.kodex.cli.settings.MosaicSettingsRenderer
import io.github.stream29.kodex.cli.workingdirectory.WorkingDirectoryPopup

public actual class SessionSettingsDropdowns actual constructor(
    public actual val model: TuiDropdownState,
    public actual val reasoning: TuiDropdownState,
    public actual val serviceTier: TuiDropdownState,
    public actual val questions: TuiDropdownState,
) {
    internal val renderer = MosaicSettingsRenderer(mapOf(
        SessionChoiceField.Model.name to model,
        SessionChoiceField.Reasoning.name to reasoning,
        SessionChoiceField.ServiceTier.name to serviceTier,
        SessionChoiceField.Questions.name to questions,
    ))
    public actual fun dismissAll() {
        model.dismiss(); reasoning.dismiss(); serviceTier.dismiss(); questions.dismiss()
    }
}

@Composable
public actual fun rememberSessionSettingsDropdowns(): SessionSettingsDropdowns {
    val model = rememberTuiDropdownState()
    val reasoning = rememberTuiDropdownState()
    val tier = rememberTuiDropdownState()
    val questions = rememberTuiDropdownState()
    return remember(model, reasoning, tier, questions) { SessionSettingsDropdowns(model, reasoning, tier, questions) }
}

@Composable
public actual fun BoxScope.SessionSettingsComponent(viewModel: SessionSettingsViewModel) {
    val dropdowns = rememberSessionSettingsDropdowns()
    SessionSettingsPanel(viewModel, dropdowns)
    SessionSettingsOverlays(viewModel, dropdowns)
}

@Composable
public actual fun SessionSettingsPanel(viewModel: SessionSettingsViewModel, dropdowns: SessionSettingsDropdowns) {
    DisposableEffect(viewModel, dropdowns) { onDispose(dropdowns::dismissAll) }
    SessionSettingsContent(viewModel, dropdowns.renderer)
}

@Composable
public actual fun BoxScope.SessionSettingsOverlays(viewModel: SessionSettingsViewModel, dropdowns: SessionSettingsDropdowns) {
    DisposableEffect(viewModel, dropdowns) { onDispose(dropdowns::dismissAll) }
    with(dropdowns.renderer) { Menus() }
    SessionSettingsChildren(
        viewModel,
        rename = { handle, dismiss ->
            SessionRenamePopup(
                handle.viewModel, presentation = SessionRenamePresentation.Labeled,
                onDismissRequest = dismiss, onSubmitted = dismiss,
            )
        },
        directory = { handle, dismiss ->
            WorkingDirectoryPopup(handle.selection, onDismissRequest = dismiss, onSelected = {})
        },
    )
}
