package io.github.stream29.kodex.app.pathpicker.contract

import kotlinx.io.files.Path

/** Atomic state for one independently owned directory-picker workflow. */
public data class DirectoryPickerState(
    public val filterQuery: String = "",
    public val loadState: DirectoryPickerLoadState,
)

/** The directory request currently owned by a picker workflow. */
public sealed interface DirectoryPickerLoadState {
    public val requestId: Long
    public val requestedDirectory: Path

    public data class Loading(
        override val requestId: Long,
        override val requestedDirectory: Path,
    ) : DirectoryPickerLoadState {
        init {
            require(requestId > 0) { "A directory-picker request id must be positive." }
        }
    }

    public data class Ready(
        override val requestId: Long,
        override val requestedDirectory: Path,
        public val directory: Path,
        public val children: List<Path>,
    ) : DirectoryPickerLoadState {
        init {
            require(requestId > 0) { "A directory-picker request id must be positive." }
        }
    }

    public data class Failed(
        override val requestId: Long,
        override val requestedDirectory: Path,
        public val failure: DirectoryPickerFailure,
    ) : DirectoryPickerLoadState {
        init {
            require(requestId > 0) { "A directory-picker request id must be positive." }
        }
    }
}

/** Typed reason why a requested directory could not be listed. */
public sealed interface DirectoryPickerFailure {
    /** A path beginning with `~` was requested, but no user home was available. */
    public data object HomeDirectoryUnavailable : DirectoryPickerFailure

    /** The resolved path does not identify an existing directory. */
    public data class NotDirectory(
        public val directory: Path,
    ) : DirectoryPickerFailure

    /** A filesystem operation failed for a reason without a portable typed representation. */
    public data class FileSystem(
        public val detail: String,
    ) : DirectoryPickerFailure {
        init {
            require(detail.isNotBlank()) { "A filesystem failure detail must not be blank." }
        }
    }
}

/** Directory displayed as the current navigation target. */
public val DirectoryPickerState.currentDirectory: Path
    get() = when (val loadState = loadState) {
        is DirectoryPickerLoadState.Loading -> loadState.requestedDirectory
        is DirectoryPickerLoadState.Ready -> loadState.directory
        is DirectoryPickerLoadState.Failed -> loadState.requestedDirectory
    }

/** Direct child directories matching the current case-insensitive filter. */
public val DirectoryPickerState.visibleChildren: List<Path>
    get() {
        val children = (loadState as? DirectoryPickerLoadState.Ready)?.children.orEmpty()
        if (filterQuery.isEmpty()) return children
        return children.filter { child ->
            child.directoryPickerName().contains(filterQuery, ignoreCase = true)
        }
    }

/** Whether the current non-loading target has a navigable parent. */
public val DirectoryPickerState.canNavigateUp: Boolean
    get() = loadState !is DirectoryPickerLoadState.Loading && currentDirectory.parent != null

/** Whether confirmation can emit a resolved directory. */
public val DirectoryPickerState.canConfirm: Boolean
    get() = loadState is DirectoryPickerLoadState.Ready

private fun Path.directoryPickerName(): String = name.ifEmpty { this.toString() }
