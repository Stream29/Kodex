package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.applicationpreferences.ApplicationPreferencesViewModel
import io.github.stream29.kodex.app.ui.SettingsRenderer

/** Stable keys for these two fields inside one renderer; not persisted input preferences. */
public enum class PreferencesChoiceField { NewLineKey, SubmitKey }

/**
 * Shared General page: Sidebars then Input, snapshot +/- bounds and canonical key commands.
 * The renderer owns fields/menus only. No implicit resize, refresh, child close or key-write mirror.
 */
@Composable
public expect fun ApplicationPreferencesContent(
    viewModel: ApplicationPreferencesViewModel,
    renderer: SettingsRenderer,
    showOperationFailure: Boolean = true,
): Unit
