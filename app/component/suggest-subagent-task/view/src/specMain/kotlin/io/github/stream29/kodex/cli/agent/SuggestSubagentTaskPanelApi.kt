@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownState

/**
 * Renderer-only anchors/menu state. Share this handle between the panel and menus rendered
 * directly in the host's TuiPopupHost; it contains no configuration or rejection draft.
 */
@Stable
public expect class SuggestSubagentTaskDropdowns {
    public val model: TuiDropdownState
    public val requestUserInputMode: TuiDropdownState

    public companion object {
        /** Exact owner/call key disposes old menu anchors when the pending call changes. */
        @Composable
        public fun remember(owner: Any?, callId: String?): SuggestSubagentTaskDropdowns
    }
}

/**
 * Full confirmation/configuration panel. No arbitrary configuration slot or renderer-owned
 * rejection mode exists. [onBrowseWorkingDirectory] opens a host-routed Working Directory
 * child bound to its exact call; selection edits the component through updateWorkingDirectory.
 * Removing this renderer cancels only its waits/focus work, not the Agent-owned component.
 */
@Composable
public expect fun SuggestSubagentTaskPanel(
    viewModel: SuggestSubagentTaskViewModel,
    state: SuggestSubagentTaskState.Pending,
    columns: Int,
    rows: Int,
    dropdowns: SuggestSubagentTaskDropdowns,
    onBrowseWorkingDirectory: (String) -> Unit,
): Unit

/**
 * Complete typed configuration menus, rendered after persistent content directly in the
 * surrounding TuiPopupHost. Every callback edits the exact call and latest configuration;
 * stale/submitting callbacks are rejected by the component. Models are observed read-only.
 */
@Composable
public expect fun BoxScope.SuggestSubagentTaskConfigurationMenus(
    viewModel: SuggestSubagentTaskViewModel,
    state: SuggestSubagentTaskState.Pending,
    dropdowns: SuggestSubagentTaskDropdowns,
): Unit
