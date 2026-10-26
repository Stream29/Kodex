@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.sessiontitlesettings.SessionTitleSettingsViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownState

/** Renderer-only anchors/open-menu state; no settings, draft, validation or failure authority. */
public expect class SessionTitleSettingsDropdowns(model: TuiDropdownState, reasoning: TuiDropdownState) {
    public val model: TuiDropdownState
    public val reasoning: TuiDropdownState
}

@Composable
public expect fun rememberSessionTitleSettingsDropdowns(): SessionTitleSettingsDropdowns

/**
 * Complete controls and menus inside a TuiPopupHost. Caller owns navigation/VM lifetime; no VM
 * creation/disposal here. Failure appears here OR in the host by showOperationFailure=false.
 * For a scrollable page use Panel in the viewport and DropdownMenus at the containing host.
 */
@Composable
public expect fun BoxScope.SessionTitleSettingsComponent(
    viewModel: SessionTitleSettingsViewModel,
    showOperationFailure: Boolean = true,
): Unit

/** Section/fields only; preserve the same renderer-owned dropdown handles for the overlay. */
@Composable
public expect fun SessionTitleSettingsPanel(
    viewModel: SessionTitleSettingsViewModel,
    dropdowns: SessionTitleSettingsDropdowns,
    showOperationFailure: Boolean = true,
): Unit

/** Both complete baseline menus; disabling rendering does not add VM command rejection. */
@Composable
public expect fun BoxScope.SessionTitleSettingsDropdownMenus(
    viewModel: SessionTitleSettingsViewModel,
    dropdowns: SessionTitleSettingsDropdowns,
): Unit
