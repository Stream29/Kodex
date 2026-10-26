package io.github.stream29.kodex.cli.pathpicker

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import kotlinx.io.files.Path

public enum class DirectoryPickerRowRole { Parent, Directory, Text, Error }

/** Ephemeral rendering parameters, never a second directory/selection authority or a storage cache. */
public data class DirectoryPickerRow(
    public val key: String,
    public val label: String,
    public val role: DirectoryPickerRowRole,
    public val onActivate: (() -> Unit)? = null,
    public val focusForFilter: Boolean = false,
)

public data class DirectoryPickerPresentation(
    public val title: String,
    public val directory: String,
    public val listingIdentity: Path?,
    public val filter: String,
    public val rows: List<DirectoryPickerRow>,
    public val selectLabel: String,
    public val cancelLabel: String,
)

/** Owns list geometry, selection/focus, filter input and modal mounting, not the browser capability. */
public interface DirectoryPickerRenderer {
    @Composable
    public fun Dialog(
        presentation: DirectoryPickerPresentation,
        onDismiss: () -> Unit, onEscape: () -> Unit, onConfirm: () -> Unit, onNavigateUp: () -> Unit,
        onFilter: (String) -> Unit, onFilterLetter: (String) -> Unit, onBackspace: () -> Boolean,
    )
}

/** One exact child and effect collector. Keep disposal policy fixed for that child's lifetime. */
@Composable
public expect fun DirectoryPickerContent(
    viewModel: DirectoryPickerViewModel,
    renderer: DirectoryPickerRenderer,
    onDismissRequest: () -> Unit,
    onDirectorySelected: (Path) -> Unit,
    closeOnDispose: Boolean = true,
): Unit
