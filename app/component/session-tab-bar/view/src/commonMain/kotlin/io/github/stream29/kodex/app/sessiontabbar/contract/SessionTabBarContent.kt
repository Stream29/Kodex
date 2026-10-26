package io.github.stream29.kodex.app.sessiontabbar.contract

import androidx.compose.runtime.*

@Composable
public actual fun SessionTabBarContent(
    state: SessionTabBarState,
    callbacks: SessionTabBarCallbacks,
    renderer: SessionTabBarRenderer,
    onOpenTabMenu: (SessionTabMenuRequest) -> Unit,
) {
    val currentState by rememberUpdatedState(state)
    val currentCallbacks by rememberUpdatedState(callbacks)
    val currentOnOpenTabMenu by rememberUpdatedState(onOpenTabMenu)
    renderer.Frame(
        state,
        sessions = { renderer.Sessions { currentCallbacks.openSessions() } },
        tab = { tab, index ->
            key(tab.identity) {
                renderer.Tab(
                    tab, selected = state.selected == tab.identity, first = index == 0,
                    onSelect = {
                        if (currentState.tabs.any { it.identity == tab.identity }) {
                            currentCallbacks.select(tab.identity)
                        }
                    },
                    onMenu = { placement ->
                        if (currentState.tabs.any { it.identity == tab.identity }) {
                            currentCallbacks.openContextMenu(tab.identity)
                            currentOnOpenTabMenu(SessionTabMenuRequest(tab.identity, tab.label, placement))
                        }
                    },
                )
            }
        },
        newSession = { renderer.NewSession(state.selected == null) { currentCallbacks.selectNewSession() } },
    )
}
