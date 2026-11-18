package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
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
): SessionSidebarRenderer {
    val select = rememberUpdatedState(onSelectContent)
    val shellHover = rememberUpdatedState(onShellHoverChanged)
    val shellMenu = rememberUpdatedState(onOpenShellMenu)
    val indexHover = rememberUpdatedState(onIndexHoverChanged)
    val indexMenu = rememberUpdatedState(onOpenIndexMenu)
    val scope = rememberCoroutineScope()
    return remember(scope) { ComposeSessionSidebarRenderer(select, shellHover, shellMenu, indexHover, indexMenu, RowMenuFocusRestoration(scope)) }
}

private interface ComposeShellPlacement : SidebarShellInteraction, ComposeSidebarPopupPlacement

private class ComposeShellRowOrigin(
    side: SessionSidebarSide,
    override val session: AgentShellSession,
    fallback: FocusRequester?,
    private val hover: State<(SidebarShellInteraction, Boolean) -> Unit>,
    private val menu: State<(SidebarShellMenuOpening) -> Unit>,
) : ComposeSidebarRowOrigin(side == SessionSidebarSide.Left, fallback), ComposeShellPlacement, SidebarShellRowInteraction {
    override fun emitHover(hovered: Boolean) { hover.value(this, hovered) }
    override fun emitMenu() {
        if (isPlaced && !session.completed.value) menu.value(SidebarShellMenuOpening(ComposeShellMenuOrigin(this, openingPoint)))
    }
}

private class ComposeShellMenuOrigin(
    private val row: ComposeShellRowOrigin, override val point: Offset?,
) : ComposeShellPlacement {
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
        var expanded by remember(side) { mutableStateOf(false) }
        val fallback = LocalRowMenuFallback.current
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
        interaction: SidebarShellRowInteraction, command: String, onHoverChanged: (Boolean) -> Unit, onOpenMenu: () -> Unit,
    ) {
        val origin = interaction as ComposeShellRowOrigin
        val summary = remember(command) { command.lineSequence().joinToString(" ") }
        SidebarPopupRow(
            origin = origin,
            summary = summary,
            rowDescription = "Shell session ${origin.session.sessionId}",
            rowStateDescription = "Running",
            menuActionDescription = "Open menu for shell session ${origin.session.sessionId}",
            onHoverChanged = onHoverChanged,
            onOpenMenu = onOpenMenu,
        )
    }

    @Composable override fun ShellHover(
        interaction: SidebarShellInteraction, title: String, command: String, onHoverChanged: (Boolean) -> Unit,
    ) {
        SidebarHoverPopup(interaction as ComposeShellPlacement, title, onHoverChanged) {
            SelectionContainer {
                Text(
                    command, Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    @Composable override fun ShellMenu(
        opening: SidebarShellMenuOpening, label: String, onDismiss: () -> Unit, onClose: () -> Unit, onViewOutput: (() -> Unit)?,
    ) {
        val origin = opening.interaction as ComposeShellPlacement
        SidebarMenuPopup(
            opening = opening,
            origin = origin,
            restoration = restoration,
            isOriginValid = { origin.isPlaced && !origin.session.completed.value },
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
            Text("Session ${origin.session.sessionId}", style = MaterialTheme.typography.titleSmall)
            SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(
                    origin.session.arguments.command,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }

    override fun captureShellOutputPlacement(opening: SidebarShellMenuOpening): SidebarShellOutputPlacement? {
        val origin = opening.interaction as ComposeShellPlacement
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
        ComposeTerminalOutput(placement, origin.session.arguments.command, output, onBack)
    }
}

private class ComposeSidebarShellListScope(private val original: androidx.compose.foundation.lazy.LazyListScope) : SidebarShellListScope {
    override fun Rows(sessions: List<AgentShellSession>, content: @Composable (AgentShellSession) -> Unit) {
        original.items(sessions.size, { sessions[it].sessionId }) { content(sessions[it]) }
    }
}
