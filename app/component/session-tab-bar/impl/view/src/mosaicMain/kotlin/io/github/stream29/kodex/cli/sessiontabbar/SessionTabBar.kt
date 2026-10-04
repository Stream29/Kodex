package io.github.stream29.kodex.cli.sessiontabbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabBarCallbacks
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabBarState
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabIdentity
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabPresentation
import io.github.stream29.kodex.cli.components.ScrollOrientation
import io.github.stream29.kodex.cli.components.ScrollState
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.components.horizontalScroll
import io.github.stream29.kodex.cli.components.rememberScrollState
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.runningIndicatorLabel
import io.github.stream29.kodex.cli.components.scrollablePaging
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth

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
public fun SessionTabBar(
    state: SessionTabBarState,
    callbacks: SessionTabBarCallbacks,
    runningIndicatorFrame: State<String>,
    columns: Int,
    onOpenTabMenu: (SessionTabContextMenuRequest) -> Unit = {},
) {
    val tabColumns = (columns - TabControlsColumns).coerceAtLeast(1)
    val scrollState = rememberScrollState()
    val currentState by rememberUpdatedState(state)
    val currentCallbacks by rememberUpdatedState(callbacks)
    val presentations = state.tabs.map { tab ->
        RenderedTab(
            tab = tab,
            label = runningIndicatorLabel(
                name = tab.label,
                running = tab.running,
                frame = runningIndicatorFrame.value,
            ).ellipsizeToTerminalWidth(MaximumTabLabelColumns),
            selected = tab.identity == state.selected,
        )
    }
    val selectedIndex = presentations.indexOfFirst(RenderedTab::selected)
    val selectedBounds = remember(
        presentations.map(RenderedTab::label),
        selectedIndex,
    ) {
        sessionTabBounds(
            labels = presentations.map(RenderedTab::label),
            index = selectedIndex,
        )
    }
    LaunchedEffect(selectedBounds, tabColumns, scrollState.maxValue, scrollState.viewportSize) {
        selectedBounds?.let { bounds ->
            if (scrollState.viewportSize > 0 && scrollState.maxValue != Int.MAX_VALUE) {
                scrollState.ensureSessionTabRangeVisible(bounds)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SessionTopBarBackground)
            .scrollablePaging(
                state = scrollState,
                viewportSize = { scrollState.viewportSize },
                orientation = ScrollOrientation.Horizontal,
            ),
    ) {
        TuiButton(
            label = "Sessions",
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionButtonForeground,
            onClick = currentCallbacks::openSessions,
        )
        Text(" ")
        Row(
            modifier = Modifier
                .width(tabColumns)
                .horizontalScroll(scrollState),
        ) {
            presentations.forEachIndexed { index, rendered ->
                if (index != 0) Text(" ")
                key(rendered.tab.identity) {
                    SessionTabButton(
                        rendered = rendered,
                        currentState = { currentState },
                        callbacks = { currentCallbacks },
                        onOpenTabMenu = onOpenTabMenu,
                    )
                }
            }
        }
        Text(" ")
        TuiButton(
            label = "+",
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionButtonForeground,
            onClick = currentCallbacks::createNewSession,
        )
    }
}

@Composable
private fun SessionTabButton(
    rendered: RenderedTab,
    currentState: () -> SessionTabBarState,
    callbacks: () -> SessionTabBarCallbacks,
    onOpenTabMenu: (SessionTabContextMenuRequest) -> Unit,
) {
    val anchor = rememberTuiPopupAnchor()
    val currentOnOpenTabMenu by rememberUpdatedState(onOpenTabMenu)
    val currentTab = rendered.tab
    TuiButton(
        label = rendered.label,
        modifier = Modifier
            .background(SessionTopBarBackground)
            .tuiPopupAnchor(anchor),
        color = SessionForeground,
        selected = rendered.selected,
        onClick = {
            if (currentState().tabs.any { it.identity == currentTab.identity }) {
                callbacks().select(currentTab.identity)
            }
        },
        onSecondaryClick = { position ->
            if (currentState().tabs.any { it.identity == currentTab.identity }) {
                callbacks().openContextMenu(currentTab.identity)
                currentOnOpenTabMenu(
                    SessionTabContextMenuRequest(
                        identity = currentTab.identity,
                        label = currentTab.label,
                        anchor = anchor,
                        clickPosition = position,
                    ),
                )
            }
        },
    )
}

private data class RenderedTab(
    val tab: SessionTabPresentation,
    val label: String,
    val selected: Boolean,
)

/** Bounds include the visible brackets and exclude the inter-tab separator. */
public data class SessionTabBounds(
    public val start: Int,
    public val endExclusive: Int,
)

/**
 * Computes terminal-cell bounds in the immutable presentation order.
 * Invalid indices return null so a replacement/empty snapshot cannot move the
 * viewport to a stale tab.
 */
public fun sessionTabBounds(
    labels: List<String>,
    index: Int,
): SessionTabBounds? {
    if (index !in labels.indices) return null
    var start = 0
    labels.take(index).forEach { label ->
        start += label.terminalCellWidth() + TabBracketsColumns + TabSpacingColumns
    }
    return SessionTabBounds(
        start = start,
        endExclusive = start + labels[index].terminalCellWidth() + TabBracketsColumns,
    )
}

/** Moves the renderer-only horizontal viewport until [bounds] is visible. */
public fun ScrollState.ensureSessionTabRangeVisible(bounds: SessionTabBounds) {
    val viewportEnd = value + viewportSize
    when {
        bounds.start < value -> scrollTo(bounds.start)
        bounds.endExclusive > viewportEnd -> scrollTo(bounds.endExclusive - viewportSize)
    }
}

private const val MaximumTabLabelColumns: Int = 20
private const val TabBracketsColumns: Int = 2
private const val TabSpacingColumns: Int = 1
private const val TabControlsColumns: Int = 15

private val SessionForeground
    @Composable
    get() = io.github.stream29.kodex.cli.components.TuiTheme.colorScheme.onSurface

private val SessionButtonForeground
    @Composable
    get() = io.github.stream29.kodex.cli.components.TuiTheme.colorScheme.onPrimaryContainer

private val SessionTopBarBackground
    @Composable
    get() = com.jakewharton.mosaic.ui.Color.Unspecified

private val SessionButtonBackground
    @Composable
    get() = io.github.stream29.kodex.cli.components.TuiTheme.colorScheme.primaryContainer
