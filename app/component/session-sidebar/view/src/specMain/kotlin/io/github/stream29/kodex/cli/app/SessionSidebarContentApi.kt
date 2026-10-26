package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.cli.historyindex.HistoryIndexSide

/** Placement only; character/pixel sizes remain renderer-local. */
public enum class SessionSidebarSide { Left, Right }

/** Captures the exact process handle, never a session-id lookup on another Agent. */
public interface SidebarShellInteraction {
    public val session: AgentShellSession
    public val isPlaced: Boolean
}

public interface SidebarShellRowInteraction : SidebarShellInteraction {
    public fun emitHover(hovered: Boolean)
    public fun emitMenu()
}

public class SidebarShellMenuOpening(public val interaction: SidebarShellInteraction)

public interface SidebarShellRenderer {
    public val canShowHover: Boolean
    @Composable public fun rememberShellInteraction(side: SessionSidebarSide, session: AgentShellSession): SidebarShellRowInteraction
    @Composable public fun ShellRow(
        interaction: SidebarShellRowInteraction, command: String,
        onHoverChanged: (Boolean) -> Unit, onOpenMenu: () -> Unit,
    )
    @Composable public fun ShellHover(
        interaction: SidebarShellInteraction, title: String, command: String, onHoverChanged: (Boolean) -> Unit,
    )
    /** Dismiss before invoking the command, matching the original Native menu. */
    @Composable public fun ShellMenu(
        opening: SidebarShellMenuOpening, label: String, onDismiss: () -> Unit, onClose: () -> Unit,
    )
}

public interface SidebarShellListScope {
    public fun Rows(sessions: List<AgentShellSession>, content: @Composable (AgentShellSession) -> Unit)
}

public interface SessionSidebarRenderer : SidebarShellRenderer {
    @Composable public fun Frame(side: SessionSidebarSide, header: @Composable () -> Unit, body: @Composable () -> Unit)
    @Composable public fun Header(side: SessionSidebarSide, content: SidebarContent, label: String, onToggleExpanded: () -> Unit)
    @Composable public fun Shells(sessions: List<AgentShellSession>, content: (SidebarShellListScope) -> Unit)
    @Composable public fun Index(viewModel: HistoryIndexViewModel, side: HistoryIndexSide)
}

public interface SidebarContentMenuRenderer {
    @Composable public fun Menu(
        options: List<SidebarContent>, selected: SidebarContent, label: (SidebarContent) -> String,
        onSelect: (SidebarContent) -> Unit,
    )
}

public expect fun sidebarContentLabel(content: SidebarContent): String

@Composable
public expect fun SessionSidebarContent(
    side: SessionSidebarSide, content: SidebarContent, selectedAgent: AgentViewModel?,
    renderer: SessionSidebarRenderer, onToggleExpanded: () -> Unit,
)

@Composable
public expect fun SessionSidebarContentMenuContent(
    selected: SidebarContent, onSelect: (SidebarContent) -> Unit, renderer: SidebarContentMenuRenderer,
)

@Composable
public expect fun ShellSessionSidebarContent(
    side: SessionSidebarSide, session: AgentShellSession, renderer: SidebarShellRenderer,
)

/** Host filters selected-child matching requests and owns shared Shell/Index popup priority/grace. */
@Composable
public expect fun SidebarShellHoverContent(
    interaction: SidebarShellInteraction?, renderer: SidebarShellRenderer,
    onHoverChanged: (Boolean) -> Unit, onDismiss: () -> Unit,
)

@Composable
public expect fun SidebarShellMenuContent(
    opening: SidebarShellMenuOpening?, renderer: SidebarShellRenderer, onDismiss: () -> Unit,
)
