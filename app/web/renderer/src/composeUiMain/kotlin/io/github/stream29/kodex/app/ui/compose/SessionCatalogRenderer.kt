@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.github.stream29.kodex.app.ui.compose.EditorDialog as ComposeDialog
import io.github.stream29.kodex.app.ui.compose.EditorPopup as Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessiondelete.SessionDeleteContent
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.cli.sessioncatalog.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

@Composable
public actual fun rememberComposeSessionCatalogRenderer(): SessionCatalogRenderer = remember { ComposeCatalogRenderer() }

private class ComposeCatalogRenderer : SessionCatalogRenderer {
    @Composable
    override fun Dialog(
        showArchived: Boolean, rows: SessionCatalogRows, onShowArchivedChange: (Boolean) -> Unit,
        onDismiss: () -> Unit, onOpen: (SessionCatalogEntry) -> Unit,
        onContextMenu: (SessionCatalogEntry, SessionCatalogMenuOrigin) -> Unit,
    ) {
        ComposeDialog(onDismissRequest = onDismiss) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(
                    Modifier.widthIn(max = 760.dp).heightIn(max = 720.dp).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    UiText("Sessions", UiTextRole.Title)
                    Row {
                        Checkbox(
                            showArchived, onShowArchivedChange,
                            modifier = Modifier.semantics { contentDescription = "Show archived" },
                        )
                        Text("Show archived")
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 480.dp)) {
                        when (rows) {
                            SessionCatalogRows.Loading -> item { UiText("Loading sessions…") }
                            SessionCatalogRows.Empty -> item { UiText("No persisted sessions") }
                            is SessionCatalogRows.Entries -> items(rows.entries, key = { it.sessionIndex }) { entry ->
                                CatalogRow(entry, { onOpen(entry) }) { onContextMenu(entry, it) }
                            }
                        }
                    }
                    UiActions { UiAction("Close", onClick = onDismiss) }
                }
            }
        }
    }

    @Composable
    override fun ContextMenu(
        entry: SessionCatalogEntry, origin: SessionCatalogMenuOrigin, onDismiss: () -> Unit,
        onFork: () -> Unit, onArchive: () -> Unit, onUnarchive: () -> Unit, onDelete: () -> Unit,
    ) {
        val graphical = origin as ComposeCatalogOrigin
        val bounds = graphical.bounds ?: return
        val placement = remember(bounds, graphical.point) { CatalogMenuPosition(bounds, graphical.point) }
        Popup(
            popupPositionProvider = placement, onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true, dismissOnBackPress = false),
        ) {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.widthIn(max = 480.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    UiText("Index: ${entry.sessionIndex}")
                    val zone = TimeZone.currentSystemDefault()
                    entry.createdAt?.let { UiText("Created at: ${it.toLocalDateTime(zone)}") }
                    entry.updatedAt?.let { UiText("Updated at: ${it.toLocalDateTime(zone)}") }
                    if (entry.archived) UiAction("Unarchive", autoFocus = true, onClick = onUnarchive)
                    else UiAction("Archive", autoFocus = true, onClick = onArchive)
                    UiAction("Delete", onClick = onDelete)
                    UiAction("Fork", onClick = onFork)
                }
            }
            NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = onDismiss)
        }
    }

    @Composable
    override fun Delete(viewModel: SessionDeleteViewModel, onDismiss: () -> Unit) {
        SessionDeleteContent(viewModel, onDismiss, {}, frame = { content ->
            ComposeDialog(onDismissRequest = onDismiss) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.widthIn(max = 560.dp).padding(24.dp)) { content() }
                }
            }
        })
    }
}

@Composable
private fun CatalogRow(entry: SessionCatalogEntry, onOpen: () -> Unit, onMenu: (SessionCatalogMenuOrigin) -> Unit) {
    val origin = remember(entry.sessionIndex) { ComposeCatalogOrigin() }
    DisposableEffect(origin) { onDispose { origin.bounds = null } }
    fun showMenu(point: Offset? = null) { origin.point = point; onMenu(origin) }
    Row(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { origin.bounds = it.boundsInWindow() }
            .onPointerEvent(PointerEventType.Press) {
                if (it.buttons.isSecondaryPressed) showMenu(it.changes.firstOrNull()?.position)
            }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Menu || it.key == Key.F10 && it.isShiftPressed)) {
                    showMenu(); true
                } else false
            },
    ) {
        OutlinedButton(onOpen, Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth()) {
                Text(entry.threadName ?: "Session ${entry.sessionIndex}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.running) Text("Running")
                entry.updatedAt?.let { Text("Updated: ${it.toLocalDateTime(TimeZone.currentSystemDefault())}") }
            }
        }
        TextButton(
            onClick = { showMenu() },
            modifier = Modifier.semantics { contentDescription = "Actions for session ${entry.sessionIndex}" },
        ) { Text("Actions") }
    }
}

private class ComposeCatalogOrigin : SessionCatalogMenuOrigin {
    var bounds by mutableStateOf<Rect?>(null)
    var point: Offset? = null
    override val isPlaced: Boolean get() = bounds != null
}

private class CatalogMenuPosition(private val bounds: Rect, private val point: Offset?) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        val x = (bounds.left + (point?.x ?: 0f)).roundToInt()
        val y = (if (point == null) bounds.bottom else bounds.top + point.y).roundToInt()
        val top = if (y + popupContentSize.height <= windowSize.height) y else bounds.top.roundToInt() - popupContentSize.height
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            top.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
