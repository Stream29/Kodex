package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth

/** Application session navigation: catalog access, visible tabs, and a New-session creation action. */
@Composable
internal fun SessionTabBar(
    tabs: List<SessionTabViewState>,
    columns: Int,
    onSelectTab: (SessionTabTarget) -> Unit,
    onOpenTabMenu: (SessionTabTarget, String, TuiPopupAnchor) -> Unit,
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
    entry: SessionTabViewState,
    maximumLabelColumns: Int,
    onClick: () -> Unit,
    onOpenMenu: (SessionTabTarget, String, TuiPopupAnchor) -> Unit,
) {
    val sessionTarget = when (val target = entry.target) {
        is SessionTabTarget.NewSession -> {
            val sessionName = entry.newSessionName
                ?: if (target.ordinal == 1) "New session" else "New session ${target.ordinal}"
            SessionTabButton(
                entry = entry,
                sessionName = sessionName,
                label = sessionName,
                maximumLabelColumns = maximumLabelColumns,
                onClick = onClick,
                onOpenMenu = onOpenMenu,
            )
            return
        }

        is SessionTabTarget.OpenSession -> target
    }
    val rootSession = requireNotNull(entry.rootSession)
    val rootTree by requireNotNull(rootSession.viewModel).state.collectAsState()
    val root = rootTree.agents.firstOrNull { agent -> agent.agentId == rootTree.rootAgentId }
    if (root == null) {
        SessionTabButton(
            entry = entry,
            sessionName = "Session ${sessionTarget.sessionIndex}",
            label = "Session ${sessionTarget.sessionIndex}",
            maximumLabelColumns = maximumLabelColumns,
            onClick = onClick,
            onOpenMenu = onOpenMenu,
        )
        return
    }
    val rootState by root.viewModel.state.collectAsState()
    val sessionName = rootState.durable.settings?.threadName
        ?.takeIf(String::isNotBlank)
        ?: "Session ${sessionTarget.sessionIndex}"
    SessionTabButton(
        entry = entry,
        sessionName = sessionName,
        label = if (rootState.running) "$sessionName *" else sessionName,
        maximumLabelColumns = maximumLabelColumns,
        onClick = onClick,
        onOpenMenu = onOpenMenu,
    )
}

@Composable
private fun SessionTabButton(
    entry: SessionTabViewState,
    sessionName: String,
    label: String,
    maximumLabelColumns: Int,
    onClick: () -> Unit,
    onOpenMenu: (SessionTabTarget, String, TuiPopupAnchor) -> Unit,
) {
    val tabMenuAnchor = rememberTuiPopupAnchor()
    TuiButton(
        label = label.ellipsizeToTerminalWidth(maximumLabelColumns),
        modifier = Modifier
            .background(if (entry.selected) SessionButtonBackground else SessionTopBarBackground)
            .tuiPopupAnchor(tabMenuAnchor),
        color = SessionForeground,
        idleTextStyle = if (entry.selected) TextStyle.Bold else TextStyle.Unspecified,
        onSecondaryClick = { onOpenMenu(entry.target, sessionName, tabMenuAnchor) },
        onClick = onClick,
    )
}

private fun visibleTabs(
    tabs: List<SessionTabViewState>,
    availableColumns: Int,
): List<SessionTabViewState> {
    val active = tabs.firstOrNull { entry -> entry.selected }
    val ordered = listOfNotNull(active) + tabs.filter { entry -> entry != active }
    var remaining = availableColumns
    return buildList {
        ordered.forEach { entry ->
            val required = minimumTabColumns(entry) + if (isEmpty()) 0 else TabSpacingColumns
            if (required <= remaining) {
                add(entry)
                remaining -= required
            }
        }
    }.sortedBy { entry -> tabs.indexOf(entry) }
}

private fun tabLabelColumns(entry: SessionTabViewState, availableColumns: Int, tabCount: Int): Int {
    val spacing = (tabCount - 1).coerceAtLeast(0) * TabSpacingColumns
    val availableForLabels = (availableColumns - spacing - tabCount * TabBracketsColumns).coerceAtLeast(tabCount)
    val equalShare = (availableForLabels / tabCount.coerceAtLeast(1)).coerceAtLeast(1)
    return minOf(equalShare, MaximumTabLabelColumns).coerceAtLeast(minimumLabelColumns(entry))
}

private fun minimumTabColumns(entry: SessionTabViewState): Int =
    minimumLabelColumns(entry) + TabBracketsColumns

private fun minimumLabelColumns(entry: SessionTabViewState): Int = when (val target = entry.target) {
    is SessionTabTarget.NewSession -> "new".terminalCellWidth()
    is SessionTabTarget.OpenSession -> "s${target.sessionIndex}".terminalCellWidth()
}

private const val MaximumTabLabelColumns: Int = 20
private const val TabBracketsColumns: Int = 2
private const val TabSpacingColumns: Int = 1
private const val TabControlsColumns: Int = 15
