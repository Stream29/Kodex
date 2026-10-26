package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.ui.BoxScope
import io.github.stream29.kodex.cli.historyindex.*

/** Converts existing Native row geometry without introducing a second process/index target. */
public expect fun mosaicShellHover(request: ShellSessionInteractionRequest): SidebarShellInteraction
public expect fun mosaicShellMenu(request: ShellSessionMenuRequest): SidebarShellMenuOpening
public expect fun mosaicIndexHover(request: HistoryIndexInteractionRequest): HistoryIndexInteraction
public expect fun mosaicIndexMenu(request: HistoryIndexMenuRequest): HistoryIndexMenuOpening

@Composable
public expect fun BoxScope.rememberMosaicSidebarOverlayRenderer(columns: Int, rows: Int): SidebarShellRenderer

@Composable
public expect fun BoxScope.rememberMosaicIndexOverlayRenderer(columns: Int, rows: Int): HistoryIndexRenderer
