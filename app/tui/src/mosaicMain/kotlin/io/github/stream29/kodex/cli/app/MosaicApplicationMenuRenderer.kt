package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.*
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabMenuPlacement
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.cli.sessiontabbar.MosaicSessionTabMenuPlacement

internal class MosaicHistoryMenuPlacement(val anchor: TuiPopupAnchor, val point: IntOffset?) : ApplicationHistoryMenuPlacement

internal class MosaicApplicationMenuRenderer(private val scope: BoxScope) : ApplicationMenuRenderer {
    override fun isTabPlaced(placement: SessionTabMenuPlacement): Boolean =
        (placement as MosaicSessionTabMenuPlacement).anchor.isPlaced
    override fun isHistoryPlaced(placement: ApplicationHistoryMenuPlacement): Boolean =
        (placement as MosaicHistoryMenuPlacement).anchor.isPlaced

    @Composable
    override fun Tab(placement: SessionTabMenuPlacement, entries: List<ApplicationMenuEntry>, onDismiss: () -> Unit) {
        val origin = placement as MosaicSessionTabMenuPlacement
        Menu(origin.anchor, origin.clickPosition, entries, onDismiss)
    }
    @Composable
    override fun History(placement: ApplicationHistoryMenuPlacement, entries: List<ApplicationMenuEntry>, onDismiss: () -> Unit) {
        val origin = placement as MosaicHistoryMenuPlacement
        Menu(origin.anchor, origin.point, entries, onDismiss)
    }
    @Composable
    private fun Menu(anchor: TuiPopupAnchor, point: IntOffset?, entries: List<ApplicationMenuEntry>, onDismiss: () -> Unit) {
        with(scope) {
            TuiContextMenu(
                expanded = true, anchor = anchor, clickPosition = point, onDismissRequest = onDismiss,
                backgroundColor = TuiTheme.colorScheme.surfaceContainer,
            ) { appendApplicationEntries(entries) }
        }
    }
    @Composable
    override fun RevertConfirmation(
        title: String, paragraphs: List<String>, cancelLabel: String, confirmLabel: String,
        onCancel: () -> Unit, onConfirm: () -> Unit,
    ) {
        with(scope) {
            TuiDialog(onDismissRequest = onCancel, modifier = Modifier.width(56).background(SettingsDialogHomeBackground)) {
                Column {
                    Text(title, textStyle = TuiTheme.typography.title)
                    paragraphs.forEach { Text(it, textStyle = TuiTheme.typography.supporting) }
                    TuiDialogActionRow(Modifier.fillMaxWidth()) {
                        TuiButton(cancelLabel, autoFocus = true, onClick = onCancel)
                        TuiButton(confirmLabel, color = TuiTheme.colorScheme.error, onClick = onConfirm)
                    }
                }
            }
        }
    }
}

private fun TuiPopupMenuScope.appendApplicationEntries(entries: List<ApplicationMenuEntry>) {
    entries.forEach { entry ->
        when (entry) {
            is ApplicationMenuEntry.Information -> TuiPopupMenuItem(entry.key, {}, enabled = false) {
                Column { entry.lines.forEach { Text(it) } }
            }
            is ApplicationMenuEntry.Action -> TuiPopupMenuItem(entry.key, entry.onClick, dismissOnClick = false) { Text(entry.label) }
        }
    }
}

/** Native menu entrypoint over the same shared projection used by the root and graphical host. */
@Composable
internal fun BoxScope.SessionTabContextMenuPopup(
    target: PersistedSessionViewModel, anchor: TuiPopupAnchor, clickPosition: IntOffset?,
    onDismiss: () -> Unit, onClose: () -> Unit, onCloseAndArchive: (PersistedSessionViewModel) -> Unit,
    onRename: () -> Unit, onDelete: () -> Unit, createdAt: String? = null, updatedAt: String? = null,
) {
    val renderer = remember(this) { MosaicApplicationMenuRenderer(this) }
    renderer.Tab(
        MosaicSessionTabMenuPlacement(anchor, clickPosition),
        applicationTabMenuEntries(target.sessionIndex, createdAt, updatedAt, onRename, onClose, { onCloseAndArchive(target) }, onDelete),
        onDismiss,
    )
}

@Composable
internal fun BoxScope.HistoryEntryContextMenuPopup(
    anchor: TuiPopupAnchor, clickPosition: IntOffset?, onDismiss: () -> Unit,
    onRevert: () -> Unit, onFork: () -> Unit, messageIndex: Int? = null, timestamp: String? = null,
    onRevertAndEdit: (() -> Unit)? = null,
) {
    val renderer = remember(this) { MosaicApplicationMenuRenderer(this) }
    renderer.History(
        MosaicHistoryMenuPlacement(anchor, clickPosition),
        applicationHistoryMenuEntries(messageIndex, timestamp, onRevert, onFork, onRevertAndEdit),
        onDismiss,
    )
}

@Composable
internal fun BoxScope.AgentHistoryRevertDialog(agent: AgentViewModel?) {
    AgentHistoryRevertContent(agent, remember(this) { MosaicApplicationMenuRenderer(this) })
}
