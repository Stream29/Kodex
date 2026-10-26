package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.cli.components.TuiPopupAnchor

/**
 * Renders the one real History owner for an Agent, not a projection into another page ViewModel.
 *
 * Rows borrow the bounded newest-first window and submit access hints through get. Focus and
 * popup anchors remain renderer-local. Loading, pending tools,
 * streaming and turn markers retain their own original presentation branches.
 *
 * [onOpenEntryContextMenu] receives the displayed generation, storage index and actual child,
 * including Work Group children. The host revalidates that captured target with [model]'s
 * `contains` before destructive actions; no resolver or synthetic parallel target is involved.
 * [shellSessions] supplies read-only process presentation to actual tool rows.
 *
 * Unmounting releases renderer focus bookkeeping only. The Agent binding, not this composition,
 * closes [model] and its item children.
 * [viewState] contains only borrowed renderer geometry/input. The host retains it by exact model
 * identity across tabs, not across binding replacement. No storage is read by this renderer.
 * Pending navigation is acknowledged only after measuring its exact destination; unmounting
 * beforehand leaves it pending. Framework input/render failures propagate to the renderer host.
 * @throws IllegalStateException if [viewState] is simultaneously attached to another list, or
 * the host does not provide a finite viewport height.
 */
@Composable
public expect fun AgentHistoryView(
    model: AgentHistoryViewModel,
    shellSessions: AgentShellSessionRegistry,
    viewState: AgentHistoryViewState = remember(model) { AgentHistoryViewState() },
    onOpenEntryContextMenu: ((
        generation: Long,
        storageIndex: Int,
        item: HistoryItemViewModel,
        anchor: TuiPopupAnchor,
        clickPosition: IntOffset?,
    ) -> Unit)? = null,
): Unit
