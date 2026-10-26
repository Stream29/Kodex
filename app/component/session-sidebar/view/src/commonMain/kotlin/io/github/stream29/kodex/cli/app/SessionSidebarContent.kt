package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.cli.historyindex.HistoryIndexSide
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

public actual fun sidebarContentLabel(content: SidebarContent): String = when (content) {
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
    renderer.ShellRow(interaction, session.arguments.command, interaction::emitHover, interaction::emitMenu)
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
    interaction: SidebarShellInteraction?, renderer: SidebarShellRenderer,
    onHoverChanged: (Boolean) -> Unit, onDismiss: () -> Unit,
) {
    val current = interaction ?: return
    if (!current.isPlaced || !renderer.canShowHover) return
    val completed = current.session.completed.collectAsState().value
    LaunchedEffect(current, completed) { if (completed) onDismiss() }
    if (completed) return
    var visible by remember(current) { mutableStateOf(false) }
    LaunchedEffect(current) { delay(300.milliseconds); visible = true }
    if (visible) renderer.ShellHover(current, "Session ${current.session.sessionId}", current.session.arguments.command, onHoverChanged)
}

@Composable
public actual fun SidebarShellMenuContent(
    opening: SidebarShellMenuOpening?, renderer: SidebarShellRenderer, onDismiss: () -> Unit,
) {
    val current = opening ?: return
    val session = current.interaction.session
    val completed = session.completed.collectAsState().value
    LaunchedEffect(current, completed) { if (completed) onDismiss() }
    if (completed) return
    renderer.ShellMenu(current, "Close session", onDismiss) {
        if (!session.completed.value) session.close()
    }
}
