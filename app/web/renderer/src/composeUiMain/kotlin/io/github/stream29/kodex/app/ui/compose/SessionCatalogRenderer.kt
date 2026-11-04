@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
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
public actual fun rememberComposeSessionCatalogRenderer(): SessionCatalogRenderer {
    val scope = rememberCoroutineScope()
    return remember(scope) { ComposeCatalogRenderer(RowMenuFocusRestoration(scope)) }
}

private class ComposeCatalogRenderer(private val restoration: RowMenuFocusRestoration) : SessionCatalogRenderer {
    @Composable
    override fun Dialog(
        showArchived: Boolean, rows: SessionCatalogRows, onShowArchivedChange: (Boolean) -> Unit,
        onDismiss: () -> Unit, onOpen: (SessionCatalogEntry) -> Unit,
        onContextMenu: (SessionCatalogEntry, SessionCatalogMenuOrigin) -> Unit,
    ) {
        val fallback = remember { FocusRequester() }
        ComposeDialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(
                    Modifier.widthIn(max = 760.dp).heightIn(max = 720.dp).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    UiText("Sessions", UiTextRole.Title)
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                CatalogRow(entry, fallback, { onOpen(entry) }) { onContextMenu(entry, it) }
                            }
                        }
                    }
                    UiActions { Box(Modifier.focusRequester(fallback)) { UiAction("Close", onClick = onDismiss) } }
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
        restoration.Track(graphical, graphical.fallback) { graphical.isPlaced && graphical.entry == entry }
        val bounds = graphical.bounds ?: return
        val placement = remember(bounds, graphical.point) { CatalogMenuPosition(bounds, graphical.point) }
        val first = remember(origin) { FocusRequester() }
        Popup(
            popupPositionProvider = placement, onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true, dismissOnBackPress = false),
        ) {
            Surface(shape = RoundedCornerShape(4.dp), shadowElevation = 3.dp) {
                Column(
                    Modifier.width(IntrinsicSize.Max).widthIn(min = 160.dp, max = 480.dp)
                        .heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    UiText(entry.threadName ?: "Session ${entry.sessionIndex}")
                    UiText("Index: ${entry.sessionIndex}")
                    val zone = TimeZone.currentSystemDefault()
                    entry.createdAt?.let { UiText("Created at: ${it.toLocalDateTime(zone)}") }
                    entry.updatedAt?.let { UiText("Updated at: ${it.toLocalDateTime(zone)}") }
                    CompactTextButton(
                        if (entry.archived) onUnarchive else onArchive,
                        Modifier.fillMaxWidth().focusRequester(first),
                    ) { Text(if (entry.archived) "Unarchive" else "Archive", Modifier.weight(1f)) }
                    CompactTextButton(
                        onDelete, Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete", Modifier.weight(1f)) }
                    CompactTextButton(onFork, Modifier.fillMaxWidth()) { Text("Fork", Modifier.weight(1f)) }
                }
            }
            NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = onDismiss)
        }
        LaunchedEffect(first) {
            repeat(3) {
                withFrameNanos { }
                if (try { first.requestFocus() } catch (_: IllegalStateException) { false }) return@LaunchedEffect
            }
        }
    }

    @Composable
    override fun Delete(viewModel: SessionDeleteViewModel, onDismiss: () -> Unit) {
        SessionDeleteContent(viewModel, onDismiss, {}, frame = { content ->
            ComposeDialog(onDismissRequest = onDismiss) {
                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(
                        Modifier.widthIn(max = 560.dp).heightIn(max = 560.dp)
                            .verticalScroll(rememberScrollState()).padding(12.dp),
                    ) { content() }
                }
            }
        })
    }
}

@Composable
private fun CatalogRow(
    entry: SessionCatalogEntry, fallback: FocusRequester, onOpen: () -> Unit, onMenu: (SessionCatalogMenuOrigin) -> Unit,
) {
    val origin = remember(entry.sessionIndex, fallback) { ComposeCatalogOrigin(entry, fallback) }
    SideEffect { origin.entry = entry }
    val focus = remember(origin) { FocusRequester() }
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
            }, verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactTextButton(onOpen, Modifier.weight(1f).focusRequester(focus).semantics {
            contentDescription = entry.threadName ?: "Session ${entry.sessionIndex}"
            testTag = "session-catalog-row-${entry.sessionIndex}"
            stateDescription = buildList {
                if (entry.running) add("Running")
                if (entry.archived) add("Archived")
            }.joinToString("; ")
        }) {
            Column(Modifier.fillMaxWidth()) {
                Text(entry.threadName ?: "Session ${entry.sessionIndex}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge)
                if (entry.running) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RunningSpinner()
                        Text("Running", style = MaterialTheme.typography.bodySmall)
                    }
                }
                entry.updatedAt?.let { Text("Updated: ${it.toLocalDateTime(TimeZone.currentSystemDefault())}",
                    style = MaterialTheme.typography.bodySmall) }
            }
        }
        SemanticRowMenuAction("Open menu for session ${entry.sessionIndex}") {
            val focused = origin.isPlaced && try { focus.requestFocus() } catch (_: IllegalStateException) { false }
            if (!focused) false else {
                showMenu()
                true
            }
        }
    }
}

private class ComposeCatalogOrigin(var entry: SessionCatalogEntry, val fallback: FocusRequester) : SessionCatalogMenuOrigin {
    var bounds by mutableStateOf<Rect?>(null)
    var point: Offset? = null
    override val isPlaced: Boolean get() = bounds?.let { it.width > 0 && it.height > 0 } == true
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
