package io.github.stream29.kodex.cli.pathpicker

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.pathpicker.contract.*
import kotlinx.io.files.Path

@Composable
public actual fun DirectoryPickerContent(
    viewModel: DirectoryPickerViewModel, renderer: DirectoryPickerRenderer,
    onDismissRequest: () -> Unit, onDirectorySelected: (Path) -> Unit, closeOnDispose: Boolean,
) {
    val state = viewModel.state.collectAsState().value
    val currentSelected by rememberUpdatedState(onDirectorySelected)
    val currentViewModel by rememberUpdatedState(viewModel)
    DisposableEffect(viewModel) { onDispose { if (closeOnDispose) viewModel.close() } }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DirectoryPickerEffect.DirectorySelected -> if (currentViewModel === viewModel) currentSelected(effect.directory)
            }
        }
    }
    key(viewModel) {
        val children = state.visibleChildren
        val query = state.filterQuery
        val rows = buildList {
            if (state.canNavigateUp) add(DirectoryPickerRow("parent", "..", DirectoryPickerRowRole.Parent, viewModel::navigateUp))
            when (val load = state.loadState) {
                is DirectoryPickerLoadState.Loading ->
                    add(DirectoryPickerRow("loading", "Loading directories…", DirectoryPickerRowRole.Text))
                is DirectoryPickerLoadState.Failed -> {
                    add(DirectoryPickerRow("failure-heading", "Unable to read directory", DirectoryPickerRowRole.Error))
                    add(DirectoryPickerRow("failure", load.failure.message(), DirectoryPickerRowRole.Error))
                }
                is DirectoryPickerLoadState.Ready -> if (children.isEmpty()) {
                    add(DirectoryPickerRow(
                        "empty", if (query.isEmpty()) "No child directories" else "No matching directories", DirectoryPickerRowRole.Text,
                    ))
                } else children.forEach { child ->
                    add(DirectoryPickerRow(
                        "directory:$child", "${load.childNames.getValue(child).ifEmpty { child.toString() }}/", DirectoryPickerRowRole.Directory,
                        onActivate = { viewModel.navigateTo(child) },
                        focusForFilter = query.isNotEmpty() && child == children.first(),
                    ))
                }
            }
        }
        renderer.Dialog(
            DirectoryPickerPresentation(
                "Select directory", state.currentDirectory.toString(), (state.loadState as? DirectoryPickerLoadState.Ready)?.directory,
                query, rows, "Select", "Cancel",
            ),
            onDismiss = onDismissRequest,
            onEscape = { if (viewModel.state.value.filterQuery.isEmpty()) onDismissRequest() else viewModel.clearFilter() },
            onConfirm = viewModel::confirm, onNavigateUp = viewModel::navigateUp,
            onFilter = viewModel::updateFilter,
            onFilterLetter = { viewModel.updateFilter(viewModel.state.value.filterQuery + it) },
            onBackspace = {
                val current = viewModel.state.value.filterQuery
                if (current.isEmpty()) false else { viewModel.updateFilter(current.dropLast(1)); true }
            },
        )
    }
}

private fun DirectoryPickerFailure.message(): String = when (this) {
    DirectoryPickerFailure.HomeDirectoryUnavailable -> "Cannot resolve ~ because the user home directory was not found."
    is DirectoryPickerFailure.NotDirectory -> "Not a directory: $directory"
    is DirectoryPickerFailure.FileSystem -> detail
}
