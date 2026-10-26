package io.github.stream29.kodex.cli.sessioncatalog

import io.github.stream29.kodex.cli.components.RunningIndicatorFrames
import io.github.stream29.kodex.cli.components.rememberRunningIndicatorFrame
import io.github.stream29.kodex.cli.components.formatPopupTimestamp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel
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
import kotlinx.datetime.TimeZone

@Composable
public actual fun BoxScope.SessionCatalogPopup(
    viewModel: SessionCatalogViewModel,
    onFailure: (Throwable) -> Unit,
) {
    SessionCatalogContent(viewModel, remember(this) { MosaicCatalogRenderer(this) }, onFailure)
}

private class MosaicCatalogRenderer(private val box: BoxScope) : SessionCatalogRenderer {
    @Composable
    override fun Dialog(
        showArchived: Boolean, rows: SessionCatalogRows, onShowArchivedChange: (Boolean) -> Unit,
        onDismiss: () -> Unit, onOpen: (SessionCatalogEntry) -> Unit,
        onContextMenu: (SessionCatalogEntry, SessionCatalogMenuOrigin) -> Unit,
    ) = with(box) {
        val runningFrame by rememberRunningIndicatorFrame(
            active = (rows as? SessionCatalogRows.Entries)?.entries?.any { it.running } == true,
        )
        TuiDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.width(SessionCatalogWidth).background(TuiTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SessionCatalogHeader(
                    showArchived = showArchived,
                    onShowArchivedChange = onShowArchivedChange,
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().height(SessionCatalogViewportRows)) {
                    when (rows) {
                        SessionCatalogRows.Loading -> item { SessionCatalogLoadingIndicator() }
                        SessionCatalogRows.Empty -> {
                            item { Text("No persisted sessions", color = TuiTheme.colorScheme.onSurface) }
                        }
                        is SessionCatalogRows.Entries -> {
                            items(rows.entries, key = { it.sessionIndex }) { entry ->
                                SessionCatalogRow(
                                    entry = entry,
                                    maximumLabelColumns = SessionCatalogWidth - 2,
                                    runningIndicatorFrame = runningFrame,
                                    onClick = { onOpen(entry) },
                                    onOpenContextMenu = { anchor, clickPosition ->
                                        onContextMenu(entry, MosaicCatalogOrigin(anchor, clickPosition))
                                    },
                                )
                            }
                        }
                    }
                }
                TuiDialogActionRow(
                    modifier = Modifier.fillMaxWidth().background(TuiTheme.colorScheme.surfaceContainerHigh),
                ) {
                    TuiButton(label = "Close", color = TuiTheme.colorScheme.onSurface, onClick = onDismiss)
                }
            }
        }
    }

    @Composable
    override fun ContextMenu(
        entry: SessionCatalogEntry, origin: SessionCatalogMenuOrigin, onDismiss: () -> Unit,
        onFork: () -> Unit, onArchive: () -> Unit, onUnarchive: () -> Unit, onDelete: () -> Unit,
    ) = with(box) {
        val native = origin as MosaicCatalogOrigin
        val timeZone = TimeZone.currentSystemDefault()
        SessionCatalogContextMenuPopup(
            entry = entry,
            anchor = native.anchor,
            clickPosition = native.clickPosition,
            createdAt = entry.createdAt?.let { formatPopupTimestamp(it, timeZone) },
            updatedAt = entry.updatedAt?.let { formatPopupTimestamp(it, timeZone) },
            onDismissRequest = onDismiss,
            onFork = onFork, onArchive = onArchive, onUnarchive = onUnarchive, onDelete = onDelete,
        )
    }
    @Composable
    override fun Delete(viewModel: SessionDeleteViewModel, onDismiss: () -> Unit) = with(box) {
        SessionDeletePopup(
            viewModel = viewModel,
            onDismissRequest = onDismiss,
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

private class MosaicCatalogOrigin(
    val anchor: TuiPopupAnchor,
    val clickPosition: IntOffset?,
) : SessionCatalogMenuOrigin {
    override val isPlaced: Boolean get() = anchor.isPlaced
}

private const val SessionCatalogWidth: Int = 64
private const val SessionCatalogViewportRows: Int = 16
