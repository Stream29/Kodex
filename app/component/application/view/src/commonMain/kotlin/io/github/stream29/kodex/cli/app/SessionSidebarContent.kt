package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.application.contract.TerminalOutputItem
import io.github.stream29.kodex.app.application.contract.TerminalOutputPhase
import io.github.stream29.kodex.app.application.contract.TerminalOutputReader
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.cli.historyindex.HistoryIndexSide
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal fun sidebarContentLabel(content: SidebarContent): String = when (content) {
    SidebarContent.None -> "None"
    SidebarContent.TerminalSessions -> "Terminal sessions"
    SidebarContent.HistoryIndex -> "History Index"
}

@Composable
public actual fun SessionSidebarContent(
    side: SessionSidebarSide, content: SidebarContent, selectedAgent: AgentViewModel?,
    renderer: SessionSidebarRenderer, onToggleExpanded: () -> Unit,
) {
    renderer.Frame(
        side,
        header = { renderer.Header(side, content, sidebarContentLabel(content), onToggleExpanded) },
        body = {
            when (content) {
                SidebarContent.None -> Unit
                SidebarContent.TerminalSessions -> {
                    val sessions = ongoingShellSessions(selectedAgent)
                    renderer.Shells(sessions) { list ->
                        list.Rows(sessions) { session -> ShellSessionSidebarContent(side, session, renderer) }
                    }
                }
                SidebarContent.HistoryIndex -> selectedAgent?.let { agent ->
                    renderer.Index(agent.historyIndex, if (side == SessionSidebarSide.Left) HistoryIndexSide.Left else HistoryIndexSide.Right)
                }
            }
        },
    )
}

@Composable
public actual fun SessionSidebarContentMenuContent(
    selected: SidebarContent, onSelect: (SidebarContent) -> Unit, renderer: SidebarContentMenuRenderer,
) {
    renderer.Menu(SidebarContent.entries, selected, ::sidebarContentLabel, onSelect)
}

@Composable
public actual fun ShellSessionSidebarContent(
    side: SessionSidebarSide, session: AgentShellSession, renderer: SidebarShellRenderer,
) {
    val interaction = renderer.rememberShellInteraction(side, session)
    DisposableEffect(interaction) { onDispose { interaction.emitHover(false) } }
    renderer.ShellRow(interaction, session.terminalIntentTitle(), interaction::emitHover, interaction::emitMenu)
}

@Composable
private fun ongoingShellSessions(agent: AgentViewModel?): List<AgentShellSession> {
    if (agent == null) return emptyList()
    return key(agent) {
        val sessions = agent.shellSessions.activeSessions.collectAsState().value
        buildList {
            sessions.values.sortedBy(AgentShellSession::sessionId).forEach { session ->
                key(session.sessionId) {
                    val completed = session.completed.collectAsState().value
                    if (!completed) add(session)
                }
            }
        }
    }
}

@Composable
public actual fun SidebarShellHoverContent(
    interaction: SidebarShellInteraction?, registry: AgentShellSessionRegistry, renderer: SidebarShellRenderer,
    onHoverChanged: (Boolean) -> Unit, onDismiss: () -> Unit,
) {
    val current = interaction ?: return
    if (!current.isPlaced || !renderer.canShowHover) return
    val session = current.session
    val completed = session.completed.collectAsState().value
    LaunchedEffect(current, completed) { if (completed) onDismiss() }
    if (completed) return
    var visible by remember(current, registry) { mutableStateOf(false) }
    LaunchedEffect(current, registry) { delay(300.milliseconds); visible = true }
    if (!visible) return

    val reader = remember(current, registry) { TerminalOutputReader(session, registry) }
    LaunchedEffect(reader) {
        // The inspector drains after exit; a hover instead cancels even a suspended read.
        coroutineScope {
            val read = launch { if (!session.completed.value) reader.observe() }
            val stopOnExit = launch(start = CoroutineStart.UNDISPATCHED) {
                session.completed.first { it }
                read.cancel()
            }
            try { read.join() } finally { stopOnExit.cancel() }
        }
    }
    val preview = remember(current) {
        val arguments = session.arguments
        SidebarShellPreview(
            session.terminalIntentTitle(), session.sessionId, arguments.workdir,
            arguments.shell?.path?.toString(), arguments.command,
        )
    }
    renderer.ShellHover(current, preview, reader.state, onHoverChanged)
}

@Composable
public actual fun SidebarShellMenuContent(
    opening: SidebarShellMenuOpening?, renderer: SidebarShellRenderer,
    onViewOutput: (() -> Unit)?, canAct: () -> Boolean, onDismiss: () -> Unit,
) {
    val current = opening ?: return
    val session = current.interaction.session
    val completed = session.completed.collectAsState().value
    LaunchedEffect(current, completed) { if (completed) onDismiss() }
    if (completed) return
    fun canUseMenu(): Boolean = canAct() && current.interaction.isPlaced && !session.completed.value
    renderer.ShellMenu(
        current, session.sessionId, "Close session", onDismiss,
        onClose = { if (canUseMenu()) { onDismiss(); session.close() } },
        onViewOutput = onViewOutput?.let { open -> { if (canUseMenu()) open() } },
    )
}

public actual fun AgentShellSession.terminalIntentTitle(): String = arguments.name

public actual fun terminalOutputItemLabel(item: TerminalOutputItem): String = when (item) {
    is TerminalOutputItem.Text -> item.text
    is TerminalOutputItem.BackendOmission -> "… ${item.bytes} bytes omitted by backend …"
    is TerminalOutputItem.WindowEviction -> "… ${item.items} earlier items removed from this view …"
}

public actual fun terminalOutputStatus(phase: TerminalOutputPhase, hasOutput: Boolean): String = when (phase) {
    TerminalOutputPhase.Reading -> if (hasOutput) "Reading output…" else "Waiting for output…"
    is TerminalOutputPhase.Complete -> if (hasOutput) "Exit code ${phase.exitCode}" else "Exit code ${phase.exitCode} (no output)"
    TerminalOutputPhase.Removed -> "Terminal no longer registered. See History for the command result."
    TerminalOutputPhase.SessionInactive -> "Session is inactive. Showing only the last captured output."
    TerminalOutputPhase.ReadFailed -> "Could not read output. Reopen the inspector to try again."
}
