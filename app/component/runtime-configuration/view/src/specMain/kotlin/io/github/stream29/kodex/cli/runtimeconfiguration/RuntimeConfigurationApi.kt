@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.runtimeconfiguration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationState
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier

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

/** Standalone row with one cell between triggers; no lifecycle ownership of [viewModel]. */
@Composable
public expect fun RuntimeConfigurationTriggers(
    viewModel: RuntimeConfigurationViewModel,
    dropdowns: RuntimeConfigurationDropdowns,
    enabled: Boolean = true,
): Unit

/** State-only standalone renderer, also useful in preview/fake-ViewModel interaction tests. */
@Composable
public expect fun RuntimeConfigurationTriggers(
    state: RuntimeConfigurationState,
    dropdowns: RuntimeConfigurationDropdowns,
    enabled: Boolean = true,
): Unit

/**
 * Emits TWO direct measurables without a Row or spacer so the host's status-bar layout can wrap
 * each whole trigger and apply its own spacing. Does not move token/cwd/settings/runtime controls.
 */
@Composable
public expect fun RuntimeConfigurationStatusItemsWithoutSpacing(
    viewModel: RuntimeConfigurationViewModel,
    dropdowns: RuntimeConfigurationDropdowns,
    enabled: Boolean = true,
): Unit

/** State-only version retaining the two-direct-measurables host layout contract. */
@Composable
public expect fun RuntimeConfigurationStatusItemsWithoutSpacing(
    state: RuntimeConfigurationState,
    dropdowns: RuntimeConfigurationDropdowns,
    enabled: Boolean = true,
): Unit

/**
 * Render after persistent content as a DIRECT child of TuiPopupHost, sharing [dropdowns] with
 * triggers. Waits belong to this composition's scope, so unmount cancels them, not the borrowed
 * ViewModel/owner. Captures the exact ViewModel and leaf tuple; never resolves a replacement target.
 * Port exceptions retain the baseline composition-scope/host reporting path.
 */
@Composable
public expect fun BoxScope.RuntimeConfigurationMenus(
    viewModel: RuntimeConfigurationViewModel,
    dropdowns: RuntimeConfigurationDropdowns,
): Unit

/** Complete three-level and questions menus; navigation/Escape never invoke a write callback. */
@Composable
public expect fun BoxScope.RuntimeConfigurationMenus(
    state: RuntimeConfigurationState,
    dropdowns: RuntimeConfigurationDropdowns,
    onConfigurationSelected: (OpenAiModelId, ReasoningEffort, ServiceTier) -> Unit,
    onRequestUserInputModeSelected: (RequestUserInputMode) -> Unit,
): Unit

/** Exact trigger text for host layout measurement; only Default omits the tier suffix. */
public expect fun runtimeConfigurationLabel(
    model: OpenAiModelId,
    reasoning: ReasoningEffort,
    tier: ServiceTier,
): String

/** Exact questions trigger/menu text, also shared with host layout measurement. */
public expect fun runtimeRequestUserInputModeLabel(mode: RequestUserInputMode): String
