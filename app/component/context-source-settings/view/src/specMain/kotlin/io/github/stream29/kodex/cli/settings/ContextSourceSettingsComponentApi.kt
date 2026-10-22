package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.contextsourcesettings.ContextSourceSettingsViewModel

/**
 * Complete independent list/dialog surface inside a TuiPopupHost. The host owns navigation/lifetime,
 * calls hidePage on departure and close on disposal; this renderer never creates or closes the VM.
 * Suppress the shared failure banner only when the host already renders that same source once.
 */
@Composable
public expect fun BoxScope.ContextSourceSettingsComponent(
    viewModel: ContextSourceSettingsViewModel,
    showOperationFailure: Boolean = true,
): Unit

/** Scrollable list surface; pair with ContextSourceSettingsDialogs at the containing popup host. */
@Composable
public expect fun ContextSourceSettingsPanel(
    viewModel: ContextSourceSettingsViewModel,
    showOperationFailure: Boolean = true,
): Unit

/** Exact-token dialog overlay. VM draft/error is authoritative; local input stores cursor/undo only. */
@Composable
public expect fun BoxScope.ContextSourceSettingsDialogs(viewModel: ContextSourceSettingsViewModel): Unit
