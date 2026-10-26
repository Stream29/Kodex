package io.github.stream29.kodex.cli.sessioncatalog

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel

/** Renderer-owned placement, invalidated when its originating row leaves layout. */
public interface SessionCatalogMenuOrigin {
    public val isPlaced: Boolean
}

public sealed interface SessionCatalogRows {
    public data object Loading : SessionCatalogRows
    public data object Empty : SessionCatalogRows
    public data class Entries(public val entries: List<SessionCatalogEntry>) : SessionCatalogRows
}

/** Layout, row anchors, animation, timestamps and modal geometry only. */
public interface SessionCatalogRenderer {
    @Composable
    public fun Dialog(
        showArchived: Boolean,
        rows: SessionCatalogRows,
        onShowArchivedChange: (Boolean) -> Unit,
        onDismiss: () -> Unit,
        onOpen: (SessionCatalogEntry) -> Unit,
        onContextMenu: (SessionCatalogEntry, SessionCatalogMenuOrigin) -> Unit,
    )

    @Composable
    public fun ContextMenu(
        entry: SessionCatalogEntry,
        origin: SessionCatalogMenuOrigin,
        onDismiss: () -> Unit,
        onFork: () -> Unit,
        onArchive: () -> Unit,
        onUnarchive: () -> Unit,
        onDelete: () -> Unit,
    )

    @Composable
    public fun Delete(viewModel: SessionDeleteViewModel, onDismiss: () -> Unit)
}

/** Borrows the catalog owner and admits callbacks only for their exact snapshot/menu. */
@Composable
public expect fun SessionCatalogContent(
    viewModel: SessionCatalogViewModel,
    renderer: SessionCatalogRenderer,
    onFailure: (Throwable) -> Unit,
)
