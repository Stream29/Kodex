package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel
import io.github.stream29.kodex.cli.sessioncatalog.SessionCatalogMenuOrigin
import io.github.stream29.kodex.cli.sessioncatalog.SessionGroupRow
import io.github.stream29.kodex.cli.sessioncatalog.SessionGroupsRenderer

@Composable
internal fun rememberComposeSessionGroupsRenderer(): SessionGroupsRenderer {
    val scope = rememberCoroutineScope()
    return remember(scope) { ComposeSessionGroupsRenderer(RowMenuFocusRestoration(scope)) }
}

private class ComposeSessionGroupsRenderer(private val restoration: RowMenuFocusRestoration) : SessionGroupsRenderer {
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
        val fallback = remember { FocusRequester() }
        val colors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().background(colors.surface)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Sessions", Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
                    color = colors.onSurface, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                ToolWindowIconButton("Refresh sessions", ToolWindowIcon.Refresh, onRefresh, Modifier.focusRequester(fallback))
                ToolWindowIconButton("Collapse Sessions", ToolWindowIcon.Collapse, onCollapse)
            }
            HorizontalDivider(color = colors.outlineVariant)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(rows, key = { it.lazyKey() }, contentType = { it::class }) { row ->
                    when (row) {
                        SessionGroupRow.Loading -> CatalogListNotice("Loading sessions…")
                        SessionGroupRow.Empty -> CatalogListNotice("No persisted sessions")
                        is SessionGroupRow.LoadingEntries -> Box(Modifier.padding(start = 16.dp)) {
                            CatalogListNotice("Loading sessions…")
                        }
                        is SessionGroupRow.Header -> SessionGroupHeader(
                            row, fallback, restoration, onToggleGroup, onNewInGroup, onToggleArchived,
                        )
                        is SessionGroupRow.Entry -> Box(Modifier.fillMaxWidth().padding(start = 16.dp)) {
                            CatalogRow(row.entry, fallback, { onOpen(row) }, selected = row.selected) {
                                onContextMenu(row, it)
                            }
                        }
                        is SessionGroupRow.Limit -> CompactTextButton(
                            { onToggleLimit(row.cwd) }, Modifier.fillMaxWidth().padding(start = 16.dp),
                        ) {
                            Text(row.hiddenCount?.let { "Show more ($it)" } ?: "Show less", Modifier.weight(1f))
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
    ) = CatalogContextMenu(
        row.entry, origin, restoration, onDismiss, onToggleArchived, onDelete,
        onRename = onRename, mayRestore = LocalSidebarFocusRestoreAllowed.current,
    )

    @Composable
    override fun Delete(viewModel: SessionDeleteViewModel, onDismiss: () -> Unit) = CatalogDelete(viewModel, onDismiss)
}

@Composable
private fun CatalogListNotice(text: String) {
    Text(
        text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SessionGroupHeader(
    header: SessionGroupRow.Header,
    fallback: FocusRequester,
    restoration: RowMenuFocusRestoration,
    onToggleGroup: (String?) -> Unit,
    onNewInGroup: (String?) -> Unit,
    onToggleArchived: (String?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fullPath = header.cwd ?: header.title
    val optionsFocus = remember { FocusRequester() }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    var opening by remember { mutableStateOf<Any?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            bounds = null
            opening = null
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            ToolWindowTooltip(fullPath) {
                CompactTextButton(
                    { onToggleGroup(header.cwd) },
                    Modifier.fillMaxWidth().semantics {
                        contentDescription = fullPath
                        heading()
                        stateDescription = when {
                            header.expanded && header.hasRunning -> "Expanded; Running"
                            header.hasRunning -> "Collapsed; Running"
                            header.expanded -> "Expanded"
                            else -> "Collapsed"
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                ) {
                    Icon(
                        (if (header.expanded) ToolWindowIcon.ChevronDown else ToolWindowIcon.ChevronRight).vector,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                    )
                    if (header.hasRunning) {
                        RunningSpinner()
                        Spacer(Modifier.width(4.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            header.title, style = MaterialTheme.typography.labelLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        header.subtitle?.let {
                            Text(
                                it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        ToolWindowIconButton("New session in $fullPath", ToolWindowIcon.New, { onNewInGroup(header.cwd) })
        ToolWindowIconButton(
            "Options for $fullPath", ToolWindowIcon.Options,
            onClick = {
                try { optionsFocus.requestFocus() } catch (_: IllegalStateException) { }
                opening = Any()
            },
            modifier = Modifier.focusRequester(optionsFocus).onGloballyPositioned { bounds = it.boundsInWindow() },
        )
    }
    opening?.let { request ->
        fun isPlaced() = bounds?.let { it.width > 0 && it.height > 0 } == true
        fun dismiss() { if (opening === request) opening = null }
        val placed = isPlaced()
        restoration.Track(request, fallback, LocalSidebarFocusRestoreAllowed.current, ::isPlaced)
        LaunchedEffect(request, placed) { if (!placed) dismiss() }
        if (placed) {
            CatalogMenuPopup(request, checkNotNull(bounds), point = null, onDismiss = ::dismiss) { first ->
                CompactTextButton(
                    onClick = {
                        if (opening === request && isPlaced()) {
                            dismiss()
                            onToggleArchived(header.cwd)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(first).semantics {
                        role = Role.Checkbox
                        toggleableState = if (header.showArchived) ToggleableState.On else ToggleableState.Off
                    },
                ) {
                    Text("Show archived", Modifier.weight(1f))
                    if (header.showArchived) Icon(ToolWindowIcon.Check.vector, contentDescription = null)
                }
            }
        }
    }
}

private fun SessionGroupRow.lazyKey(): String {
    fun groupKey(cwd: String?) = if (cwd == null) "unset" else "path:$cwd"
    return when (this) {
        SessionGroupRow.Loading -> "loading"
        SessionGroupRow.Empty -> "empty"
        is SessionGroupRow.Header -> "header:${groupKey(cwd)}"
        is SessionGroupRow.LoadingEntries -> "loadingEntries:${groupKey(cwd)}"
        is SessionGroupRow.Limit -> "limit:${groupKey(cwd)}"
        is SessionGroupRow.Entry -> "entry:${entry.sessionIndex}:${entry.rootAgentId}:${groupKey(cwd)}"
    }
}
