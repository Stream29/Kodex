package io.github.stream29.kodex.app.sessiontabbar.contract

import androidx.compose.runtime.Composable

/** Opaque view-owned anchor/pointer snapshot; never enters Application or Session state. */
public interface SessionTabMenuPlacement

/** Each activation is a fresh opening even for identical target and placement values. */
public class SessionTabMenuRequest(
    public val identity: SessionTabIdentity,
    public val label: String,
    public val placement: SessionTabMenuPlacement,
)

public interface SessionTabBarRenderer {
    @Composable public fun Frame(
        state: SessionTabBarState,
        sessions: @Composable () -> Unit,
        tab: @Composable (SessionTabPresentation, index: Int) -> Unit,
        newSession: @Composable () -> Unit,
    )
    @Composable public fun Sessions(onClick: () -> Unit)
    @Composable public fun Tab(
        tab: SessionTabPresentation, selected: Boolean, first: Boolean,
        onSelect: () -> Unit, onMenu: (SessionTabMenuPlacement) -> Unit,
    )
    @Composable public fun NewSession(selected: Boolean, onClick: () -> Unit)
}

/**
 * Shared order and exact-identity callback admission against the current supplied snapshot.
 * The renderer retains only focus, viewport, running animation and placement.
 */
@Composable
public expect fun SessionTabBarContent(
    state: SessionTabBarState,
    callbacks: SessionTabBarCallbacks,
    renderer: SessionTabBarRenderer,
    onOpenTabMenu: (SessionTabMenuRequest) -> Unit = {},
): Unit
