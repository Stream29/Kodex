package io.github.stream29.kodex.app.ui.compose

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.cli.app.*
import io.github.stream29.kodex.cli.historyindex.*
import io.github.stream29.kodex.cli.settings.SidebarContent

/** Caller owns content preferences, selected-child filtering and popup priority/grace. */
@Composable
public expect fun rememberComposeSessionSidebarRenderer(
    onSelectContent: (SessionSidebarSide, SidebarContent) -> Unit = { _, _ -> },
    onShellHoverChanged: (SidebarShellInteraction, Boolean) -> Unit = { _, _ -> },
    onOpenShellMenu: (SidebarShellMenuOpening) -> Unit = {},
    onIndexHoverChanged: (HistoryIndexInteraction, Boolean) -> Unit = { _, _ -> },
    onOpenIndexMenu: (HistoryIndexMenuOpening) -> Unit = {},
): SessionSidebarRenderer
