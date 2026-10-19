package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabBarCallbacks
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabBarState
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabIdentity
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabPresentation
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.sessiontabbar.SessionTabBar as ComponentSessionTabBar

/**
 * Maps persisted host fixtures to component presentation identities for renderer tests.
 */
@Composable
internal fun SessionTabBar(
    tabs: List<SessionTabRenderState>,
    runningIndicatorFrame: State<String>,
    columns: Int,
    onSelectTab: (PersistedSessionViewModel) -> Unit,
    onOpenTabMenu: (PersistedSessionViewModel, String, TuiPopupAnchor, IntOffset?) -> Unit,
    onSelectNewSession: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val entries = tabs.mapIndexed { index, tab ->
        val identity = SessionTabIdentity(
            "test-tab-$index-${System.identityHashCode(tab.target)}",
        )
        identity to tab
    }
    val byIdentity = entries.associate { it.first to it.second }
    ComponentSessionTabBar(
        state = SessionTabBarState(
            tabs = entries.map { (identity, tab) ->
                SessionTabPresentation(
                    identity = identity,
                    label = tab.sessionName,
                    running = tab.running,
                )
            },
            selected = entries.firstOrNull { it.second.selected }?.first,
        ),
        runningIndicatorFrame = runningIndicatorFrame,
        columns = columns,
        callbacks = object : SessionTabBarCallbacks {
            override fun select(identity: SessionTabIdentity) {
                byIdentity[identity]?.target?.let(onSelectTab)
            }

            override fun close(identity: SessionTabIdentity) = Unit

            override fun openContextMenu(identity: SessionTabIdentity) = Unit

            override fun selectNewSession() = onSelectNewSession()

            override fun openSessions() = onOpenSessions()
        },
        onOpenTabMenu = { request ->
            val tab = byIdentity[request.identity] ?: return@ComponentSessionTabBar
            onOpenTabMenu(
                tab.target,
                tab.sessionName,
                request.anchor,
                request.clickPosition,
            )
        },
    )
}
