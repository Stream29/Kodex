package io.github.stream29.kodex.app.application.contract

import io.github.stream29.kodex.app.session.contract.SessionViewModel

/**
 * Atomic ordered tab registry and selected index.
 *
 * Child mutable state stays in each [SessionViewModel]. This parent state
 * contains only the parent-owned ordering and selected position. Render tabs
 * in this order and render [selected] from this same snapshot, using the child
 * instance as the callback target. Selecting another tab does not close it.
 *
 * @throws IllegalArgumentException if tabs are empty, the selection is outside
 * this snapshot, or the same child instance appears more than once.
 */
public data class ApplicationNavigationState(
    public val tabs: List<SessionViewModel>,
    public val selectedIndex: Int,
) {
    init {
        require(tabs.isNotEmpty()) {
            "Application navigation must contain a selected tab."
        }
        require(selectedIndex in tabs.indices) {
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

    public val selected: SessionViewModel
        get() = tabs[selectedIndex]
}
