@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.ui.compose.EditorPopup as Popup
import io.github.stream29.kodex.app.ui.compose.EditorDropdownMenu as DropdownMenu
import androidx.compose.ui.window.PopupProperties
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.cli.historyindex.*
import io.github.stream29.kodex.cli.settings.SidebarContent

@Composable
public actual fun rememberComposeSessionSidebarRenderer(
    onSelectContent: (SessionSidebarSide, SidebarContent) -> Unit,
    onShellHoverChanged: (SidebarShellInteraction, Boolean) -> Unit,
    onOpenShellMenu: (SidebarShellMenuOpening) -> Unit,
    onIndexHoverChanged: (HistoryIndexInteraction, Boolean) -> Unit,
    onOpenIndexMenu: (HistoryIndexMenuOpening) -> Unit,
): SessionSidebarRenderer {
    val select = rememberUpdatedState(onSelectContent)
    val shellHover = rememberUpdatedState(onShellHoverChanged)
    val shellMenu = rememberUpdatedState(onOpenShellMenu)
    val indexHover = rememberUpdatedState(onIndexHoverChanged)
    val indexMenu = rememberUpdatedState(onOpenIndexMenu)
    val scope = rememberCoroutineScope()
    return remember(scope) { ComposeSessionSidebarRenderer(select, shellHover, shellMenu, indexHover, indexMenu, RowMenuFocusRestoration(scope)) }
}

private interface ComposeShellPlacement : SidebarShellInteraction {
    val side: SessionSidebarSide
    val bounds: Rect?
    val point: Offset?
    val fallback: FocusRequester?
}

private class ComposeShellRowOrigin(
    override val side: SessionSidebarSide, override val session: AgentShellSession,
    override val fallback: FocusRequester?,
    private val hover: State<(SidebarShellInteraction, Boolean) -> Unit>,
    private val menu: State<(SidebarShellMenuOpening) -> Unit>,
) : ComposeShellPlacement, SidebarShellRowInteraction {
    override var bounds by mutableStateOf<Rect?>(null)
    override var point by mutableStateOf<Offset?>(null)
    var openingPoint: Offset? = null
    override val isPlaced: Boolean get() = bounds?.let { it.width > 0 && it.height > 0 } == true
    override fun emitHover(hovered: Boolean) { hover.value(this, hovered) }
    override fun emitMenu() { if (isPlaced && !session.completed.value) menu.value(SidebarShellMenuOpening(ComposeShellMenuOrigin(this, openingPoint))) }
}

private class ComposeShellMenuOrigin(private val row: ComposeShellRowOrigin, override val point: Offset?) : ComposeShellPlacement {
    override val side: SessionSidebarSide get() = row.side
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
        val focus = remember(origin) { FocusRequester() }
        var focused by remember(origin) { mutableStateOf(false) }
        DisposableEffect(origin) { onDispose { origin.bounds = null } }
        val summary = remember(command) { command.lineSequence().joinToString(" ") }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
                .background(if (focused) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .onGloballyPositioned { origin.bounds = it.boundsInWindow() }
                .semantics { contentDescription = "Shell session ${origin.session.sessionId}"; stateDescription = "Running" }
                .onPointerEvent(PointerEventType.Enter) { origin.point = it.changes.firstOrNull()?.position; onHoverChanged(true) }
                .onPointerEvent(PointerEventType.Move) { origin.point = it.changes.firstOrNull()?.position }
                .onPointerEvent(PointerEventType.Exit) { onHoverChanged(false) }
                .onPointerEvent(PointerEventType.Press) {
                    if (it.buttons.isSecondaryPressed) { origin.openingPoint = it.changes.firstOrNull()?.position; onOpenMenu() }
                }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.key == Key.Menu || it.key == Key.F10 && it.isShiftPressed)) {
                        origin.openingPoint = null; onOpenMenu(); true
                    } else false
                }.onFocusChanged { focused = it.isFocused }.focusRequester(focus).focusable(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.padding(start = 8.dp, end = 6.dp).size(5.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape))
            Text(summary, Modifier.weight(1f).padding(end = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            SemanticRowMenuAction("Open menu for shell session ${origin.session.sessionId}") {
                val focused = origin.isPlaced && try { focus.requestFocus() } catch (_: IllegalStateException) { false }
                if (!focused) false else {
                    origin.openingPoint = null
                    onOpenMenu()
                    true
                }
            }
        }
    }
    @Composable override fun ShellHover(
        interaction: SidebarShellInteraction, title: String, command: String, onHoverChanged: (Boolean) -> Unit,
    ) {
        val origin = interaction as ComposeShellPlacement
        val bounds = origin.bounds ?: return
        val placement = remember(bounds, origin.side, origin.point) { PointerPopupPosition(bounds, origin.side == SessionSidebarSide.Left, origin.point) }
        Popup(popupPositionProvider = placement, properties = PopupProperties(focusable = false)) {
            Surface(
                shadowElevation = 8.dp,
                modifier = Modifier.widthIn(max = 480.dp).heightIn(max = 360.dp)
                    .onPointerEvent(PointerEventType.Enter) { onHoverChanged(true) }
                    .onPointerEvent(PointerEventType.Exit) { onHoverChanged(false) },
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(command, Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodyLarge, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        }
    }
    @Composable override fun ShellMenu(opening: SidebarShellMenuOpening, label: String, onDismiss: () -> Unit, onClose: () -> Unit) {
        val origin = opening.interaction as ComposeShellPlacement
        val mayRestore = LocalSidebarFocusRestoreAllowed.current
        restoration.Track(opening, origin.fallback, mayRestore) { origin.isPlaced && !origin.session.completed.value }
        LaunchedEffect(opening, origin.isPlaced) { if (!origin.isPlaced) onDismiss() }
        if (!origin.isPlaced) return
        val bounds = origin.bounds ?: return
        val placement = remember(bounds, origin.side, origin.point) { PointerPopupPosition(bounds, origin.side == SessionSidebarSide.Left, origin.point) }
        val close = remember(opening) { FocusRequester() }
        Popup(
            popupPositionProvider = placement, onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true, dismissOnBackPress = false),
        ) {
            Surface(shape = RoundedCornerShape(4.dp), shadowElevation = 3.dp) {
                Column(Modifier.widthIn(min = 160.dp, max = 480.dp).heightIn(max = 480.dp).padding(8.dp)) {
                    Text("Session ${origin.session.sessionId}", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        Text(origin.session.arguments.command, style = MaterialTheme.typography.bodyLarge,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    CompactTextButton(
                        onClick = { if (origin.isPlaced && !origin.session.completed.value) { onDismiss(); onClose() } },
                        modifier = Modifier.fillMaxWidth().focusRequester(close),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(label, Modifier.weight(1f)) }
                }
            }
            NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = onDismiss)
        }
        LaunchedEffect(close) {
            repeat(3) {
                withFrameNanos { }
                if (try { close.requestFocus() } catch (_: IllegalStateException) { false }) return@LaunchedEffect
            }
        }
    }
}

private class ComposeSidebarShellListScope(private val original: androidx.compose.foundation.lazy.LazyListScope) : SidebarShellListScope {
    override fun Rows(sessions: List<AgentShellSession>, content: @Composable (AgentShellSession) -> Unit) {
        original.items(sessions.size, { sessions[it].sessionId }) { content(sessions[it]) }
    }
}
