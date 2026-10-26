package io.github.stream29.kodex.cli.sessioncatalog

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogState
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
public actual fun SessionCatalogContent(
    viewModel: SessionCatalogViewModel,
    renderer: SessionCatalogRenderer,
    onFailure: (Throwable) -> Unit,
) {
    key(viewModel) {
        val scope = rememberCoroutineScope()
        val failureHandler by rememberUpdatedState(onFailure)
        val state = viewModel.state.collectAsState().value
        val deleteTarget = viewModel.deleteTarget.collectAsState().value
        var menu by remember { mutableStateOf<CatalogMenuRequest?>(null) }
        LaunchedEffect(viewModel) { viewModel.refresh() }
        fun contains(entry: SessionCatalogEntry): Boolean {
            val current = viewModel.state.value
            return current is SessionCatalogState.Loaded && current.sessions.any { it == entry }
        }
        fun launchReported(command: suspend () -> Unit) {
            scope.launch {
                try { command() }
                catch (cancellation: CancellationException) { throw cancellation }
                catch (failure: Throwable) { failureHandler(failure) }
            }
        }
        val rows = when (state) {
            SessionCatalogState.Unloaded, is SessionCatalogState.Loading -> SessionCatalogRows.Loading
            is SessionCatalogState.Loaded -> if (state.sessions.isEmpty()) SessionCatalogRows.Empty
                else SessionCatalogRows.Entries(state.sessions)
        }
        renderer.Dialog(
            state.showArchived, rows,
            onShowArchivedChange = { archived -> menu = null; scope.launch { viewModel.setShowArchived(archived) } },
            onDismiss = viewModel::dismiss,
            onOpen = { entry ->
                if (contains(entry)) { menu = null; launchReported { viewModel.requestOpen(entry) } }
            },
            onContextMenu = { entry, origin -> if (contains(entry)) menu = CatalogMenuRequest(entry, origin) },
        )
        menu?.let { request ->
            val valid = state is SessionCatalogState.Loaded &&
                state.sessions.any { it == request.entry } && request.origin.isPlaced
            LaunchedEffect(request, valid) { if (!valid && menu === request) menu = null }
            if (valid) {
                fun consume(command: () -> Unit) {
                    if (menu !== request || !request.origin.isPlaced || !contains(request.entry)) return
                    menu = null
                    command()
                }
                renderer.ContextMenu(
                    request.entry, request.origin,
                    onDismiss = { if (menu === request) menu = null },
                    onFork = { consume { launchReported { viewModel.fork(request.entry.sessionIndex) } } },
                    onArchive = { consume { scope.launch { viewModel.archive(request.entry.sessionIndex) } } },
                    onUnarchive = { consume { scope.launch { viewModel.unarchive(request.entry.sessionIndex) } } },
                    onDelete = { consume { viewModel.requestDelete(request.entry) } },
                )
            }
        }
        deleteTarget?.let { handle ->
            DisposableEffect(viewModel, handle) { onDispose { viewModel.dismissDelete(handle) } }
            renderer.Delete(handle.viewModel) { viewModel.dismissDelete(handle) }
        }
    }
}

private class CatalogMenuRequest(val entry: SessionCatalogEntry, val origin: SessionCatalogMenuOrigin)
