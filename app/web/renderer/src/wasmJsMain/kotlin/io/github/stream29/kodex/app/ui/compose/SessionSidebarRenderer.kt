package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.application.contract.TerminalOutputItem
import io.github.stream29.kodex.app.application.contract.TerminalOutputState
import io.github.stream29.kodex.app.ui.compose.EditorDropdownMenu as DropdownMenu
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.cli.historyindex.*
import io.github.stream29.kodex.cli.settings.SidebarContent
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun rememberComposeSessionSidebarRenderer(
    onSelectContent: (SessionSidebarSide, SidebarContent) -> Unit = { _, _ -> },
    onShellHoverChanged: (SidebarShellInteraction, Boolean) -> Unit = { _, _ -> },
    onOpenShellMenu: (SidebarShellMenuOpening) -> Unit = {},
    onIndexHoverChanged: (HistoryIndexInteraction, Boolean) -> Unit = { _, _ -> },
    onOpenIndexMenu: (HistoryIndexMenuOpening) -> Unit = {},
    showContentMenu: Boolean = true, // Standalone sidebar callers can still exercise the legacy header.
): SessionSidebarRenderer {
    val select = rememberUpdatedState(onSelectContent)
    val shellHover = rememberUpdatedState(onShellHoverChanged)
    val shellMenu = rememberUpdatedState(onOpenShellMenu)
    val indexHover = rememberUpdatedState(onIndexHoverChanged)
    val indexMenu = rememberUpdatedState(onOpenIndexMenu)
    val scope = rememberCoroutineScope()
    return remember(scope, showContentMenu) {
        ComposeSessionSidebarRenderer(select, shellHover, shellMenu, indexHover, indexMenu, RowMenuFocusRestoration(scope), showContentMenu)
    }
}

private class ComposeShellRowOrigin(
    side: SessionSidebarSide,
    override val session: AgentShellSession,
    fallback: FocusRequester?,
    private val hover: State<(SidebarShellInteraction, Boolean) -> Unit>,
    private val menu: State<(SidebarShellMenuOpening) -> Unit>,
) : ComposeSidebarRowOrigin(side == SessionSidebarSide.Left, fallback), SidebarShellRowInteraction {
    override fun emitHover(hovered: Boolean) { hover.value(this, hovered) }
    override fun emitMenu() {
        if (isPlaced && !session.completed.value) menu.value(SidebarShellMenuOpening(ComposeShellMenuOrigin(this, openingPoint)))
    }
}

private class ComposeShellMenuOrigin(
    private val row: ComposeShellRowOrigin, override val point: Offset?,
) : SidebarShellInteraction, ComposeSidebarMenuPlacement {
    override val preferAfter: Boolean get() = row.preferAfter
    override val session: AgentShellSession get() = row.session
    override val bounds: Rect? get() = row.bounds
    override val isPlaced: Boolean get() = row.isPlaced
    override val fallback: FocusRequester? get() = row.fallback
}

private class ComposeSessionSidebarRenderer(
    private val select: State<(SessionSidebarSide, SidebarContent) -> Unit>,
    private val hover: State<(SidebarShellInteraction, Boolean) -> Unit>,
    private val menu: State<(SidebarShellMenuOpening) -> Unit>,
    private val indexHover: State<(HistoryIndexInteraction, Boolean) -> Unit>,
    private val indexMenu: State<(HistoryIndexMenuOpening) -> Unit>,
    private val restoration: RowMenuFocusRestoration,
    private val showContentMenu: Boolean,
) : SessionSidebarRenderer {
    override val canShowHover: Boolean get() = true

    @Composable override fun Frame(side: SessionSidebarSide, header: @Composable () -> Unit, body: @Composable () -> Unit) {
        val fallback = remember(side) { FocusRequester() }
        CompositionLocalProvider(LocalRowMenuFallback provides fallback) {
            Surface {
                Column(Modifier.fillMaxSize()) {
                    header()
                    Box(Modifier.weight(1f).fillMaxWidth()) { body() }
                }
            }
        }
    }

    @Composable override fun Header(side: SessionSidebarSide, content: SidebarContent, label: String, onToggleExpanded: () -> Unit) {
        val fallback = LocalRowMenuFallback.current
        if (!showContentMenu) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                ToolWindowIconButton(
                    "Collapse ${side.name.lowercase()} sidebar", ToolWindowIcon.Collapse, onToggleExpanded,
                    modifier = if (fallback == null) Modifier else Modifier.focusRequester(fallback),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            return
        }
        var expanded by remember(side) { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            @Composable fun Collapse() {
                CompactTextButton(
                    onToggleExpanded, Modifier.widthIn(min = 32.dp).semantics { contentDescription = "Collapse ${side.name.lowercase()} sidebar" },
                ) { Text(if (side == SessionSidebarSide.Left) "←" else "→") }
            }
            if (side == SessionSidebarSide.Left) Collapse()
            Box(Modifier.weight(1f)) {
                CompactTextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                        .then(if (fallback == null) Modifier else Modifier.focusRequester(fallback))
                        .semantics { contentDescription = "Sidebar content ${side.name.lowercase()}" },
                ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge) }
                SessionSidebarContentMenuContent(content, { select.value(side, it) }, object : SidebarContentMenuRenderer {
                    @Composable override fun Menu(
                        options: List<SidebarContent>, selected: SidebarContent, label: (SidebarContent) -> String,
                        onSelect: (SidebarContent) -> Unit,
                    ) {
                        DropdownMenu(expanded, { expanded = false }) {
                            options.forEach { option ->
                                CompactChoiceMenuItem(
                                    label = label(option),
                                    selected = option == selected,
                                    autoFocus = option == (options.firstOrNull { it == selected } ?: options.firstOrNull()),
                                    enabled = expanded,
                                    onClick = { expanded = false; onSelect(option) },
                                )
                            }
                        }
                    }
                })
            }
            if (side == SessionSidebarSide.Right) Collapse()
        }
    }

    @Composable override fun Shells(sessions: List<AgentShellSession>, content: (SidebarShellListScope) -> Unit) {
        if (sessions.isEmpty()) {
            Text("No running terminal sessions", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.fillMaxSize()) { content(ComposeSidebarShellListScope(this)) }
    }

    @Composable override fun Index(viewModel: HistoryIndexViewModel, side: HistoryIndexSide) {
        HistoryIndexContent(viewModel, side, rememberComposeHistoryIndexRenderer(indexHover.value, indexMenu.value))
    }

    @Composable override fun rememberShellInteraction(side: SessionSidebarSide, session: AgentShellSession): SidebarShellRowInteraction {
        val fallback = LocalRowMenuFallback.current
        return remember(side, session, fallback) { ComposeShellRowOrigin(side, session, fallback, hover, menu) }
    }

    @Composable override fun ShellRow(
        interaction: SidebarShellRowInteraction, title: String, onHoverChanged: (Boolean) -> Unit, onOpenMenu: () -> Unit,
    ) {
        val origin = interaction as ComposeShellRowOrigin
        SidebarPopupRow(
            origin = origin,
            summary = title,
            rowDescription = "Shell session ${origin.session.sessionId}",
            rowStateDescription = "Running",
            menuActionDescription = "Open menu for shell session ${origin.session.sessionId}",
            onHoverChanged = onHoverChanged,
            onOpenMenu = onOpenMenu,
        )
    }

    @Composable override fun ShellHover(
        interaction: SidebarShellInteraction, preview: SidebarShellPreview,
        output: StateFlow<TerminalOutputState>, onHoverChanged: (Boolean) -> Unit,
    ) {
        SidebarHoverPopup(interaction as ComposeShellRowOrigin, preview.title, onHoverChanged, singleLineTitle = true) {
            val state by output.collectAsState()
            SelectionContainer(Modifier.width(440.dp).weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    preview.metadataLines.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(preview.command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            Text(terminalOutputStatus(state.phase, state.items.isNotEmpty()), style = MaterialTheme.typography.labelMedium)
            val list = rememberLazyListState()
            var follows by remember { mutableStateOf(true) }
            val gestures = remember(list) {
                object : NestedScrollConnection {
                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        if (source == NestedScrollSource.UserInput) {
                            // In a reversed list, positive consumed Y moves away from the latest line.
                            if (consumed.y > 0f) follows = false
                            else if (!list.canScrollBackward) follows = true
                        }
                        return Offset.Zero
                    }
                }
            }
            LaunchedEffect(state.items.lastOrNull()?.id, follows) {
                if (follows && state.items.isNotEmpty()) list.requestScrollToItem(0)
            }
            // Allocate the viewport even before the first line so live output does not move the popup.
            SelectionContainer(Modifier.weight(1f, fill = false)) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(112.dp).nestedScroll(gestures),
                    state = list,
                    reverseLayout = true,
                ) {
                    items(state.items.size, key = { state.items[state.items.lastIndex - it].id }) { index ->
                        val item = state.items[state.items.lastIndex - index]
                        Text(
                            terminalOutputItemLabel(item).ifEmpty { " " },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item is TerminalOutputItem.Text) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    @Composable override fun ShellMenu(
        opening: SidebarShellMenuOpening, sessionId: Int, label: String, onDismiss: () -> Unit,
        onClose: () -> Unit, onViewOutput: (() -> Unit)?,
    ) {
        val origin = opening.interaction as ComposeSidebarMenuPlacement
        SidebarMenuPopup(
            opening = opening,
            origin = origin,
            restoration = restoration,
            isOriginValid = { origin.isPlaced && !opening.interaction.session.completed.value },
            dismissWhenUnplaced = true,
            actionLabel = if (onViewOutput != null) "View output" else label,
            actionColors = if (onViewOutput != null) ButtonDefaults.textButtonColors()
                else ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            onDismiss = onDismiss,
            onAction = onViewOutput ?: onClose,
            afterAction = if (onViewOutput == null) null else {
                {
                    CompactTextButton(
                        onClick = onClose,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(label, Modifier.weight(1f)) }
                }
            },
        ) {
            Text(
                "Terminal #$sessionId", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    override fun captureShellOutputPlacement(opening: SidebarShellMenuOpening): SidebarShellOutputPlacement? {
        val origin = opening.interaction as ComposeSidebarMenuPlacement
        val bounds = origin.bounds?.takeIf { origin.isPlaced } ?: return null
        return ComposeTerminalOutputPlacement(bounds, origin.point, origin.preferAfter, origin.fallback)
    }

    @Composable override fun ShellOutput(
        opening: ApplicationOverlayMenu.ShellOutput, output: StateFlow<TerminalOutputState>, onBack: () -> Unit,
    ) {
        val placement = opening.placement as ComposeTerminalOutputPlacement
        val origin = opening.original.opening.interaction
        val mayRestore = LocalSidebarFocusRestoreAllowed.current
        restoration.Track(opening, placement.fallback, mayRestore) {
            origin.isPlaced && !origin.session.completed.value &&
                opening.agent.shellSessions.activeSessions.value[origin.session.sessionId] === origin.session
        }
        ComposeTerminalOutput(placement, origin.session.terminalIntentTitle(), origin.session.arguments.command, output, onBack)
    }
}

private class ComposeSidebarShellListScope(private val original: androidx.compose.foundation.lazy.LazyListScope) : SidebarShellListScope {
    override fun Rows(sessions: List<AgentShellSession>, content: @Composable (AgentShellSession) -> Unit) {
        original.items(sessions.size, { sessions[it].sessionId }) { content(sessions[it]) }
    }
}
