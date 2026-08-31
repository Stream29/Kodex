package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.cli.components.ScrollState
import io.github.stream29.kodex.cli.components.ScrollOrientation
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.components.horizontalScroll
import io.github.stream29.kodex.cli.components.rememberScrollState
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.scrollablePaging
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth

internal data class SessionTabRenderState(
    val target: SessionViewModel,
    val selected: Boolean,
    val sessionName: String,
    val running: Boolean = false,
)

@Composable
internal fun collectSessionTabRenderStates(
    tabs: List<SessionViewModel>,
    selectedIndex: Int,
): List<SessionTabRenderState> = buildList(tabs.size) {
    tabs.forEachIndexed { index, target ->
        key(target) {
            val name by target.name.collectAsState()
            val running = when (target) {
                is NewSessionViewModel -> false
                is PersistedSessionViewModel -> {
                    val execution by target.rootAgent.execution.collectAsState()
                    execution.running
                }
            }
            add(
                SessionTabRenderState(
                    target = target,
                    selected = index == selectedIndex,
                    sessionName = name,
                    running = running,
                ),
            )
        }
    }
}

@Composable
internal fun SessionTabBar(
    tabs: List<SessionTabRenderState>,
    runningIndicatorFrame: State<String>,
    columns: Int,
    onSelectTab: (SessionViewModel) -> Unit,
    onOpenTabMenu: (SessionViewModel, String, TuiPopupAnchor, IntOffset?) -> Unit,
    onCreateNewSession: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val tabColumns = (columns - TabControlsColumns).coerceAtLeast(1)
    val scrollState = rememberScrollState()
    val presentations = tabs.map { entry ->
        SessionTabPresentation(
            entry = entry,
            label = runningIndicatorLabel(
                name = entry.sessionName,
                running = entry.running,
                frame = runningIndicatorFrame.value,
            ).ellipsizeToTerminalWidth(MaximumTabLabelColumns),
        )
    }
    val selectedIndex = presentations.indexOfFirst { presentation ->
        presentation.entry.selected
    }
    val selectedBounds = remember(presentations.map(SessionTabPresentation::label), selectedIndex) {
        sessionTabBounds(
            labels = presentations.map(SessionTabPresentation::label),
            index = selectedIndex,
        )
    }
    LaunchedEffect(
        selectedBounds,
        tabColumns,
        scrollState.maxValue,
        scrollState.viewportSize,
    ) {
        selectedBounds?.let { bounds ->
            if (scrollState.viewportSize > 0 && scrollState.maxValue != Int.MAX_VALUE) {
                scrollState.ensureRangeVisible(bounds)
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
            onClick = onOpenSessions,
        )
        Text(" ")
        Row(
            modifier = Modifier
                .width(tabColumns)
                .horizontalScroll(scrollState),
        ) {
            presentations.forEachIndexed { index, presentation ->
                if (index != 0) Text(" ")
                key(presentation.entry.target) {
                    SessionTab(
                        presentation = presentation,
                        onClick = { onSelectTab(presentation.entry.target) },
                        onOpenMenu = onOpenTabMenu,
                    )
                }
            }
        }
        Text(" ")
        TuiButton(
            label = "+",
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionButtonForeground,
            onClick = onCreateNewSession,
        )
    }
}

@Composable
private fun SessionTab(
    presentation: SessionTabPresentation,
    onClick: () -> Unit,
    onOpenMenu: (SessionViewModel, String, TuiPopupAnchor, IntOffset?) -> Unit,
) {
    val anchor = rememberTuiPopupAnchor()
    val entry = presentation.entry
    TuiButton(
        label = presentation.label,
        modifier = Modifier
            .background(SessionTopBarBackground)
            .tuiPopupAnchor(anchor),
        color = SessionForeground,
        selected = entry.selected,
        onSecondaryClick = { position ->
            onOpenMenu(entry.target, entry.sessionName, anchor, position)
        },
        onClick = onClick,
    )
}

private data class SessionTabPresentation(
    val entry: SessionTabRenderState,
    val label: String,
)

internal data class SessionTabBounds(
    val start: Int,
    val endExclusive: Int,
)

internal fun sessionTabBounds(
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

internal fun ScrollState.ensureRangeVisible(bounds: SessionTabBounds) {
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
