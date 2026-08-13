package io.github.stream29.kodex.app.application.contract

import io.github.stream29.kodex.app.session.contract.SessionViewModel

/**
 * Atomic ordered tab registry and selected index.
 *
 * Child mutable state stays in each [SessionViewModel]. This parent state
 * contains only the parent-owned ordering and selected position.
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
