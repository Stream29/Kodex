package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.application.contract.ApplicationPopupState
import io.github.stream29.kodex.cli.historyindex.*
import io.github.stream29.kodex.cli.settings.SidebarContent

/** One fresh root menu opening; placement and child handles are borrowed, not command authority. */
public sealed interface ApplicationOverlayMenu {
    public class Tab(public val opening: ApplicationTabMenuTarget) : ApplicationOverlayMenu
    public class History(public val opening: ApplicationHistoryMenuOpening) : ApplicationOverlayMenu
    public sealed interface Sidebar : ApplicationOverlayMenu { public val side: SessionSidebarSide }
    public class Shell(
        override val side: SessionSidebarSide, public val opening: SidebarShellMenuOpening,
    ) : Sidebar
    public class Index(
        override val side: SessionSidebarSide, public val opening: HistoryIndexMenuOpening,
    ) : Sidebar
}

/** A row identity is renderer-local; repeated events from it retain the same detail-read lifetime. */
public sealed interface ApplicationOverlayHover {
    public val side: SessionSidebarSide
    public val rowIdentity: Any
    public class Shell(
        override val side: SessionSidebarSide,
        public val interaction: SidebarShellInteraction,
        override val rowIdentity: Any = interaction,
    ) : ApplicationOverlayHover
    public class Index(
        override val side: SessionSidebarSide,
        public val interaction: HistoryIndexInteraction,
        override val rowIdentity: Any = interaction,
    ) : ApplicationOverlayHover
}

/**
 * Renderer-only menu/hover arbitration. Opening any menu clears hover and blocks new row hover.
 * Row exit waits 180ms; entering the matching popup cancels that wait. Old exits, timers and
 * dismissals cannot clear a replacement opening. No operation closes a borrowed child.
 */
public interface ApplicationOverlayState {
    public val menu: ApplicationOverlayMenu?
    public val hover: ApplicationOverlayHover?
    public fun openMenu(opening: ApplicationOverlayMenu): Unit
    public fun hoverChanged(candidate: ApplicationOverlayHover, entered: Boolean): Unit
    public fun popupHoverChanged(expected: ApplicationOverlayHover, entered: Boolean): Unit
    public fun dismissMenu(expected: ApplicationOverlayMenu): Boolean
    public fun dismissHover(expected: ApplicationOverlayHover): Boolean
    public fun clearSide(side: SessionSidebarSide): Unit
    public fun clearSidebar(): Unit
    public fun clear(): Unit
}

/** Context changes clear sidebar overlays; an Application popup additionally clears all root menus. */
@Composable
public expect fun rememberApplicationOverlays(
    selectedAgent: AgentViewModel?, left: SidebarContent, right: SidebarContent, popup: ApplicationPopupState,
): ApplicationOverlayState

/** Filters exact selected children before delegating existing Shell/Index content and read lifetimes. */
@Composable
public expect fun ApplicationSidebarOverlaysContent(
    state: ApplicationOverlayState, selectedAgent: AgentViewModel?,
    shellRenderer: SidebarShellRenderer, indexRenderer: HistoryIndexRenderer,
): Unit
