@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.runtimeconfiguration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownState

/** Renderer-owned handles shared by the two triggers and direct popup-host menu children. */
@Stable
public expect class RuntimeConfigurationDropdowns {
    public val model: TuiDropdownState
    public val requestUserInputMode: TuiDropdownState

    public companion object {
        /** Use the exact stable child as [owner]; switching targets discards all menu/focus state. */
        @Composable
        public fun remember(owner: Any?): RuntimeConfigurationDropdowns
    }
}

/**
 * Render after persistent content as a DIRECT child of TuiPopupHost, sharing [dropdowns] with
 * triggers. Waits belong to this composition's scope, so unmount cancels them, not the borrowed
 * ViewModel/owner. Captures the exact ViewModel and leaf tuple; never resolves a replacement target.
 */
@Composable
public expect fun BoxScope.RuntimeConfigurationMenus(
    viewModel: RuntimeConfigurationViewModel,
    dropdowns: RuntimeConfigurationDropdowns,
): Unit
