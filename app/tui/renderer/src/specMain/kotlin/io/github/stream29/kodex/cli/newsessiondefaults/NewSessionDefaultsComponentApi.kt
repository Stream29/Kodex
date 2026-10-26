@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.newsessiondefaults

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownState

/** Only renderer menu anchors/open state; never a second defaults draft. */
public expect class NewSessionDefaultsDropdowns(
    model: TuiDropdownState,
    reasoning: TuiDropdownState,
    serviceTier: TuiDropdownState,
    questions: TuiDropdownState,
) {
    public val model: TuiDropdownState
    public val reasoning: TuiDropdownState
    public val serviceTier: TuiDropdownState
    public val questions: TuiDropdownState
    public fun dismissAll()
}

@Composable
public expect fun rememberNewSessionDefaultsDropdowns(): NewSessionDefaultsDropdowns

/** Full four-field component; Title remains an independently owned sibling. */
@Composable
public expect fun BoxScope.NewSessionDefaultsComponent(
    viewModel: NewSessionSettingsViewModel,
    showOperationFailure: Boolean = true,
): Unit

/** Scrollable host content. Disable its banner when the Settings host displays shared failure once. */
@Composable
public expect fun NewSessionDefaultsPanel(
    viewModel: NewSessionSettingsViewModel,
    dropdowns: NewSessionDefaultsDropdowns,
    showOperationFailure: Boolean = true,
): Unit

/** Full canonical menus; callbacks submit the displayed revision and no local draft. */
@Composable
public expect fun BoxScope.NewSessionDefaultsDropdownMenus(
    viewModel: NewSessionSettingsViewModel,
    dropdowns: NewSessionDefaultsDropdowns,
): Unit
