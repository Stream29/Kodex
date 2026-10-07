package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.cli.historyindex.HistoryIndexSidebarBody
import io.github.stream29.kodex.cli.historyindex.HistoryIndexInteractionRequest
import io.github.stream29.kodex.cli.historyindex.HistoryIndexMenuRequest
import io.github.stream29.kodex.cli.historyindex.HistoryIndexSide
import io.github.stream29.kodex.cli.components.SettingsDialogHomeBackground

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.LayoutCoordinates
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.drawBehind
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.heightIn
import com.jakewharton.mosaic.layout.onPlaced
import com.jakewharton.mosaic.layout.onPointerEvent
import com.jakewharton.mosaic.layout.onPointerHover
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.layout.widthIn
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.unit.IntSize
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.cli.components.EllipsizedText
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiContextMenu
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPopupAnchorBounds
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiPopupPositionProvider
import io.github.stream29.kodex.cli.components.TuiPressable
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.TuiPopup
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiInteractionTextStyle
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.cli.components.wrapToTerminalWidth
import io.github.stream29.kodex.cli.history.RequestUserInputHistoryRow
import io.github.stream29.kodex.cli.history.RequestUserInputHistoryRowModel
import io.github.stream29.kodex.cli.settings.MinimumSidebarWidthColumns
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.utils.terminaltext.takeFirstFittingTerminalWidth
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** Renders one independently configured side, borrowing its exact Agent and children. */
@Composable
public fun SessionSidebar(
    side: SessionSidebarSide,
    content: SidebarContent,
    selectedAgent: AgentViewModel?,
    dropdownState: TuiDropdownState,
    columns: Int,
    rows: Int,
    onHoverChanged: (Boolean) -> Unit,
    onToggleExpanded: () -> Unit,
    onOpenShellSessionMenu: (ShellSessionMenuRequest) -> Unit,
    onShellSessionHoverChanged: (ShellSessionInteractionRequest, Boolean) -> Unit = { _, _ -> },
    onHistoryIndexHoverChanged: (HistoryIndexInteractionRequest, Boolean) -> Unit = { _, _ -> },
    onOpenHistoryIndexMenu: (HistoryIndexMenuRequest) -> Unit = {},
    resizable: Boolean = true,
    onResizeStart: (Int) -> Unit = {},
    onResize: (Int) -> Unit = {},
    onResizeEnd: (Int) -> Unit = {},
) {
    val contentColumns = (columns - SessionSidebarSplitterColumns).coerceAtLeast(0)
    Row(
        modifier = Modifier
            .width(columns)
            .height(rows)
            .background(SettingsDialogNavigationBackground)
            .onPointerHover(
                onPointerEnter = { onHoverChanged(true) },
                onPointerExit = { onHoverChanged(false) },
            ),
    ) {
        if (side == SessionSidebarSide.Right) {
            SessionSidebarSplitter(
                side = side,
                sidebarColumns = columns,
                rows = rows,
                enabled = resizable,
                onResizeStart = onResizeStart,
                onResize = onResize,
                onResizeEnd = onResizeEnd,
            )
        }
        if (contentColumns > 0) {
            SessionSidebarContent(
                side = side,
                content = content,
                selectedAgent = selectedAgent,
                dropdownState = dropdownState,
                columns = contentColumns,
                rows = rows,
                onToggleExpanded = onToggleExpanded,
                onOpenShellSessionMenu = onOpenShellSessionMenu,
                onShellSessionHoverChanged = onShellSessionHoverChanged,
                onHistoryIndexHoverChanged = onHistoryIndexHoverChanged,
                onOpenHistoryIndexMenu = onOpenHistoryIndexMenu,
            )
        }
        if (side == SessionSidebarSide.Left) {
            SessionSidebarSplitter(
                side = side,
                sidebarColumns = columns,
                rows = rows,
                enabled = resizable,
                onResizeStart = onResizeStart,
                onResize = onResize,
                onResizeEnd = onResizeEnd,
            )
        }
    }
}

@Composable
private fun SessionSidebarContent(
    side: SessionSidebarSide,
    content: SidebarContent,
    selectedAgent: AgentViewModel?,
    dropdownState: TuiDropdownState,
    columns: Int,
    rows: Int,
    onToggleExpanded: () -> Unit,
    onOpenShellSessionMenu: (ShellSessionMenuRequest) -> Unit,
    onShellSessionHoverChanged: (ShellSessionInteractionRequest, Boolean) -> Unit,
    onHistoryIndexHoverChanged: (HistoryIndexInteractionRequest, Boolean) -> Unit,
    onOpenHistoryIndexMenu: (HistoryIndexMenuRequest) -> Unit,
) {
    Column(modifier = Modifier.width(columns).height(rows)) {
        SessionSidebarHeader(
            side = side,
            content = content,
            dropdownState = dropdownState,
            columns = columns,
            onToggleExpanded = onToggleExpanded,
        )
        if (rows > 1) {
            when (content) {
                SidebarContent.None -> Unit
                SidebarContent.TerminalSessions -> TerminalSessionsSidebarBody(
                    selectedAgent = selectedAgent,
                    side = side,
                    columns = columns,
                    rows = rows - 1,
                    onHoverChanged = onShellSessionHoverChanged,
                    onOpenShellSessionMenu = onOpenShellSessionMenu,
                )

                SidebarContent.HistoryIndex -> selectedAgent?.let { agent ->
                    HistoryIndexSidebarBody(
                        viewModel = agent.historyIndex,
                        side = when (side) {
                            SessionSidebarSide.Left -> HistoryIndexSide.Left
                            SessionSidebarSide.Right -> HistoryIndexSide.Right
                        },
                        columns = columns,
                        rows = rows - 1,
                        onHoverChanged = onHistoryIndexHoverChanged,
                        onOpenMenu = onOpenHistoryIndexMenu,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionSidebarSplitter(
    side: SessionSidebarSide,
    sidebarColumns: Int,
    rows: Int,
    enabled: Boolean,
    onResizeStart: (Int) -> Unit,
    onResize: (Int) -> Unit,
    onResizeEnd: (Int) -> Unit,
) {
    val interaction = remember(side) { SessionSidebarSplitterInteraction() }
    val stateBackground = when {
        interaction.dragStart != null -> TuiTheme.colorScheme.surfaceContainerActive
        enabled && interaction.hovered -> TuiTheme.colorScheme.surfaceContainerHover
        else -> null
    }
    Column(
        modifier = Modifier
            .width(SessionSidebarSplitterColumns)
            .height(rows)
            .onPlaced { coordinates -> interaction.coordinates = coordinates }
            .onPointerHover(
                onPointerEnter = { interaction.hovered = enabled },
                onPointerExit = { interaction.hovered = false },
            )
            .onPointerEvent { event ->
                if (!enabled && interaction.dragStart == null) return@onPointerEvent false
                when (event.type) {
                    MouseEvent.Type.Press -> {
                        if (event.button != MouseEvent.Button.Left || event.shift) {
                            return@onPointerEvent false
                        }
                        val pointerColumn = interaction.pointerColumn(event.position.x)
                            ?: return@onPointerEvent false
                        interaction.dragStart = SessionSidebarDragStart(
                            pointerColumn = pointerColumn,
                            sidebarColumns = sidebarColumns,
                        )
                        onResizeStart(sidebarColumns)
                        true
                    }

                    MouseEvent.Type.Drag -> {
                        val requested = interaction.requestedWidth(side, event.position.x)
                            ?: return@onPointerEvent false
                        onResize(requested)
                        true
                    }

                    MouseEvent.Type.Release -> {
                        val requested = interaction.requestedWidth(side, event.position.x)
                            ?: return@onPointerEvent false
                        onResize(requested)
                        interaction.dragStart = null
                        onResizeEnd(requested)
                        true
                    }

                    MouseEvent.Type.Motion -> false
                }
            },
    ) {
        if (stateBackground != null) {
            Box(
                modifier = Modifier
                    .width(SessionSidebarSplitterColumns)
                    .height(rows)
                    .background(stateBackground),
            )
        } else {
            if (rows > 0) {
                Box(
                    modifier = Modifier
                        .width(SessionSidebarSplitterColumns)
                        .height(1)
                        .background(SettingsDialogHeaderBackground),
                )
            }
            if (rows > 1) {
                Box(
                    modifier = Modifier
                        .width(SessionSidebarSplitterColumns)
                        .height(rows - 1)
                        .background(SettingsDialogNavigationBackground),
                )
            }
        }
    }
}

@Stable
private class SessionSidebarSplitterInteraction {
    var hovered: Boolean by mutableStateOf(false)
    var dragStart: SessionSidebarDragStart? by mutableStateOf(null)
    var coordinates: LayoutCoordinates? = null

    fun pointerColumn(localColumn: Int): Int? {
        val current = coordinates?.takeIf(LayoutCoordinates::isAttached) ?: return null
        return current.position.x + localColumn
    }

    fun requestedWidth(side: SessionSidebarSide, localColumn: Int): Int? {
        val start = dragStart ?: return null
        val currentPointerColumn = pointerColumn(localColumn) ?: return null
        val delta = currentPointerColumn.toLong() - start.pointerColumn
        return (
            start.sidebarColumns.toLong() +
                side.resizeDirection.toLong() * delta
            )
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
    }
}

private data class SessionSidebarDragStart(
    val pointerColumn: Int,
    val sidebarColumns: Int,
)

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
    side: SessionSidebarSide,
    columns: Int,
    rows: Int,
    onHoverChanged: (ShellSessionInteractionRequest, Boolean) -> Unit,
    onOpenShellSessionMenu: (ShellSessionMenuRequest) -> Unit,
) {
    val shellSessions = collectOngoingShellSessions(selectedAgent)
    LazyColumn(modifier = Modifier.width(columns).height(rows)) {
        items(shellSessions, key = AgentShellSession::sessionId) { session ->
            ShellSessionSidebarRow(
                side = side,
                session = session,
                columns = columns,
                onHoverChanged = onHoverChanged,
                onOpenMenu = { anchor, clickPosition ->
                    onOpenShellSessionMenu(
                        ShellSessionMenuRequest(
                            session = session,
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
public fun SessionSidebarExpandButton(
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
public fun BoxScope.SessionSidebarContentMenu(
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
    side: SessionSidebarSide,
    session: AgentShellSession,
    columns: Int,
    onHoverChanged: (ShellSessionInteractionRequest, Boolean) -> Unit,
    onOpenMenu: (TuiPopupAnchor, IntOffset?) -> Unit,
) {
    val anchor = rememberTuiPopupAnchor()
    val request = remember(side, session, anchor) {
        ShellSessionInteractionRequest(
            side = side,
            session = session,
            anchor = anchor,
        )
    }
    DisposableEffect(request) {
        onDispose { onHoverChanged(request, false) }
    }
    val summary = remember(session.arguments.command, columns) {
        shellSessionSidebarSummary(
            command = session.arguments.command,
            columns = columns,
        )
    }
    TuiPressable(
        onClick = {},
        onSecondaryClick = { position -> onOpenMenu(anchor, position) },
        modifier = Modifier
            .fillMaxWidth()
            .tuiPopupAnchor(anchor)
            .onPointerEvent { event ->
                if (event.type == MouseEvent.Type.Motion) {
                    request.pointerPosition = event.position
                }
                false
            }
            .onPointerHover(
                onPointerEnter = { onHoverChanged(request, true) },
                onPointerExit = { onHoverChanged(request, false) },
            ),
    ) { _, hovered, pressed ->
        EllipsizedText(
            value = summary,
            modifier = Modifier
                .fillMaxWidth()
                .background(SettingsDialogNavigationBackground),
            color = SettingsDialogForeground,
            textStyle = tuiInteractionTextStyle(
                hovered = hovered,
                pressed = pressed,
                idleTextStyle = TextStyle.Dim,
            ),
        )
    }
}

@Composable
public fun BoxScope.ShellSessionContextMenu(
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
public fun BoxScope.ShellSessionHoverPopup(
    request: ShellSessionInteractionRequest?,
    contentColumns: Int,
    contentRows: Int,
    onHoverChanged: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val current = request ?: return
    if (contentColumns <= 0 || contentRows <= 0 || !current.anchor.isPlaced) return
    val completed by current.session.completed.collectAsState()
    LaunchedEffect(current, completed) {
        if (completed) onDismissRequest()
    }
    if (completed) return
    var visible by remember(current) { mutableStateOf(false) }
    LaunchedEffect(current) {
        delay(SidebarHoverDelay)
        visible = true
    }
    if (!visible) return
    SidebarHoverPopupSurface(
        anchor = current.anchor,
        side = current.side,
        pointerPosition = current.pointerPosition,
        title = "Session ${current.session.sessionId}",
        content = current.session.arguments.command,
        contentColumns = contentColumns,
        contentRows = contentRows,
        onHoverChanged = onHoverChanged,
    )
}

@Composable
private fun BoxScope.SidebarHoverPopupSurface(
    anchor: TuiPopupAnchor,
    side: SessionSidebarSide,
    pointerPosition: IntOffset?,
    title: String,
    content: String,
    contentColumns: Int,
    contentRows: Int,
    requestUserInputRows: List<RequestUserInputHistoryRowModel>? = null,
    titleColor: Color = SettingsDialogForeground,
    onHoverChanged: (Boolean) -> Unit,
) {
    val popupWidth = maxOf(
        title.terminalCellWidth(),
        requestUserInputRows
            ?.flatMap { row -> row.value.lineSequence().toList() }
            ?.maxOfOrNull(String::terminalCellWidth)
            ?: content.lineSequence().maxOfOrNull(String::terminalCellWidth)
            ?: 0,
    ).coerceIn(1, contentColumns)
    val lines = content.wrapToTerminalWidth(popupWidth)
    val bodyHeight = requestUserInputRows?.sumOf { row ->
        row.value.wrapToTerminalWidth(popupWidth).size.coerceAtLeast(1)
    } ?: lines.size
    val popupHeight = (bodyHeight + 1).coerceIn(1, contentRows)
    val listState = remember(anchor, content, requestUserInputRows) { LazyListState() }
    TuiPopup(
        anchor = anchor,
        onDismissRequest = null,
        positionProvider = remember(
            side,
            pointerPosition,
            contentColumns,
            contentRows,
        ) {
            SidebarHoverPopupPositionProvider(
                side = side,
                contentColumns = contentColumns,
                contentRows = contentRows,
                pointerPosition = pointerPosition,
            )
        },
        modifier = Modifier
            .widthIn(max = popupWidth)
            .heightIn(max = popupHeight)
            .drawBehind {
                drawRect(char = ' ', textStyle = TextStyle.Empty)
            }
            .background(SettingsDialogHomeBackground)
            .onPointerHover(
                onPointerEnter = { onHoverChanged(true) },
                onPointerExit = { onHoverChanged(false) },
            ),
    ) {
        Column(
            modifier = Modifier
                .width(popupWidth)
                .height(popupHeight)
                .background(SettingsDialogHomeBackground),
        ) {
            Text(
                value = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SettingsDialogHeaderBackground),
                color = titleColor,
            )
            if (popupHeight > 1) {
                LazyColumn(
                    modifier = Modifier
                        .width(popupWidth)
                        .height(popupHeight - 1)
                        .background(SettingsDialogHomeBackground),
                    state = listState,
                ) {
                    if (requestUserInputRows == null) {
                        items(lines) { line ->
                            Text(
                                value = line,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SettingsDialogHomeBackground),
                                color = SettingsDialogForeground,
                            )
                        }
                    } else {
                        items(requestUserInputRows) { row ->
                            RequestUserInputHistoryRow(row)
                        }
                    }
                }
            }
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

/** Renderer request retains the exact process handle, not a process-id lookup on another Agent. */
public data class ShellSessionMenuRequest(
    public val session: AgentShellSession,
    public val anchor: TuiPopupAnchor,
    public val clickPosition: IntOffset?,
)

@Stable
public class ShellSessionInteractionRequest(
    public val side: SessionSidebarSide,
    public val session: AgentShellSession,
    public val anchor: TuiPopupAnchor,
) {
    public var pointerPosition: IntOffset? by mutableStateOf(null)
}

/** Renderer-only direction and geometry; never a portable spec value. */
public enum class SessionSidebarSide(
    internal val collapseLabel: String,
    internal val expandLabel: String,
    internal val resizeDirection: Int,
) {
    Left(collapseLabel = "←", expandLabel = "→", resizeDirection = 1),
    Right(collapseLabel = "→", expandLabel = "←", resizeDirection = -1),
}

internal val SidebarContent.displayName: String
    get() = when (this) {
        SidebarContent.None -> "None"
        SidebarContent.TerminalSessions -> "Terminal sessions"
        SidebarContent.HistoryIndex -> "History Index"
    }

private class SidebarHoverPopupPositionProvider(
    private val side: SessionSidebarSide,
    private val contentColumns: Int,
    private val contentRows: Int,
    private val pointerPosition: IntOffset?,
) : TuiPopupPositionProvider {
    override fun calculateMaximumSize(
        anchorBounds: TuiPopupAnchorBounds,
        surfaceSize: IntSize,
    ): IntSize = IntSize(
        width = contentColumns.coerceIn(0, surfaceSize.width),
        height = contentRows.coerceIn(0, surfaceSize.height),
    )

    override fun calculatePosition(
        anchorBounds: TuiPopupAnchorBounds,
        surfaceSize: IntSize,
        popupContentSize: IntSize,
    ): IntOffset {
        val localPointer = pointerPosition ?: IntOffset(
            x = 0,
            y = anchorBounds.size.height - 1,
        )
        val pointer = anchorBounds.position + localPointer
        val afterPointerX = pointer.x + 1
        val beforePointerX = pointer.x - popupContentSize.width
        val requestedX = when (side) {
            SessionSidebarSide.Left -> if (
                afterPointerX + popupContentSize.width <= surfaceSize.width
            ) {
                afterPointerX
            } else {
                beforePointerX
            }

            SessionSidebarSide.Right -> if (beforePointerX >= 0) {
                beforePointerX
            } else {
                afterPointerX
            }
        }
        val belowPointerY = pointer.y + 1
        val abovePointerY = pointer.y - popupContentSize.height
        val requestedY = if (
            belowPointerY + popupContentSize.height <= surfaceSize.height
        ) {
            belowPointerY
        } else {
            abovePointerY
        }
        return IntOffset(
            x = requestedX.coerceIn(
                0,
                (surfaceSize.width - popupContentSize.width).coerceAtLeast(0),
            ),
            y = requestedY.coerceIn(
                SidebarHoverPopupTopRow.coerceAtMost(
                    (surfaceSize.height - popupContentSize.height).coerceAtLeast(0),
                ),
                (surfaceSize.height - popupContentSize.height).coerceAtLeast(0),
            ),
        )
    }
}

internal fun shellSessionSidebarSummary(
    command: String,
    columns: Int,
): String {
    if (columns <= 0) return ""
    val prefix = "● "
    val prefixWidth = prefix.terminalCellWidth()
    if (columns <= prefixWidth) return prefix.takeFirstFittingTerminalWidth(columns)

    val contentWidth = columns - prefixWidth
    val content = StringBuilder()
    var remainingWidth = contentWidth
    var truncated = false
    val lines = command.lineSequence().iterator()
    var firstLine = true
    while (lines.hasNext()) {
        val line = lines.next()
        if (!firstLine) {
            if (remainingWidth == 0) {
                truncated = true
                break
            }
            content.append(' ')
            remainingWidth -= 1
        }
        firstLine = false

        val fitting = line.takeFirstFittingTerminalWidth(remainingWidth)
        content.append(fitting)
        remainingWidth -= fitting.terminalCellWidth()
        if (fitting.length < line.length) {
            truncated = true
            break
        }
        if (remainingWidth == 0 && lines.hasNext()) {
            truncated = true
            break
        }
    }

    if (!truncated) return prefix + content
    val ellipsis = "..."
    val visibleContent = if (contentWidth <= ellipsis.terminalCellWidth()) {
        content.toString().takeFirstFittingTerminalWidth(contentWidth)
    } else {
        content.toString().takeFirstFittingTerminalWidth(
            contentWidth - ellipsis.terminalCellWidth(),
        ) + ellipsis
    }
    return prefix + visibleContent
}

public fun canExpandSessionSidebar(
    columns: Int,
    requestedColumns: Int,
    oppositeColumns: Int,
): Boolean =
    columns.toLong() >=
        requestedColumns.coerceAtLeast(0).toLong() +
        oppositeColumns.coerceAtLeast(0) +
        SessionSidebarMinimumContentColumns

public fun clampSessionSidebarResize(
    columns: Int,
    oppositeColumns: Int,
    requestedColumns: Int,
): Int? {
    val maximum = (
        columns.toLong() -
            oppositeColumns.coerceAtLeast(0) -
            SessionSidebarMinimumContentColumns
        ).coerceAtMost(Int.MAX_VALUE.toLong())
    if (maximum < MinimumSidebarWidthColumns) return null
    return requestedColumns.coerceIn(
        MinimumSidebarWidthColumns,
        maximum.toInt(),
    )
}

public fun resolveSessionSidebarColumns(
    columns: Int,
    leftColumns: Int,
    rightColumns: Int,
): SessionSidebarColumns {
    val total = columns.coerceAtLeast(SessionSidebarMinimumContentColumns)
    val capacity = total - SessionSidebarMinimumContentColumns
    val requestedLeft = leftColumns.coerceAtLeast(0)
    val requestedRight = rightColumns.coerceAtLeast(0)
    val requestedTotal = requestedLeft.toLong() + requestedRight
    val resolvedLeft: Int
    val resolvedRight: Int
    if (requestedTotal <= capacity) {
        resolvedLeft = requestedLeft
        resolvedRight = requestedRight
    } else if (requestedTotal == 0L) {
        resolvedLeft = 0
        resolvedRight = 0
    } else {
        resolvedLeft = (
            capacity.toLong() *
                requestedLeft /
                requestedTotal
            ).toInt()
        resolvedRight = capacity - resolvedLeft
    }
    return SessionSidebarColumns(
        left = resolvedLeft,
        content = total - resolvedLeft - resolvedRight,
        right = resolvedRight,
    )
}

public data class SessionSidebarColumns(
    public val left: Int,
    public val content: Int,
    public val right: Int,
)

public const val SessionSidebarCollapsedButtonColumns: Int = 3
public const val SessionSidebarCollapsedButtonRows: Int = 1
internal const val SessionSidebarSplitterColumns: Int = 1
internal const val SessionSidebarMinimumContentColumns: Int = 1
private val SidebarHoverDelay = 300.milliseconds
private const val SidebarHoverPopupTopRow: Int = 1

private val SettingsDialogForeground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.onSurface

private val SettingsDialogHeaderBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainerHigh

private val SettingsDialogNavigationBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = TuiTheme.colorScheme.surfaceContainer
