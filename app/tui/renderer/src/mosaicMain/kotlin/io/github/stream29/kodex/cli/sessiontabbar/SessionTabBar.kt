package io.github.stream29.kodex.cli.sessiontabbar

import androidx.compose.runtime.*
import com.jakewharton.mosaic.focus.focusCursor
import com.jakewharton.mosaic.focus.focusable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.sessiontabbar.contract.*
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabMenuRequest as SharedTabMenuRequest

@Composable
public actual fun SessionTabBar(
    state: SessionTabBarState,
    callbacks: SessionTabBarCallbacks,
    runningIndicatorFrame: State<String>,
    columns: Int,
    onOpenTabMenu: (SessionTabContextMenuRequest) -> Unit,
) {
    SessionTabBarContent(
        state, callbacks, rememberMosaicSessionTabBarRenderer(columns, runningIndicatorFrame),
        onOpenTabMenu = { request: SharedTabMenuRequest ->
            val placement = request.placement as MosaicSessionTabMenuPlacement
            onOpenTabMenu(SessionTabContextMenuRequest(request.identity, request.label, placement.anchor, placement.clickPosition))
        },
    )
}

@Composable
public actual fun rememberMosaicSessionTabBarRenderer(
    columns: Int, runningIndicatorFrame: State<String>,
): SessionTabBarRenderer = remember(columns, runningIndicatorFrame) { MosaicSessionTabBarRenderer(columns, runningIndicatorFrame) }

private class MosaicSessionTabBarRenderer(
    private val columns: Int, private val runningIndicatorFrame: State<String>,
) : SessionTabBarRenderer {
    @Composable
    override fun Frame(
        state: SessionTabBarState, sessions: @Composable () -> Unit,
        tab: @Composable (SessionTabPresentation, Int) -> Unit, newSession: @Composable () -> Unit,
    ) {
        val tabColumns = (columns - TabControlsColumns).coerceAtLeast(1)
        val scrollState = rememberScrollState()
        val controlsScrollState = rememberScrollState()
        val labels = state.tabs.map { terminalTabLabel(it, runningIndicatorFrame.value) }
        val selectedIndex = state.tabs.indexOfFirst { it.identity == state.selected }
        val selectedBounds = remember(labels, selectedIndex) { sessionTabBounds(labels, selectedIndex) }
        LaunchedEffect(selectedBounds, tabColumns, scrollState.maxValue, scrollState.viewportSize) {
            selectedBounds?.let { bounds ->
                if (scrollState.viewportSize > 0 && scrollState.maxValue != Int.MAX_VALUE) {
                    scrollState.ensureSessionTabRangeVisible(bounds)
                }
            }
        }
        // Tiny surfaces retain a tab viewport and page the outer row to reach its controls.
        Box(
            Modifier.width(columns.coerceAtLeast(1)).horizontalScroll(controlsScrollState)
                .focusCursor(0).focusable(enabled = columns < MinimumTabBarColumns),
        ) {
            Row(
                Modifier.width(columns.coerceAtLeast(MinimumTabBarColumns)).background(SessionTopBarBackground)
                    .scrollablePaging(scrollState, viewportSize = { scrollState.viewportSize }, orientation = ScrollOrientation.Horizontal),
            ) {
                sessions()
                Text(" ")
                Row(Modifier.width(tabColumns).horizontalScroll(scrollState)) {
                    state.tabs.forEachIndexed { index, presentation -> tab(presentation, index) }
                }
                Text(" ")
                newSession()
            }
        }
    }

    @Composable
    override fun Sessions(onClick: () -> Unit) {
        TuiButton("Sessions", Modifier.background(SessionButtonBackground), color = SessionButtonForeground, onClick = onClick)
    }

    @Composable
    override fun Tab(
        tab: SessionTabPresentation, selected: Boolean, first: Boolean,
        onSelect: () -> Unit, onMenu: (SessionTabMenuPlacement) -> Unit,
    ) {
        if (!first) Text(" ")
        val anchor = rememberTuiPopupAnchor()
        TuiButton(
            label = terminalTabLabel(tab, runningIndicatorFrame.value),
            modifier = Modifier.background(SessionTopBarBackground).tuiPopupAnchor(anchor),
            color = SessionForeground, selected = selected, onClick = onSelect,
            onSecondaryClick = { position -> onMenu(MosaicSessionTabMenuPlacement(anchor, position)) },
        )
    }

    @Composable
    override fun NewSession(selected: Boolean, onClick: () -> Unit) {
        TuiButton("+", Modifier.background(SessionButtonBackground), color = SessionButtonForeground, selected = selected, onClick = onClick)
    }
}

private fun terminalTabLabel(tab: SessionTabPresentation, frame: String): String =
    runningIndicatorLabel(tab.label, tab.running, frame).ellipsizeToTerminalWidth(MaximumTabLabelColumns)

public actual fun sessionTabBounds(labels: List<String>, index: Int): SessionTabBounds? {
    if (index !in labels.indices) return null
    var start = 0
    labels.take(index).forEach { label -> start += label.terminalCellWidth() + TabBracketsColumns + TabSpacingColumns }
    return SessionTabBounds(start, start + labels[index].terminalCellWidth() + TabBracketsColumns)
}

public actual fun ScrollState.ensureSessionTabRangeVisible(bounds: SessionTabBounds) {
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
private const val MinimumTabBarColumns: Int = TabControlsColumns + 1

private val SessionForeground
    @Composable get() = TuiTheme.colorScheme.onSurface
private val SessionButtonForeground
    @Composable get() = TuiTheme.colorScheme.onPrimaryContainer
private val SessionTopBarBackground
    @Composable get() = com.jakewharton.mosaic.ui.Color.Unspecified
private val SessionButtonBackground
    @Composable get() = TuiTheme.colorScheme.primaryContainer
