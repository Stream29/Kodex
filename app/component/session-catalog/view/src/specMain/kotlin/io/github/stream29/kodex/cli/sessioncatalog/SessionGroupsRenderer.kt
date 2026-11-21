package io.github.stream29.kodex.cli.sessioncatalog

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel

/** Flat rows let both renderers virtualize the whole list, including a single large expanded group. */
public sealed interface SessionGroupRow {
    public data object Loading : SessionGroupRow
    public data object Empty : SessionGroupRow

    public data class Header(
        public val cwd: String?,
        public val title: String,
        public val subtitle: String?,
        public val expanded: Boolean,
        public val showArchived: Boolean,
        public val hasRunning: Boolean,
    ) : SessionGroupRow

    public data class LoadingEntries(public val cwd: String?) : SessionGroupRow

    public data class Entry(
        public val cwd: String?,
        public val entry: SessionCatalogEntry,
        public val selected: Boolean,
    ) : SessionGroupRow

    /** Null [hiddenCount] denotes Show less, otherwise this is the exact omitted count. */
    public data class Limit(public val cwd: String?, public val hiddenCount: Int?) : SessionGroupRow
}

public interface SessionGroupsRenderer {
    @Composable
    public fun Panel(
        rows: List<SessionGroupRow>,
        onRefresh: () -> Unit,
        onCollapse: () -> Unit,
        onToggleGroup: (String?) -> Unit,
        onNewInGroup: (String?) -> Unit,
        onToggleArchived: (String?) -> Unit,
        onToggleLimit: (String?) -> Unit,
        onOpen: (SessionGroupRow.Entry) -> Unit,
        onContextMenu: (SessionGroupRow.Entry, SessionCatalogMenuOrigin) -> Unit,
    )

    @Composable
    public fun ContextMenu(
        row: SessionGroupRow.Entry,
        origin: SessionCatalogMenuOrigin,
        onDismiss: () -> Unit,
        onRename: () -> Unit,
        onToggleArchived: () -> Unit,
        onDelete: () -> Unit,
    )

    @Composable
    public fun Delete(viewModel: SessionDeleteViewModel, onDismiss: () -> Unit)
}
