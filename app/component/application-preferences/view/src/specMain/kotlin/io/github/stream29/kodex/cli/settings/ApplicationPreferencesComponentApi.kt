package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.applicationpreferences.ApplicationPreferencesViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownState

/** Anchors/menu-open state only; not another preferences draft. */
public class ApplicationPreferencesDropdowns(
    public val newLineKey: TuiDropdownState,
    public val submitKey: TuiDropdownState,
)

@Composable
public expect fun rememberApplicationPreferencesDropdowns(): ApplicationPreferencesDropdowns

/**
 * Complete General controls and both menus inside a TuiPopupHost, no VM creation/lifecycle ownership.
 * For scrollable hosts use Panel and DropdownMenus with the same renderer-only handles. Suppress
 * the shared failure acknowledgement only when the host already renders that source once.
 */
@Composable
public expect fun BoxScope.ApplicationPreferencesComponent(
    viewModel: ApplicationPreferencesViewModel,
    showOperationFailure: Boolean = true,
): Unit

/** Sidebars then Input; +/- captures displayed columns, never silently refreshes before applying. */
@Composable
public expect fun ApplicationPreferencesPanel(
    viewModel: ApplicationPreferencesViewModel,
    dropdowns: ApplicationPreferencesDropdowns,
    showOperationFailure: Boolean = true,
): Unit

/** Overlay for the two canonical paired-key choices. */
@Composable
public expect fun BoxScope.ApplicationPreferencesDropdownMenus(
    viewModel: ApplicationPreferencesViewModel,
    dropdowns: ApplicationPreferencesDropdowns,
): Unit
