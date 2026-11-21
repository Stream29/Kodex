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
import androidx.compose.ui.semantics.selected
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
import io.github.stream29.kodex.cli.components.formatPopupTimestamp
import io.github.stream29.kodex.cli.sessioncatalog.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

@Composable
internal fun rememberComposeSessionCatalogRenderer(): SessionCatalogRenderer {
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
        onArchive: () -> Unit, onUnarchive: () -> Unit, onDelete: () -> Unit,
    ) = CatalogContextMenu(
        entry, origin, restoration, onDismiss,
        onToggleArchived = if (entry.archived) onUnarchive else onArchive, onDelete = onDelete,
    )

    @Composable
    override fun Delete(viewModel: SessionDeleteViewModel, onDismiss: () -> Unit) = CatalogDelete(viewModel, onDismiss)
}

@Composable
internal fun CatalogContextMenu(
    entry: SessionCatalogEntry,
    origin: SessionCatalogMenuOrigin,
    restoration: RowMenuFocusRestoration,
    onDismiss: () -> Unit,
    onToggleArchived: () -> Unit,
    onDelete: () -> Unit,
    onRename: (() -> Unit)? = null,
    mayRestore: () -> Boolean = { true },
) {
    val graphical = origin as ComposeCatalogOrigin
    val valid = graphical.isPlaced && graphical.entry == entry
    restoration.Track(origin, graphical.fallback, mayRestore) { graphical.isPlaced && graphical.entry == entry }
    LaunchedEffect(origin, valid) { if (!valid) onDismiss() }
    if (!valid) return
    val bounds = graphical.bounds ?: return
    key(origin) {
        var available by remember { mutableStateOf(true) }
        DisposableEffect(Unit) { onDispose { available = false } }
        fun run(command: () -> Unit) {
            if (!available || !graphical.isPlaced || graphical.entry != entry) return
            available = false
            command()
        }
        CatalogMenuPopup(origin, bounds, graphical.point, onDismiss = { run(onDismiss) }) { first ->
            UiText(entry.name ?: "Session ${entry.sessionIndex}")
            UiText("Index: ${entry.sessionIndex}")
            val zone = TimeZone.currentSystemDefault()
            entry.createdAt?.let { UiText("Created at: ${formatPopupTimestamp(it, zone)}") }
            entry.updatedAt?.let { UiText("Updated at: ${formatPopupTimestamp(it, zone)}") }
            if (onRename != null) {
                CompactTextButton({ run(onRename) }, Modifier.fillMaxWidth().focusRequester(first)) {
                    Text("Rename", Modifier.weight(1f))
                }
            }
            CompactTextButton(
                { run(onToggleArchived) },
                Modifier.fillMaxWidth().then(if (onRename == null) Modifier.focusRequester(first) else Modifier),
            ) { Text(if (entry.archived) "Unarchive" else "Archive", Modifier.weight(1f)) }
            CompactTextButton(
                { run(onDelete) }, Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete", Modifier.weight(1f)) }
        }
    }
}

@Composable
internal fun CatalogMenuPopup(
    opening: Any,
    bounds: Rect,
    point: Offset?,
    onDismiss: () -> Unit,
    content: @Composable (FocusRequester) -> Unit,
) {
    val placement = remember(bounds, point) { CatalogMenuPosition(bounds, point) }
    val first = remember(opening) { FocusRequester() }
    Popup(
        popupPositionProvider = placement, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = false),
    ) {
        Surface(shape = RoundedCornerShape(4.dp), shadowElevation = 3.dp) {
            Column(
                Modifier.width(IntrinsicSize.Max).widthIn(min = 160.dp, max = 480.dp)
                    .heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) { content(first) }
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
internal fun CatalogDelete(viewModel: SessionDeleteViewModel, onDismiss: () -> Unit) {
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

@Composable
internal fun CatalogRow(
    entry: SessionCatalogEntry,
    fallback: FocusRequester,
    onOpen: () -> Unit,
    selected: Boolean? = null,
    onMenu: (SessionCatalogMenuOrigin) -> Unit,
) {
    val anchor = remember(entry.sessionIndex, entry.rootAgentId, fallback) { CatalogRowAnchor(entry, fallback) }
    SideEffect { anchor.entry = entry }
    val focus = remember(anchor) { FocusRequester() }
    DisposableEffect(anchor) { onDispose { anchor.bounds = null } }
    fun focusRow(): Boolean =
        anchor.bounds?.let { it.width > 0 && it.height > 0 } == true &&
            try { focus.requestFocus() } catch (_: IllegalStateException) { false }
    fun showMenu(point: Offset? = null) {
        val bounds = anchor.bounds ?: return
        val relativePoint = point?.let { anchor.windowOrigin + it - bounds.topLeft }
        onMenu(ComposeCatalogOrigin(anchor, entry, relativePoint))
    }
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .onGloballyPositioned {
                anchor.windowOrigin = it.localToWindow(Offset.Zero)
                anchor.bounds = it.boundsInWindow()
            }
            .onPointerEvent(PointerEventType.Press) {
                if (it.buttons.isSecondaryPressed) {
                    if (selected != null) focusRow()
                    showMenu(it.changes.firstOrNull()?.position)
                }
            }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && (it.key == Key.Menu || it.key == Key.F10 && it.isShiftPressed)) {
                    showMenu(); true
                } else false
            }, verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactTextButton(
            onOpen,
            Modifier.weight(1f).focusRequester(focus).semantics {
                contentDescription = entry.name ?: "Session ${entry.sessionIndex}"
                testTag = "session-catalog-row-${entry.sessionIndex}"
                if (selected != null) this.selected = selected
                stateDescription = buildList {
                    if (entry.running) add("Running")
                    if (entry.archived) add("Archived")
                }.joinToString("; ")
            },
            colors = when (selected) {
                true -> ButtonDefaults.textButtonColors(
                    containerColor = colors.secondaryContainer, contentColor = colors.onSecondaryContainer,
                )
                false -> ButtonDefaults.textButtonColors(contentColor = colors.onSurface)
                null -> ButtonDefaults.textButtonColors()
            },
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(entry.name ?: "Session ${entry.sessionIndex}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge)
                if (entry.running || selected != null && entry.archived) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (entry.running) {
                            RunningSpinner()
                            Text("Running", style = MaterialTheme.typography.bodySmall)
                        }
                        if (selected != null && entry.archived) Text("Archived", style = MaterialTheme.typography.bodySmall)
                    }
                }
                entry.updatedAt?.let {
                    Text(
                        "Updated: ${it.toLocalDateTime(TimeZone.currentSystemDefault())}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (selected == null) Int.MAX_VALUE else 1,
                        overflow = if (selected == null) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                }
            }
        }
        SemanticRowMenuAction("Open menu for session ${entry.sessionIndex}") {
            if (!focusRow()) false else {
                showMenu()
                true
            }
        }
    }
}

private class CatalogRowAnchor(entry: SessionCatalogEntry, val fallback: FocusRequester) {
    var entry by mutableStateOf(entry)
    var bounds by mutableStateOf<Rect?>(null)
    var windowOrigin = Offset.Zero
}

private class ComposeCatalogOrigin(
    private val anchor: CatalogRowAnchor,
    val entry: SessionCatalogEntry,
    val point: Offset?,
) : SessionCatalogMenuOrigin {
    val fallback: FocusRequester get() = anchor.fallback
    val bounds: Rect? get() = anchor.bounds
    override val isPlaced: Boolean
        get() = anchor.entry == entry && bounds?.let { it.width > 0 && it.height > 0 } == true
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
