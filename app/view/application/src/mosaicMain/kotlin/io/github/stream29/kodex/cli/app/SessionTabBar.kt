package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
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
                    val summary by target.summary.collectAsState()
                    summary.rootRunning
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
    val tabColumns = (columns - TabControlsColumns).coerceAtLeast(0)
    val visible = visibleTabs(tabs, tabColumns)
    Row(modifier = Modifier.fillMaxWidth().background(SessionTopBarBackground)) {
        TuiButton(
            label = "Sessions",
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionForeground,
            onClick = onOpenSessions,
        )
        Text(" ")
        visible.forEachIndexed { index, entry ->
            if (index != 0) Text(" ")
            key(entry.target) {
                SessionTab(
                    entry = entry,
                    runningIndicatorFrame = runningIndicatorFrame,
                    maximumLabelColumns = tabLabelColumns(entry, tabColumns, visible.size),
                    onClick = { onSelectTab(entry.target) },
                    onOpenMenu = onOpenTabMenu,
                )
            }
        }
        if (visible.isNotEmpty()) Text(" ")
        TuiButton(
            label = "+",
            modifier = Modifier.background(SessionButtonBackground),
            color = SessionForeground,
            onClick = onCreateNewSession,
        )
    }
}

@Composable
private fun SessionTab(
    entry: SessionTabRenderState,
    runningIndicatorFrame: State<String>,
    maximumLabelColumns: Int,
    onClick: () -> Unit,
    onOpenMenu: (SessionViewModel, String, TuiPopupAnchor, IntOffset?) -> Unit,
) {
    val anchor = rememberTuiPopupAnchor()
    val label = runningIndicatorLabel(
        name = entry.sessionName,
        running = entry.running,
        frame = runningIndicatorFrame.value,
    )
    TuiButton(
        label = label.ellipsizeToTerminalWidth(maximumLabelColumns),
        modifier = Modifier
            .background(if (entry.selected) SessionButtonBackground else SessionTopBarBackground)
            .tuiPopupAnchor(anchor),
        color = SessionForeground,
        idleTextStyle = if (entry.selected) TextStyle.Bold else TextStyle.Unspecified,
        onSecondaryClick = { position ->
            onOpenMenu(entry.target, entry.sessionName, anchor, position)
        },
        onClick = onClick,
    )
}

private fun visibleTabs(
    tabs: List<SessionTabRenderState>,
    availableColumns: Int,
): List<SessionTabRenderState> {
    val active = tabs.firstOrNull(SessionTabRenderState::selected)
    val ordered = listOfNotNull(active) + tabs.filter { entry -> entry !== active }
    var remaining = availableColumns
    return buildList {
        ordered.forEach { entry ->
            val required = minimumTabColumns(entry) + if (isEmpty()) 0 else TabSpacingColumns
            if (required <= remaining) {
                add(entry)
                remaining -= required
            }
        }
    }.sortedBy(tabs::indexOf)
}

private fun tabLabelColumns(
    entry: SessionTabRenderState,
    availableColumns: Int,
    tabCount: Int,
): Int {
    val spacing = (tabCount - 1).coerceAtLeast(0) * TabSpacingColumns
    val availableForLabels =
        (availableColumns - spacing - tabCount * TabBracketsColumns).coerceAtLeast(tabCount)
    val equalShare = (availableForLabels / tabCount.coerceAtLeast(1)).coerceAtLeast(1)
    return minOf(equalShare, MaximumTabLabelColumns).coerceAtLeast(minimumLabelColumns(entry))
}

private fun minimumTabColumns(entry: SessionTabRenderState): Int =
    minimumLabelColumns(entry) + TabBracketsColumns

private fun minimumLabelColumns(entry: SessionTabRenderState): Int = when (val target = entry.target) {
    is NewSessionViewModel -> "new".terminalCellWidth()
    is PersistedSessionViewModel -> "s${target.sessionIndex}".terminalCellWidth()
}

private const val MaximumTabLabelColumns: Int = 20
private const val TabBracketsColumns: Int = 2
private const val TabSpacingColumns: Int = 1
private const val TabControlsColumns: Int = 15
