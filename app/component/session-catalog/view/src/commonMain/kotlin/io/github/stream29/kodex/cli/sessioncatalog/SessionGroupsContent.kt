package io.github.stream29.kodex.cli.sessioncatalog

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupFocus
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupTarget
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
public actual fun SessionGroupsContent(
    viewModel: SessionGroupsViewModel,
    focus: SessionGroupFocus?,
    renderer: SessionGroupsRenderer,
    onNewInGroup: suspend (String?) -> Unit,
    onCollapse: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    key(viewModel) {
        val scope = rememberCoroutineScope()
        val focused = rememberUpdatedState(focus)
        val failure = rememberUpdatedState(onFailure)
        val requestNew = rememberUpdatedState(onNewInGroup)
        val state by viewModel.state.collectAsState()
        val delete by viewModel.deleteTarget.collectAsState()
        var menu by remember { mutableStateOf<SessionGroupMenu?>(null) }
        LaunchedEffect(viewModel) {
            viewModel.observeGroups(snapshotFlow { focused.value }) { failure.value(it) }
        }
        DisposableEffect(viewModel) {
            onDispose { viewModel.deleteTarget.value?.let(viewModel::dismissDelete) }
        }
        fun runCommand(command: suspend () -> Unit) {
            scope.launch {
                try {
                    command()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failure.value(error)
                }
            }
        }
        fun contains(row: SessionGroupRow.Entry): Boolean = viewModel.state.value.let {
            it.isExpanded(row.cwd, focused.value) && row.entry in it.visibleEntries(row.cwd)
        }
        renderer.Panel(
            rows = remember(state, focus) { state.rows(focus) },
            onRefresh = viewModel::refresh,
            onCollapse = onCollapse,
            onToggleGroup = { viewModel.toggleGroup(it, focused.value) },
            onNewInGroup = { cwd ->
                runCommand {
                    if (viewModel.state.value.groups.orEmpty().any { it.cwd == cwd }) requestNew.value(cwd)
                }
            },
            onToggleArchived = viewModel::toggleArchived,
            onToggleLimit = viewModel::toggleLimit,
            onOpen = { row ->
                runCommand {
                    if (contains(row)) {
                        menu = null
                        viewModel.requestOpen(row.target())
                    }
                }
            },
            onContextMenu = { row, origin ->
                if (origin.isPlaced && contains(row)) menu = SessionGroupMenu(row, origin)
            },
        )
        menu?.let { captured ->
            val valid = state.isExpanded(captured.row.cwd, focus) &&
                captured.row.entry in state.visibleEntries(captured.row.cwd) && captured.origin.isPlaced
            fun dismiss() { if (menu === captured) menu = null }
            LaunchedEffect(captured, valid) { if (!valid) dismiss() }
            if (valid) {
                fun consume(command: (SessionGroupTarget) -> Unit) {
                    if (menu !== captured || !captured.origin.isPlaced || !contains(captured.row)) return
                    dismiss()
                    command(captured.row.target())
                }
                renderer.ContextMenu(
                    captured.row, captured.origin,
                    onDismiss = ::dismiss,
                    onRename = { consume { runCommand { viewModel.requestRename(it) } } },
                    onToggleArchived = { consume { runCommand { viewModel.requestArchive(it) } } },
                    onDelete = { consume { viewModel.requestDelete(it) } },
                )
            }
        }
        delete?.let { handle -> renderer.Delete(handle.viewModel) { viewModel.dismissDelete(handle) } }
    }
}

private class SessionGroupMenu(val row: SessionGroupRow.Entry, val origin: SessionCatalogMenuOrigin)

private fun SessionGroupRow.Entry.target() = SessionGroupTarget(cwd, entry)
