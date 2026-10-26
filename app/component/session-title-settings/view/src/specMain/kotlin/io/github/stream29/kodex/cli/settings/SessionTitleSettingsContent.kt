package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.sessiontitlesettings.SessionTitleSettingsViewModel
import io.github.stream29.kodex.app.ui.SettingsRenderer

public enum class TitleChoiceField { Model, Reasoning }

/** Shared title controls and command wiring; disabled presentation never rewrites settings or owns the child. */
@Composable
public expect fun SessionTitleSettingsContent(
    viewModel: SessionTitleSettingsViewModel,
    renderer: SettingsRenderer,
    showOperationFailure: Boolean = true,
): Unit
