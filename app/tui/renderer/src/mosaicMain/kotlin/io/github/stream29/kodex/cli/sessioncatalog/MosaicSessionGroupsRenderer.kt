package io.github.stream29.kodex.cli.sessioncatalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxHeight
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.onPointerHover
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel
import io.github.stream29.kodex.cli.components.EllipsizedText
import io.github.stream29.kodex.cli.components.LazyColumn
import io.github.stream29.kodex.cli.components.LazyListState
import io.github.stream29.kodex.cli.components.RunningIndicatorFrames
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiPopup
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPopupMenu
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiPressable
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.formatPopupTimestamp
import io.github.stream29.kodex.cli.components.items
import io.github.stream29.kodex.cli.components.rememberLazyListState
import io.github.stream29.kodex.cli.components.rememberRunningIndicatorFrame
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.runningIndicatorLabel
import io.github.stream29.kodex.cli.components.tuiInteractionTextStyle
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import io.github.stream29.kodex.cli.components.wrapToTerminalWidth
import io.github.stream29.kodex.cli.sessiondelete.SessionDeletePopup
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone

@Composable
public actual fun BoxScope.rememberMosaicSessionGroupsRenderer(
    columns: Int,
    modifier: Modifier,
): MosaicSessionGroupsRenderer {
    val width = rememberUpdatedState(columns.coerceAtLeast(0))
    val panelModifier = rememberUpdatedState(modifier)
    return remember(this) { MosaicSessionGroupsRendererImpl(this, width, panelModifier) }
}

private class MosaicSessionGroupsRendererImpl(
    private val host: BoxScope,
    private val columns: State<Int>,
    private val panelModifier: State<Modifier>,
) : MosaicSessionGroupsRenderer {
    private val entryMenu = HostOverlaySlot()
    private val deleteDialog = HostOverlaySlot()
    private var archiveMenu by mutableStateOf<GroupArchiveMenu?>(null)
    private var pathHover by mutableStateOf<GroupPathHover?>(null)

    @Composable
    override fun Panel(
        rows: List<SessionGroupRow>,
        onRefresh: () -> Unit,
        onCollapse: () -> Unit,
        onToggleGroup: (String?) -> Unit,
        onNewInGroup: (String?) -> Unit,
        onToggleArchived: (String?) -> Unit,
        onToggleLimit: (String?) -> Unit,
        onOpen: (SessionGroupRow.Entry) -> Unit,
        onContextMenu: (SessionGroupRow.Entry, SessionCatalogMenuOrigin) -> Unit,
    ) {
        val width = columns.value
        if (width == 0) return
        val listState = rememberLazyListState()
        val latestToggleArchived = rememberUpdatedState(onToggleArchived)
        val hasVisibleRunning by remember(listState, rows) {
            derivedStateOf {
                listState.layoutInfo.visibleItemsInfo.any { visible ->
                    when (val row = rows.getOrNull(visible.index)) {
                        is SessionGroupRow.Header -> row.hasRunning
                        is SessionGroupRow.Entry -> row.entry.running
                        else -> false
                    }
                }
            }
        }
        val runningFrame = rememberRunningIndicatorFrame(hasVisibleRunning)
        DisposableEffect(this) {
            onDispose {
                archiveMenu = null
                pathHover = null
            }
        }
        Column(panelModifier.value.width(width).fillMaxHeight().background(TuiTheme.colorScheme.surfaceContainer)) {
            SessionsPanelHeader(width, onRefresh, onCollapse)
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), state = listState) {
                items(rows, key = SessionGroupRow::mosaicKey, contentType = SessionGroupRow::mosaicType) { row ->
                    when (row) {
                        SessionGroupRow.Loading -> SessionCatalogLoadingIndicator()
                        SessionGroupRow.Empty -> Text("No persisted sessions", color = TuiTheme.colorScheme.onSurface)
                        is SessionGroupRow.Header -> GroupHeader(
                            row, width, runningFrame, listState, latestToggleArchived, onToggleGroup, onNewInGroup,
                        )
                        is SessionGroupRow.LoadingEntries -> Row(Modifier.padding(left = SessionGroupIndent)) {
                            SessionCatalogLoadingIndicator()
                        }
                        is SessionGroupRow.Entry -> {
                            val frame = if (row.entry.running) runningFrame.value else RunningIndicatorFrames.first()
                            if (width < SessionGroupIndent + 2) {
                                Text("", modifier = Modifier.height(1))
                            } else {
                                SessionCatalogRow(
                                    entry = row.entry,
                                    maximumLabelColumns = width - SessionGroupIndent - 2,
                                    runningIndicatorFrame = frame,
                                    indentColumns = SessionGroupIndent,
                                    selected = row.selected,
                                    onClick = { onOpen(row) },
                                    onOpenContextMenu = { anchor, position ->
                                        val origin = MosaicCatalogOrigin(anchor, position) { listState.contains(row) }
                                        if (origin.isPlaced) onContextMenu(row, origin)
                                    },
                                )
                            }
                        }
                        is SessionGroupRow.Limit -> {
                            val label = row.hiddenCount?.let { "Show more ($it)" } ?: "Show less"
                            TuiPressable(
                                modifier = Modifier.fillMaxWidth().height(1).padding(left = SessionGroupIndent),
                                onClick = { onToggleLimit(row.cwd) },
                            ) { _, hovered, pressed ->
                                EllipsizedText(
                                    value = label,
                                    color = TuiTheme.colorScheme.onSurfaceVariant,
                                    textStyle = tuiInteractionTextStyle(hovered = hovered, pressed = pressed),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun GroupHeader(
        row: SessionGroupRow.Header,
        width: Int,
        runningFrame: State<String>,
        listState: LazyListState,
        onToggleArchived: State<(String?) -> Unit>,
        onToggleGroup: (String?) -> Unit,
        onNewInGroup: (String?) -> Unit,
    ) {
        val colors = TuiTheme.colorScheme
        val menuAnchor = rememberTuiPopupAnchor()
        val pathAnchor = rememberTuiPopupAnchor()
        val showArchived = rememberUpdatedState(row.showArchived)
        fun isVisible(): Boolean = listState.contains(row)
        fun clearHover() {
            if (pathHover?.anchor === pathAnchor) pathHover = null
        }
        var hovered by remember { mutableStateOf(false) }
        LaunchedEffect(hovered, row.cwd) {
            val cwd = row.cwd
            if (hovered && cwd != null) {
                delay(350)
                if (pathAnchor.isPlaced && isVisible()) {
                    pathHover = GroupPathHover(pathAnchor, cwd, ::isVisible)
                }
            }
        }
        DisposableEffect(pathAnchor) { onDispose { clearHover() } }
        Row(Modifier.fillMaxWidth().height(1).background(colors.surfaceContainerHigh)) {
            val menuFits = width >= 3
            val newFits = width >= 6
            val titleFits = width > (if (newFits) 6 else if (menuFits) 3 else 0)
            TuiPressable(
                modifier = Modifier.weight(1f).tuiPopupAnchor(pathAnchor).onPointerHover(
                    onPointerEnter = { hovered = true },
                    onPointerExit = { hovered = false; clearHover() },
                ),
                enabled = titleFits,
                onClick = { onToggleGroup(row.cwd) },
            ) { _, over, pressed ->
                val subtitle = row.subtitle
                val title = (if (row.expanded) "▾ " else "▸ ") + runningIndicatorLabel(
                    name = row.title.visiblePathText(),
                    running = row.hasRunning,
                    frame = if (row.hasRunning) runningFrame.value else "",
                )
                Row {
                    EllipsizedText(
                        value = title,
                        modifier = Modifier.weight(2f, fill = false),
                        color = colors.onSurface,
                        textStyle = tuiInteractionTextStyle(hovered = over, pressed = pressed),
                    )
                    if (subtitle != null) {
                        EllipsizedText(
                            value = " ${subtitle.visiblePathText()}",
                            modifier = Modifier.weight(1f, fill = false),
                            color = colors.onSurfaceVariant,
                            textStyle = TuiTheme.typography.supporting,
                        )
                    }
                }
            }
            if (newFits) {
                TuiButton(label = "+", color = colors.onSurface, onClick = { onNewInGroup(row.cwd) })
            }
            if (menuFits) {
                TuiButton(label = "…", modifier = Modifier.tuiPopupAnchor(menuAnchor), color = colors.onSurface) {
                    if (menuAnchor.isPlaced && isVisible()) {
                        pathHover = null
                        archiveMenu = GroupArchiveMenu(menuAnchor, showArchived, ::isVisible) {
                            onToggleArchived.value(row.cwd)
                        }
                    }
                }
            }
        }
    }

    @Composable
    override fun ContextMenu(
        row: SessionGroupRow.Entry,
        origin: SessionCatalogMenuOrigin,
        onDismiss: () -> Unit,
        onRename: () -> Unit,
        onToggleArchived: () -> Unit,
        onDelete: () -> Unit,
    ) {
        val native = origin as MosaicCatalogOrigin
        entryMenu.Publish {
            if (native.isPlaced) key(native) {
                val timeZone = TimeZone.currentSystemDefault()
                with(host) {
                    SessionCatalogContextMenuPopup(
                        entry = row.entry,
                        anchor = native.anchor,
                        clickPosition = native.clickPosition,
                        createdAt = row.entry.createdAt?.let { formatPopupTimestamp(it, timeZone) },
                        updatedAt = row.entry.updatedAt?.let { formatPopupTimestamp(it, timeZone) },
                        onDismissRequest = onDismiss,
                        onRename = onRename,
                        onArchive = onToggleArchived,
                        onUnarchive = onToggleArchived,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }

    @Composable
    override fun Delete(viewModel: SessionDeleteViewModel, onDismiss: () -> Unit) {
        deleteDialog.Publish {
            key(viewModel) {
                with(host) { SessionDeletePopup(viewModel, onDismissRequest = onDismiss, onResult = {}) }
            }
        }
    }

    @Composable
    override fun Overlays() = with(host) {
        val groupMenu = archiveMenu
        val hover = pathHover
        if (hover != null) {
            val valid = hover.isPlaced
            LaunchedEffect(hover, valid) {
                if (!valid && pathHover === hover) pathHover = null
            }
            if (valid && groupMenu == null && !entryMenu.isPublished && !deleteDialog.isPublished) {
                TuiPopup(anchor = hover.anchor, modifier = Modifier.background(TuiTheme.colorScheme.surfaceContainerHigh)) {
                    Text(
                        value = hover.path.visiblePathText().wrapToTerminalWidth(columns.value.coerceAtLeast(1))
                            .joinToString("\n"),
                        color = TuiTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (groupMenu != null) {
            val valid = groupMenu.isPlaced
            LaunchedEffect(groupMenu, valid) {
                if (!valid) dismissArchiveMenu(groupMenu)
            }
            if (valid && !entryMenu.isPublished && !deleteDialog.isPublished) key(groupMenu) {
                val checked = groupMenu.checked.value
                TuiPopupMenu(
                    expanded = true,
                    anchor = groupMenu.anchor,
                    onDismissRequest = { dismissArchiveMenu(groupMenu) },
                    backgroundColor = TuiTheme.colorScheme.surfaceContainer,
                ) {
                    TuiPopupMenuItem(
                        key = "show-archived",
                        dismissOnClick = false,
                        onClick = {
                            if (groupMenu.isPlaced && dismissArchiveMenu(groupMenu)) groupMenu.onToggle()
                        },
                    ) { Text(if (checked) "[x] Show archived" else "[ ] Show archived") }
                }
            }
        }
        entryMenu.Render()
        deleteDialog.Render()
    }

    private fun dismissArchiveMenu(expected: GroupArchiveMenu): Boolean {
        if (archiveMenu !== expected) return false
        archiveMenu = null
        return true
    }
}

/** The shared content owns the request; this only moves its drawing to the Mosaic host. */
private class HostOverlaySlot {
    private var content by mutableStateOf<State<@Composable () -> Unit>?>(null)
    val isPublished: Boolean get() = content != null

    @Composable
    fun Publish(draw: @Composable () -> Unit) {
        val latestDraw = rememberUpdatedState(draw)
        DisposableEffect(this, latestDraw) {
            content = latestDraw
            onDispose { if (content === latestDraw) content = null }
        }
    }

    @Composable
    fun Render() {
        content?.value?.invoke()
    }
}

private class GroupArchiveMenu(
    val anchor: TuiPopupAnchor,
    val checked: State<Boolean>,
    private val isVisible: () -> Boolean,
    val onToggle: () -> Unit,
) {
    val isPlaced: Boolean get() = anchor.isPlaced && isVisible()
}

private class GroupPathHover(
    val anchor: TuiPopupAnchor,
    val path: String,
    private val isVisible: () -> Boolean,
) {
    val isPlaced: Boolean get() = anchor.isPlaced && isVisible()
}

@Composable
private fun SessionsPanelHeader(width: Int, onRefresh: () -> Unit, onCollapse: () -> Unit) {
    val colors = TuiTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(1).background(colors.surfaceContainerHigh)) {
        EllipsizedText(
            value = "Sessions",
            modifier = Modifier.weight(1f),
            color = colors.onSurface,
            textStyle = TuiTheme.typography.headline,
        )
        if (width >= 6) TuiButton(label = "↻", color = colors.onSurface, onClick = onRefresh)
        if (width >= 3) TuiButton(label = "—", color = colors.onSurface, onClick = onCollapse)
    }
}

private const val SessionGroupIndent = 2

private enum class GroupRowType { Loading, Empty, Header, LoadingEntries, Entry, Limit }

private data class CwdRowKey(val type: GroupRowType, val cwd: String?)

private fun SessionGroupRow.mosaicType(): GroupRowType = when (this) {
    SessionGroupRow.Loading -> GroupRowType.Loading
    SessionGroupRow.Empty -> GroupRowType.Empty
    is SessionGroupRow.Header -> GroupRowType.Header
    is SessionGroupRow.LoadingEntries -> GroupRowType.LoadingEntries
    is SessionGroupRow.Entry -> GroupRowType.Entry
    is SessionGroupRow.Limit -> GroupRowType.Limit
}

private fun SessionGroupRow.mosaicKey(): Any = when (this) {
    SessionGroupRow.Loading, SessionGroupRow.Empty -> mosaicType()
    is SessionGroupRow.Header -> CwdRowKey(GroupRowType.Header, cwd)
    is SessionGroupRow.LoadingEntries -> CwdRowKey(GroupRowType.LoadingEntries, cwd)
    is SessionGroupRow.Entry -> entry.rootAgentId
    is SessionGroupRow.Limit -> CwdRowKey(GroupRowType.Limit, cwd)
}

private fun LazyListState.contains(row: SessionGroupRow): Boolean {
    val key = row.mosaicKey()
    return layoutInfo.visibleItemsInfo.any { it.key == key }
}

private fun String.visiblePathText(): String = replace('\r', '␍').replace('\n', '␊').replace('\t', '␉')
