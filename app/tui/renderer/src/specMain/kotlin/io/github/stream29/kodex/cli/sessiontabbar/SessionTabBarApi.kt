package io.github.stream29.kodex.cli.sessiontabbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabBarCallbacks
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabBarState
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabIdentity
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabBarRenderer
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabMenuPlacement
import io.github.stream29.kodex.cli.components.ScrollState
import io.github.stream29.kodex.cli.components.TuiPopupAnchor

/**
 * Geometry-bearing context-menu request. Mosaic anchor and pointer position
 * stay in this view module; the host receives the exact [identity] and must
 * still verify admission before opening its root popup state.
 */
public data class SessionTabContextMenuRequest(
    public val identity: SessionTabIdentity,
    public val label: String,
    public val anchor: TuiPopupAnchor,
    public val clickPosition: IntOffset?,
)

public data class MosaicSessionTabMenuPlacement(
    public val anchor: TuiPopupAnchor,
    public val clickPosition: IntOffset?,
) : SessionTabMenuPlacement

@Composable
public expect fun rememberMosaicSessionTabBarRenderer(
    columns: Int, runningIndicatorFrame: State<String>,
): SessionTabBarRenderer

/** Bounds include the visible brackets and exclude the inter-tab separator. */
public data class SessionTabBounds(
    public val start: Int,
    public val endExclusive: Int,
)

/**
 * Renders the immutable snapshot supplied by Application.
 *
 * The view owns horizontal scroll, selected-tab viewport correction, focus,
 * anchor and context-menu geometry. It does not collect a registry, retain a
 * selected index, close a Session, or create a second mutable tab list.
 * Primary click calls [SessionTabBarCallbacks.select] with the exact identity;
 * secondary click calls [SessionTabBarCallbacks.openContextMenu] and then
 * [onOpenTabMenu] with the same identity. Removed identities are rejected by
 * a current-snapshot admission check before either callback.
 */
@Composable
public expect fun SessionTabBar(
    state: SessionTabBarState,
    callbacks: SessionTabBarCallbacks,
    runningIndicatorFrame: State<String>,
    columns: Int,
    onOpenTabMenu: (SessionTabContextMenuRequest) -> Unit = {},
): Unit

/**
 * Computes terminal-cell bounds in the immutable presentation order.
 * Invalid indices return null so a replacement/empty snapshot cannot move the
 * viewport to a stale tab.
 */
public expect fun sessionTabBounds(
    labels: List<String>,
    index: Int,
): SessionTabBounds?

/** Moves the renderer-only horizontal viewport until [bounds] is visible. */
public expect fun ScrollState.ensureSessionTabRangeVisible(bounds: SessionTabBounds): Unit
