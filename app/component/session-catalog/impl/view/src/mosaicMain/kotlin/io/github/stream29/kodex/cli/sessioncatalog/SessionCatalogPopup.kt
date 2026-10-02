package io.github.stream29.kodex.cli.sessioncatalog

import io.github.stream29.kodex.cli.components.RunningIndicatorFrames
import io.github.stream29.kodex.cli.components.rememberRunningIndicatorFrame
import io.github.stream29.kodex.cli.components.formatPopupTimestamp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Arrangement
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiCheckbox
import io.github.stream29.kodex.cli.components.TuiContextMenu
import io.github.stream29.kodex.cli.components.TuiDialog
import io.github.stream29.kodex.cli.components.TuiDialogActionRow
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.cli.sessiondelete.SessionDeletePopup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/**
 * Complete Select Session renderer for one host-owned catalog child.
 *
 * Refreshes once on mounting [viewModel]; Unloaded/Loading show animated progress,
 * Loaded empty shows the empty message, and completed rows preserve backend order.
 * Menu dates/labels/running flags use that captured snapshot without extra I/O.
 * Geometry, focus, scrolling and animation are renderer-only; business deletion
 * renders the VM's exact existing Session Delete child. Unmount cancels renderer
 * callers and dismisses its captured confirmation, not the catalog/backend.
 *
 * Open and fork failures are already reported by the host registry and do not
 * dismiss the popup; cancellation is rethrown. Other command failures escape the
 * renderer caller. No failure is rendered as a successfully loaded empty list.
 */
@Composable
public fun BoxScope.SessionCatalogPopup(viewModel: SessionCatalogViewModel) {
    key(viewModel) { SessionCatalogPopupContent(viewModel) }
}

@Composable
private fun BoxScope.SessionCatalogPopupContent(viewModel: SessionCatalogViewModel) {
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()
    val deleteTarget by viewModel.deleteTarget.collectAsState()
    val runningFrame by rememberRunningIndicatorFrame(active = state.sessions.any { it.running })
    var contextMenu by remember(viewModel) { mutableStateOf<SessionCatalogMenuRequest?>(null) }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    TuiDialog(
        onDismissRequest = viewModel::dismiss,
        modifier = Modifier.width(SessionCatalogWidth).background(TuiTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SessionCatalogHeader(
                showArchived = state.showArchived,
                onShowArchivedChange = { showArchived ->
                    contextMenu = null
                    scope.launch { viewModel.setShowArchived(showArchived) }
                },
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().height(SessionCatalogRows)) {
                when (val current = state) {
                    SessionCatalogState.Unloaded,
                    is SessionCatalogState.Loading -> item { SessionCatalogLoadingIndicator() }
                    is SessionCatalogState.Loaded -> if (current.sessions.isEmpty()) {
                        item { Text("No persisted sessions", color = TuiTheme.colorScheme.onSurface) }
                    } else {
                        items(current.sessions, key = { it.sessionIndex }) { entry ->
                            SessionCatalogRow(
                                entry = entry,
                                maximumLabelColumns = SessionCatalogWidth - 2,
                                runningIndicatorFrame = runningFrame,
                                onClick = {
                                    if (viewModel.state.value is SessionCatalogState.Loaded &&
                                        viewModel.state.value.sessions.any { it == entry }
                                    ) {
                                        contextMenu = null
                                        scope.launch {
                                            try {
                                                viewModel.requestOpen(entry)
                                            } catch (cancellation: CancellationException) {
                                                throw cancellation
                                            } catch (_: Throwable) {
                                                // The host Session registry reports open failures.
                                            }
                                        }
                                    }
                                },
                                onOpenContextMenu = { anchor, clickPosition ->
                                    if (viewModel.state.value is SessionCatalogState.Loaded &&
                                        viewModel.state.value.sessions.any { it == entry }
                                    ) contextMenu = SessionCatalogMenuRequest(entry, anchor, clickPosition)
                                },
                            )
                        }
                    }
                }
            }
            TuiDialogActionRow(
                modifier = Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surfaceContainerHigh),
            ) {
                TuiButton(label = "Close", color = TuiTheme.colorScheme.onSurface, onClick = viewModel::dismiss)
            }
        }
    }
    contextMenu?.let { request ->
        val valid = state is SessionCatalogState.Loaded &&
            state.sessions.any { it == request.entry } && request.anchor.isPlaced
        LaunchedEffect(request, valid) {
            if (!valid && contextMenu === request) contextMenu = null
        }
        if (!valid) return@let
        // Dispatch only this exact menu; delayed callbacks cannot clear/operate a newer menu.
        fun consume(action: () -> Unit) {
            if (contextMenu !== request || viewModel.state.value !is SessionCatalogState.Loaded ||
                viewModel.state.value.sessions.none { it == request.entry }
            ) return
            contextMenu = null
            action()
        }
        val timeZone = TimeZone.currentSystemDefault()
        SessionCatalogContextMenuPopup(
            entry = request.entry,
            anchor = request.anchor,
            clickPosition = request.clickPosition,
            createdAt = request.entry.createdAt?.let { formatPopupTimestamp(it, timeZone) },
            updatedAt = request.entry.updatedAt?.let { formatPopupTimestamp(it, timeZone) },
            onDismissRequest = { if (contextMenu === request) contextMenu = null },
            onFork = {
                consume {
                    scope.launch {
                        try {
                            viewModel.fork(request.entry.sessionIndex)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            // The host registry/catalog reports fork failures.
                        }
                    }
                }
            },
            onArchive = { consume { scope.launch { viewModel.archive(request.entry.sessionIndex) } } },
            onUnarchive = { consume { scope.launch { viewModel.unarchive(request.entry.sessionIndex) } } },
            onDelete = { consume { viewModel.requestDelete(request.entry) } },
        )
    }
    deleteTarget?.let { handle ->
        DisposableEffect(viewModel, handle) {
            onDispose { viewModel.dismissDelete(handle) }
        }
        SessionDeletePopup(
            viewModel = handle.viewModel,
            onDismissRequest = { viewModel.dismissDelete(handle) },
            // The VM consumes true after successful reload. False leaves this exact child.
            onResult = {},
        )
    }
}

/** Header helper: archive checkbox reflects the VM snapshot, never renderer-local filter state. */
@Composable
internal fun SessionCatalogHeader(
    showArchived: Boolean,
    onShowArchivedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().background(TuiTheme.colorScheme.surfaceContainerHigh),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Sessions", color = TuiTheme.colorScheme.onSurface, textStyle = TuiTheme.typography.headline)
        TuiCheckbox(
            label = "Show archived",
            checked = showArchived,
            onCheckedChange = onShowArchivedChange,
            color = TuiTheme.colorScheme.onSurface,
        )
    }
}

/** Row helper: primary click opens; secondary click/Menu/Shift-F10 supplies its exact anchor. */
@Composable
internal fun SessionCatalogRow(
    entry: SessionCatalogEntry,
    maximumLabelColumns: Int,
    runningIndicatorFrame: String = RunningIndicatorFrames.first(),
    onClick: () -> Unit,
    onOpenContextMenu: (TuiPopupAnchor, IntOffset?) -> Unit,
) {
    val anchor = rememberTuiPopupAnchor()
    TuiButton(
        label = entry.sessionBrowserLabel(maximumLabelColumns, runningFrame = runningIndicatorFrame),
        modifier = Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surfaceContainer).tuiPopupAnchor(anchor),
        color = TuiTheme.colorScheme.onSurface,
        onClick = onClick,
        onSecondaryClick = { onOpenContextMenu(anchor, it) },
    )
}

/**
 * Snapshot-only menu helper. Null dates omit their fields; null click position
 * selects keyboard anchor placement. Informational fields are not selectable.
 */
@Composable
internal fun BoxScope.SessionCatalogContextMenuPopup(
    entry: SessionCatalogEntry,
    anchor: TuiPopupAnchor,
    clickPosition: IntOffset?,
    onDismissRequest: () -> Unit,
    onFork: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
    createdAt: String? = null,
    updatedAt: String? = null,
) {
    TuiContextMenu(
        expanded = true,
        anchor = anchor,
        clickPosition = clickPosition,
        onDismissRequest = onDismissRequest,
        backgroundColor = TuiTheme.colorScheme.surfaceContainer,
    ) {
        TuiPopupMenuItem(key = "session-index-information", onClick = {}, enabled = false) {
            Column {
                Text("Index: ${entry.sessionIndex}")
                if (createdAt != null) Text("Created at: $createdAt")
                if (updatedAt != null) Text("Updated at: $updatedAt")
            }
        }
        TuiPopupMenuItem(
            key = if (entry.archived) "unarchive" else "archive",
            onClick = if (entry.archived) onUnarchive else onArchive,
            dismissOnClick = false,
        ) { Text(if (entry.archived) "Unarchive" else "Archive") }
        // Exact menu admission clears its own request. Generic pre-action dismissal
        // would clear that identity before consume() and reject every valid command.
        TuiPopupMenuItem(key = "delete", onClick = onDelete, dismissOnClick = false) { Text("Delete") }
        TuiPopupMenuItem(key = "fork", onClick = onFork, dismissOnClick = false) { Text("Fork") }
    }
}

/** Progress helper; never implies a successful empty catalog. */
@Composable
internal fun SessionCatalogLoadingIndicator() {
    val frame by rememberRunningIndicatorFrame(active = true)
    Text("$frame Loading sessions…", color = TuiTheme.colorScheme.onSurface)
}

private class SessionCatalogMenuRequest(
    val entry: SessionCatalogEntry,
    val anchor: TuiPopupAnchor,
    val clickPosition: IntOffset?,
)

private const val SessionCatalogWidth: Int = 64
private const val SessionCatalogRows: Int = 16
