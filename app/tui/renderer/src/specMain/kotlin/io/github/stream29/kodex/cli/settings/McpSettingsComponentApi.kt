package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.mcpsettings.McpSettingsViewModel

/**
 * Complete MCP list/dialog renderer inside a TuiPopupHost. Uses the stable child directly; no
 * business draft, preview selection or parallel route is remembered. Host calls hidePage when
 * leaving MCP, close when Settings ends, and installs [McpSettingsEffects] for the ENTIRE Settings
 * lifetime (even while another page is visible). No backend/store/platform opener is created here.
 * For host viewport layout, compose [McpSettingsPanel] and [McpSettingsDialogs] separately.
 */
@Composable
public expect fun BoxScope.McpSettingsComponent(viewModel: McpSettingsViewModel): Unit

/**
 * List/failure surface; layout/scroll are renderer-local, runtime rows remain read-only.
 * Set [showOperationFailure] false only when the host renders the same shared failure
 * acknowledgement itself; this avoids duplicate banners without changing failure ownership.
 */
@Composable
public expect fun McpSettingsPanel(viewModel: McpSettingsViewModel, showOperationFailure: Boolean = true): Unit

/** Independent Settings overlay; exact tokens protect replacement dialogs from late callbacks. */
@Composable
public expect fun BoxScope.McpSettingsDialogs(viewModel: McpSettingsViewModel): Unit
