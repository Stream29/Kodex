package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.onPointerHover
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiContextMenu
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiPressable
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiInteractionTextStyle
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.cli.components.wrapToTerminalWidth
import io.github.stream29.kodex.cli.settings.SidebarContent

/** Renders one independently configured side of the session workspace. */
@Composable
internal fun SessionSidebar(
    side: SessionSidebarSide,
    content: SidebarContent,
    selectedAgent: AgentViewModel?,
    dropdownState: TuiDropdownState,
    columns: Int,
    rows: Int,
    onHoverChanged: (Boolean) -> Unit,
    onToggleExpanded: () -> Unit,
    onOpenShellSessionMenu: (ShellSessionMenuRequest) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(columns)
            .height(rows)
            .background(SettingsDialogNavigationBackground)
            .onPointerHover(
                onPointerEnter = { onHoverChanged(true) },
                onPointerExit = { onHoverChanged(false) },
            ),
    ) {
        SessionSidebarHeader(
            side = side,
            content = content,
            dropdownState = dropdownState,
            columns = columns,
            onToggleExpanded = onToggleExpanded,
        )
        if (content == SidebarContent.TerminalSessions && rows > 1) {
            TerminalSessionsSidebarBody(
                selectedAgent = selectedAgent,
                columns = columns,
                rows = rows - 1,
                onOpenShellSessionMenu = onOpenShellSessionMenu,
            )
        }
    }
}

@Composable
private fun SessionSidebarHeader(
    side: SessionSidebarSide,
    content: SidebarContent,
    dropdownState: TuiDropdownState,
    columns: Int,
    onToggleExpanded: () -> Unit,
) {
    val collapseButtonColumns = minOf(SessionSidebarCollapsedButtonColumns, columns)
    val titleColumns = columns - collapseButtonColumns
    Row(modifier = Modifier.fillMaxWidth().background(SettingsDialogHeaderBackground)) {
        if (side == SessionSidebarSide.Left) {
            SessionSidebarDirectionButton(
                label = side.collapseLabel,
                columns = collapseButtonColumns,
                onClick = onToggleExpanded,
            )
        }
        if (titleColumns > 0) {
            TuiDropdownTrigger(
                dropdownState = dropdownState,
                label = content.displayName,
                modifier = Modifier
                    .width(titleColumns)
                    .background(SettingsDialogHeaderBackground),
                color = SettingsDialogForeground,
            )
        }
        if (side == SessionSidebarSide.Right) {
            SessionSidebarDirectionButton(
                label = side.collapseLabel,
                columns = collapseButtonColumns,
                onClick = onToggleExpanded,
            )
        }
    }
}

@Composable
private fun SessionSidebarDirectionButton(
    label: String,
    columns: Int,
    onClick: () -> Unit,
) {
    if (columns <= 0) return
    TuiButton(
        label = label,
        modifier = Modifier.width(columns).background(SettingsDialogHeaderBackground),
        color = SettingsDialogForeground,
        onClick = onClick,
    )
}

@Composable
private fun TerminalSessionsSidebarBody(
    selectedAgent: AgentViewModel?,
    columns: Int,
    rows: Int,
    onOpenShellSessionMenu: (ShellSessionMenuRequest) -> Unit,
) {
    val shellSessionItems = collectOngoingShellSessions(selectedAgent).map { session ->
        ShellSessionSidebarItem(
            session = session,
            lines = shellSessionSidebarLines(
                sessionId = session.sessionId,
                command = session.arguments.command,
                columns = columns,
            ),
        )
    }
    LazyColumn(modifier = Modifier.width(columns).height(rows)) {
        items(shellSessionItems, key = { item -> item.session.sessionId }) { item ->
            ShellSessionSidebarRow(
                lines = item.lines,
                onOpenMenu = { anchor, clickPosition ->
                    onOpenShellSessionMenu(
                        ShellSessionMenuRequest(
                            session = item.session,
                            anchor = anchor,
                            clickPosition = clickPosition,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
internal fun SessionSidebarExpandButton(
    side: SessionSidebarSide,
    onHoverChanged: (Boolean) -> Unit,
    onExpand: () -> Unit,
) {
    TuiButton(
        label = side.expandLabel,
        onClick = onExpand,
        modifier = Modifier
            .width(SessionSidebarCollapsedButtonColumns)
            .height(SessionSidebarCollapsedButtonRows)
            .background(SettingsDialogHeaderBackground)
            .onPointerHover(
                onPointerEnter = { onHoverChanged(true) },
                onPointerExit = { onHoverChanged(false) },
            ),
        color = SettingsDialogForeground,
    )
}

@Composable
internal fun BoxScope.SessionSidebarContentMenu(
    dropdownState: TuiDropdownState,
    selected: SidebarContent,
    onSelect: (SidebarContent) -> Unit,
) {
    TuiDropdownMenu(
        dropdownState = dropdownState,
        options = SidebarContent.entries,
        selected = selected,
        optionLabel = SidebarContent::displayName,
        onSelect = { content ->
            dropdownState.dismiss()
            onSelect(content)
        },
        backgroundColor = SettingsDialogHomeBackground,
    )
}

@Composable
internal fun ShellSessionSidebarRow(
    lines: List<String>,
    onOpenMenu: (TuiPopupAnchor, IntOffset?) -> Unit,
) {
    val anchor = rememberTuiPopupAnchor()
    TuiPressable(
        onClick = {},
        onSecondaryClick = { position -> onOpenMenu(anchor, position) },
        modifier = Modifier.fillMaxWidth().tuiPopupAnchor(anchor),
    ) { _, hovered, pressed ->
        val textStyle = tuiInteractionTextStyle(
            hovered = hovered,
            pressed = pressed,
            idleTextStyle = TextStyle.Dim,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SettingsDialogNavigationBackground),
        ) {
            lines.forEach { line ->
                Text(
                    value = line,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SettingsDialogNavigationBackground),
                    color = SettingsDialogForeground,
                    textStyle = textStyle,
                )
            }
        }
    }
}

@Composable
internal fun BoxScope.ShellSessionContextMenu(
    request: ShellSessionMenuRequest?,
    onDismissRequest: () -> Unit,
) {
    val openRequest = request ?: return
    val completed by openRequest.session.completed.collectAsState()
    LaunchedEffect(openRequest, completed) {
        if (completed) onDismissRequest()
    }
    if (completed) return
    TuiContextMenu(
        expanded = true,
        anchor = openRequest.anchor,
        clickPosition = openRequest.clickPosition,
        onDismissRequest = onDismissRequest,
        backgroundColor = SettingsDialogHomeBackground,
    ) {
        TuiPopupMenuItem(
            key = "close-shell-session",
            onClick = openRequest.session::close,
        ) {
            Text("Close session")
        }
    }
}

@Composable
private fun collectOngoingShellSessions(selectedAgent: AgentViewModel?): List<AgentShellSession> {
    if (selectedAgent == null) return emptyList()
    return key(selectedAgent) {
        val sessions by selectedAgent.shellSessions.activeSessions.collectAsState()
        buildList {
            sessions.values.sortedBy(AgentShellSession::sessionId).forEach { session ->
                key(session.sessionId) {
                    val completed by session.completed.collectAsState()
                    if (!completed) add(session)
                }
            }
        }
    }
}

internal data class ShellSessionMenuRequest(
    val session: AgentShellSession,
    val anchor: TuiPopupAnchor,
    val clickPosition: IntOffset?,
)

internal enum class SessionSidebarSide(
    val collapseLabel: String,
    val expandLabel: String,
) {
    Left(collapseLabel = "←", expandLabel = "→"),
    Right(collapseLabel = "→", expandLabel = "←"),
}

internal val SidebarContent.displayName: String
    get() = when (this) {
        SidebarContent.None -> "None"
        SidebarContent.TerminalSessions -> "Terminal sessions"
    }

private data class ShellSessionSidebarItem(
    val session: AgentShellSession,
    val lines: List<String>,
)

internal fun shellSessionSidebarLines(
    sessionId: Int,
    command: String,
    columns: Int,
): List<String> = "$sessionId: $command".wrapToTerminalWidth(columns.coerceAtLeast(1))

internal fun canExpandSessionSidebar(
    columns: Int,
    oppositeExpanded: Boolean,
): Boolean {
    val sidebarsColumns = if (oppositeExpanded) {
        SessionSidebarExpandedColumns * 2
    } else {
        SessionSidebarExpandedColumns
    }
    return columns >= sidebarsColumns + SessionSidebarMinimumContentColumns
}

internal const val SessionSidebarExpandedColumns: Int = 28
internal const val SessionSidebarCollapsedButtonColumns: Int = 3
internal const val SessionSidebarCollapsedButtonRows: Int = 1
internal const val SessionSidebarMinimumContentColumns: Int = 1
