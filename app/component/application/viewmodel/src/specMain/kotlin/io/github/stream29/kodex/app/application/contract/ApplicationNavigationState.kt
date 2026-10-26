package io.github.stream29.kodex.app.application.contract

import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel

public sealed interface ApplicationTabSelection {
    public data object NewSession : ApplicationTabSelection
    public data class SelectedTab(public val index: Int) : ApplicationTabSelection
}

/**
 * Atomic persisted tab order and union selection; an empty registry is valid.
 *
 * Child mutable state stays in each [PersistedSessionViewModel]. This parent state
 * contains only the parent-owned ordering and selected position. Render tabs
 * in this order and render [selectedSession] from this same snapshot, using the child
 * instance as the callback target. Selecting another tab does not close it.
 *
 * @throws IllegalArgumentException if a selected index is outside
 * this snapshot, or the same child instance appears more than once.
 */
public data class ApplicationNavigationState(
    public val tabs: List<PersistedSessionViewModel>,
    public val selection: ApplicationTabSelection,
) {
    init {
        require(selection !is ApplicationTabSelection.SelectedTab || selection.index in tabs.indices) {
            "The selected tab index must address the same navigation snapshot."
        }
        require(
            tabs.indices.all { index ->
                (0 until index).none { previousIndex ->
                    tabs[previousIndex] === tabs[index]
                }
            },
        ) {
            "An application child handle must not appear more than once."
        }
    }

    public val selectedSession: PersistedSessionViewModel?
        get() = when (selection) {
            ApplicationTabSelection.NewSession -> null
            is ApplicationTabSelection.SelectedTab -> tabs[selection.index]
        }
}
