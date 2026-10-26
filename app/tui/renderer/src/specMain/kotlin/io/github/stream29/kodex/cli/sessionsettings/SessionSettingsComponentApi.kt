@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.sessionsettings

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.settings.contract.SessionSettingsViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownState

/** Renderer-only menu anchors/open state; contains no configuration or target/draft authority. */
public expect class SessionSettingsDropdowns(
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
public expect fun rememberSessionSettingsDropdowns(): SessionSettingsDropdowns

/** Complete independently renderable component. Removal hides the page, never closes the stable VM. */
@Composable
public expect fun BoxScope.SessionSettingsComponent(viewModel: SessionSettingsViewModel): Unit

/** Scrollable host content; pass the same renderer menu object to [SessionSettingsOverlays]. */
@Composable
public expect fun SessionSettingsPanel(
    viewModel: SessionSettingsViewModel,
    dropdowns: SessionSettingsDropdowns,
): Unit

/**
 * Full four menus and borrowed exact child renderers. No effects collector or renderer-local name
 * draft/target. Child renderers retain their existing disposal and Labeled presentation contracts.
 */
@Composable
public expect fun BoxScope.SessionSettingsOverlays(
    viewModel: SessionSettingsViewModel,
    dropdowns: SessionSettingsDropdowns,
): Unit
