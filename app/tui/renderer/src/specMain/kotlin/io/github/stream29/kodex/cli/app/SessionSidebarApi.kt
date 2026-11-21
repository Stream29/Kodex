@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.historyindex.HistoryIndexInteractionRequest
import io.github.stream29.kodex.cli.historyindex.HistoryIndexMenuRequest
import io.github.stream29.kodex.cli.settings.SidebarContent

public const val SessionSidebarCollapsedButtonColumns: Int = 3
public const val SessionSidebarCollapsedButtonRows: Int = 1
public const val SessionSidebarSplitterColumns: Int = 1

/** Renderer request retains the exact process handle, not a process-id lookup on another Agent. */
public data class ShellSessionMenuRequest(
    public val session: AgentShellSession,
    public val anchor: TuiPopupAnchor,
    public val clickPosition: IntOffset?,
)

@Stable
public expect class ShellSessionInteractionRequest(
    side: SessionSidebarSide,
    session: AgentShellSession,
    anchor: TuiPopupAnchor,
) {
    public val side: SessionSidebarSide
    public val session: AgentShellSession
    public val anchor: TuiPopupAnchor
}

public data class SessionSidebarColumns(
    public val left: Int,
    public val content: Int,
    public val right: Int,
)

/** Renders one independently configured side, borrowing its exact Agent and children. */
@Composable
public expect fun SessionSidebar(
    side: SessionSidebarSide,
    content: SidebarContent,
    selectedAgent: AgentViewModel?,
    dropdownState: TuiDropdownState? = null,
    columns: Int,
    rows: Int,
    onHoverChanged: ((Boolean) -> Unit)? = null,
    onToggleExpanded: () -> Unit,
    onOpenShellSessionMenu: (ShellSessionMenuRequest) -> Unit,
    onShellSessionHoverChanged: (ShellSessionInteractionRequest, Boolean) -> Unit = { _, _ -> },
    onHistoryIndexHoverChanged: (HistoryIndexInteractionRequest, Boolean) -> Unit = { _, _ -> },
    onOpenHistoryIndexMenu: (HistoryIndexMenuRequest) -> Unit = {},
    resizable: Boolean = true,
    onResizeStart: (Int) -> Unit = {},
    onResize: (Int) -> Unit = {},
    onResizeEnd: (Int) -> Unit = {},
): Unit

/** Hosts a sidebar body beside its existing resize splitter. [content] receives the body width. */
@Composable
public expect fun SessionSidebarFrame(
    side: SessionSidebarSide,
    columns: Int,
    rows: Int,
    modifier: Modifier = Modifier,
    resizable: Boolean = true,
    onResizeStart: (Int) -> Unit = {},
    onResize: (Int) -> Unit = {},
    onResizeEnd: (Int) -> Unit = {},
    content: @Composable (Int) -> Unit,
): Unit

@Composable
public expect fun SessionSidebarExpandButton(
    side: SessionSidebarSide,
    onHoverChanged: (Boolean) -> Unit,
    onExpand: () -> Unit,
): Unit

@Composable
public expect fun BoxScope.SessionSidebarContentMenu(
    dropdownState: TuiDropdownState,
    selected: SidebarContent,
    onSelect: (SidebarContent) -> Unit,
): Unit

public expect fun canExpandSessionSidebar(
    columns: Int,
    requestedColumns: Int,
    oppositeColumns: Int,
): Boolean

public expect fun clampSessionSidebarResize(
    columns: Int,
    oppositeColumns: Int,
    requestedColumns: Int,
): Int?

public expect fun resolveSessionSidebarColumns(
    columns: Int,
    leftColumns: Int,
    rightColumns: Int,
): SessionSidebarColumns
