package io.github.stream29.kodex.cli.newsessiondefaults

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.app.ui.SettingsRenderer

public enum class DefaultsChoiceField { Model, Reasoning, ServiceTier, Questions }

/** Four canonical fields; callbacks carry the displayed revision without maintaining a local draft. */
@Composable
public expect fun NewSessionDefaultsContent(
    viewModel: NewSessionSettingsViewModel,
    renderer: SettingsRenderer,
    showOperationFailure: Boolean = true,
): Unit
