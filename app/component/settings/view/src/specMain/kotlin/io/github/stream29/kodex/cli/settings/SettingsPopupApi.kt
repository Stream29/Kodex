package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.settings.contract.SettingsViewModel

/**
 * Navigation and scroll layout for stable Settings children, with direct popup-host overlays.
 * All configuration, drafts and exact interaction targets belong to the components. The host renders
 * shared failure once and hides page interactions without closing their observation lifetimes.
 */
@Composable
public expect fun BoxScope.SettingsPopup(
    viewModel: SettingsViewModel,
    onDismissRequest: () -> Unit,
    onOpenLogin: () -> Unit,
): Unit
