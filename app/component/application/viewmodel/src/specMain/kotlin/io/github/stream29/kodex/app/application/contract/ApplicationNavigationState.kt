package io.github.stream29.kodex.app.application.contract

import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.AgentTabViewModel

public sealed interface ApplicationTabSelection {
    public data object NewSession : ApplicationTabSelection
    public data class SelectedTab(public val index: Int) : ApplicationTabSelection
}

/**
 * Atomic persisted tab order and union selection; an empty registry is valid.
 *
 * The ordered handles and selection are published atomically. The only delegation
 * metadata retained here is the direct return links learned from creation rows,
 * including closed ancestors reachable from an open child.
 * Child mutable state stays in each [AgentTabViewModel].
 *
 * @throws IllegalArgumentException if a selected index is outside
 * this snapshot, or the same Agent ID appears more than once.
 */
public data class ApplicationNavigationState(
    public val tabs: List<AgentTabViewModel>,
    public val selection: ApplicationTabSelection,
    public val parentAgentIds: Map<Int, Int> = emptyMap(),
) {
    init {
        require(selection !is ApplicationTabSelection.SelectedTab || selection.index in tabs.indices) {
            "The selected tab index must address the same navigation snapshot."
        }
        require(
            tabs.indices.all { index ->
                (0 until index).none { previousIndex ->
                    tabs[previousIndex].agentId == tabs[index].agentId
                }
            },
        ) {
            "An Agent ID must not appear in more than one tab."
        }
    }

    public val selectedTab: AgentTabViewModel?
        get() = when (selection) {
            ApplicationTabSelection.NewSession -> null
            is ApplicationTabSelection.SelectedTab -> tabs[selection.index]
        }

    /** Session management is available only on a root Agent's Session handle. */
    public val selectedSession: PersistedSessionViewModel?
        get() = selectedTab as? PersistedSessionViewModel
}
