package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.hooksettings.HookSettingsViewModel

/**
 * Full Hook list and independent dialog renderer. Consume the exact stable Settings child inside
 * a TuiPopupHost; the host owns page selection and calls hidePage on leaving / close on disposal.
 * This renderer never creates a VM/store, captures a baseline or keeps business draft state.
 * For a scrollable Settings page use [HookSettingsPanel] in its viewport and
 * [HookSettingsDialogs] at the containing popup host instead.
 */
@Composable
public expect fun BoxScope.HookSettingsComponent(viewModel: HookSettingsViewModel): Unit

/**
 * List/failure surface only; layout, focus and scrolling belong to its caller.
 * Set [showOperationFailure] false only when the host renders the shared failure
 * acknowledgement itself, so one application failure does not produce duplicate banners.
 */
@Composable
public expect fun HookSettingsPanel(viewModel: HookSettingsViewModel, showOperationFailure: Boolean = true): Unit

/** Overlay renderer; every callback is bound to its exact VM-owned dialog token. */
@Composable
public expect fun BoxScope.HookSettingsDialogs(viewModel: HookSettingsViewModel): Unit
