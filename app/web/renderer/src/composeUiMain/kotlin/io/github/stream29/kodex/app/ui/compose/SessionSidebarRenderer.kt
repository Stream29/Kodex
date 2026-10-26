@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    return remember { ComposeSessionSidebarRenderer(select, shellHover, shellMenu, indexHover, indexMenu) }
}

private interface ComposeShellPlacement : SidebarShellInteraction {
    val side: SessionSidebarSide
    val bounds: Rect?
    val point: Offset?
}

private class ComposeShellRowOrigin(
    override val side: SessionSidebarSide, override val session: AgentShellSession,
    private val hover: State<(SidebarShellInteraction, Boolean) -> Unit>,
    private val menu: State<(SidebarShellMenuOpening) -> Unit>,
) : ComposeShellPlacement, SidebarShellRowInteraction {
    override var bounds by mutableStateOf<Rect?>(null)
    override var point by mutableStateOf<Offset?>(null)
    var openingPoint: Offset? = null
    override val isPlaced: Boolean get() = bounds?.let { it.width > 0 && it.height > 0 } == true
    override fun emitHover(hovered: Boolean) { hover.value(this, hovered) }
    override fun emitMenu() { menu.value(SidebarShellMenuOpening(ComposeShellMenuOrigin(this, openingPoint))) }
}

private class ComposeShellMenuOrigin(private val row: ComposeShellRowOrigin, override val point: Offset?) : ComposeShellPlacement {
    override val side: SessionSidebarSide get() = row.side
    override val session: AgentShellSession get() = row.session
    override val bounds: Rect? get() = row.bounds
    override val isPlaced: Boolean get() = row.isPlaced
}

private class ComposeSessionSidebarRenderer(
    private val select: State<(SessionSidebarSide, SidebarContent) -> Unit>,
    private val hover: State<(SidebarShellInteraction, Boolean) -> Unit>,
    private val menu: State<(SidebarShellMenuOpening) -> Unit>,
    private val indexHover: State<(HistoryIndexInteraction, Boolean) -> Unit>,
    private val indexMenu: State<(HistoryIndexMenuOpening) -> Unit>,
) : SessionSidebarRenderer {
    override val canShowHover: Boolean get() = true
    @Composable override fun Frame(side: SessionSidebarSide, header: @Composable () -> Unit, body: @Composable () -> Unit) {
        Surface {
            Column(Modifier.fillMaxSize()) {
                header()
                Box(Modifier.weight(1f).fillMaxWidth()) { body() }
            }
        }
    }
    @Composable override fun Header(side: SessionSidebarSide, content: SidebarContent, label: String, onToggleExpanded: () -> Unit) {
        var expanded by remember(side) { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth()) {
            @Composable fun Collapse() {
                TextButton(
                    onToggleExpanded, Modifier.semantics { contentDescription = "Collapse ${side.name.lowercase()} sidebar" },
                ) { Text(if (side == SessionSidebarSide.Left) "←" else "→") }
            }
            if (side == SessionSidebarSide.Left) Collapse()
            Box(Modifier.weight(1f)) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Sidebar content ${side.name.lowercase()}" },
                ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                SessionSidebarContentMenuContent(content, { select.value(side, it) }, object : SidebarContentMenuRenderer {
                    @Composable override fun Menu(
                        options: List<SidebarContent>, selected: SidebarContent, label: (SidebarContent) -> String,
                        onSelect: (SidebarContent) -> Unit,
                    ) {
                        DropdownMenu(expanded, { expanded = false }) {
                            options.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(label(option), fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal) },
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
        LazyColumn(Modifier.fillMaxSize()) { content(ComposeSidebarShellListScope(this)) }
    }
    @Composable override fun Index(viewModel: HistoryIndexViewModel, side: HistoryIndexSide) {
        HistoryIndexContent(viewModel, side, rememberComposeHistoryIndexRenderer(indexHover.value, indexMenu.value))
    }
    @Composable override fun rememberShellInteraction(side: SessionSidebarSide, session: AgentShellSession): SidebarShellRowInteraction =
        remember(side, session) { ComposeShellRowOrigin(side, session, hover, menu) }

    @Composable override fun ShellRow(
        interaction: SidebarShellRowInteraction, command: String, onHoverChanged: (Boolean) -> Unit, onOpenMenu: () -> Unit,
    ) {
        val origin = interaction as ComposeShellRowOrigin
        DisposableEffect(origin) { onDispose { origin.bounds = null } }
        val summary = remember(command) { "● " + command.lineSequence().joinToString(" ") }
        TextButton(
            onClick = {},
            modifier = Modifier.fillMaxWidth().onGloballyPositioned { origin.bounds = it.boundsInWindow() }
                .semantics { contentDescription = "Shell session ${origin.session.sessionId}" }
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
                },
        ) { Text(summary, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                    Text(title, fontWeight = FontWeight.Bold)
                    SelectionContainer {
                        Text(command, Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))
                    }
                }
            }
        }
    }
    @Composable override fun ShellMenu(opening: SidebarShellMenuOpening, label: String, onDismiss: () -> Unit, onClose: () -> Unit) {
        val origin = opening.interaction as ComposeShellPlacement
        val bounds = origin.bounds ?: return
        val placement = remember(bounds, origin.side, origin.point) { PointerPopupPosition(bounds, origin.side == SessionSidebarSide.Left, origin.point) }
        Popup(
            popupPositionProvider = placement, onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true, dismissOnBackPress = false),
        ) {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.widthIn(max = 480.dp)) {
                    DropdownMenuItem(text = { Text(label) }, onClick = { onDismiss(); onClose() })
                }
            }
            NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = onDismiss)
        }
    }
}

private class ComposeSidebarShellListScope(private val original: androidx.compose.foundation.lazy.LazyListScope) : SidebarShellListScope {
    override fun Rows(sessions: List<AgentShellSession>, content: @Composable (AgentShellSession) -> Unit) {
        original.items(sessions.size, { sessions[it].sessionId }) { content(sessions[it]) }
    }
}
