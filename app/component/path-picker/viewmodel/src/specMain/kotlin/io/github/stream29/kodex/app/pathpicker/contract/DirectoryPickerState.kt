package io.github.stream29.kodex.app.pathpicker.contract

import kotlinx.io.files.Path

/**
 * Atomic state for one independently owned directory-picker workflow.
 *
 * The renderer must always show [currentDirectory] and [filterQuery]. It must
 * render [loadState] as loading, ready content, or a failure affordance;
 * [visibleChildren] is the only child list that should be presented.
 */
public data class DirectoryPickerState(
    /** Current case-insensitive child-directory filter. */
    public val filterQuery: String = "",
    /** Current request lifecycle and its observable result. */
    public val loadState: DirectoryPickerLoadState,
)

/**
 * The directory request currently owned by a picker workflow.
 *
 * [Loading] renders a progress state and does not expose parent navigation.
 * [Ready] renders [Ready.children] after filtering and exposes confirmation.
 * [Failed] renders [Failed.failure] while retaining the requested path and
 * allows retry or dismissal. Confirmation is available in every state because
 * it independently validates the target; it does not require a loaded list.
 * An empty ready list must distinguish no child directories from no filter
 * matches. Navigation to a child and to an available parent is delegated to
 * the ViewModel; focus, scroll position, layout and keyboard bindings belong
 * to the renderer.
 */
public sealed interface DirectoryPickerLoadState {
    public val requestId: Long
    public val requestedDirectory: Path

    /**
     * A newly requested target whose children are not yet available.
     * @throws IllegalArgumentException if the request id is not positive.
     */
    public data class Loading(
        override val requestId: Long,
        override val requestedDirectory: Path,
    ) : DirectoryPickerLoadState {
        init {
            require(requestId > 0) { "A directory-picker request id must be positive." }
        }
    }

    /**
     * A completed, resolved directory and its ordered direct children.
     * @throws IllegalArgumentException if the request id is not positive.
     */
    public data class Ready(
        override val requestId: Long,
        override val requestedDirectory: Path,
        public val directory: Path,
        /** Direct child directories in the stable order chosen by the browser. */
        public val children: List<Path>,
        public val parentDirectory: Path?,
        /** Display names supplied by the directory capability, keyed by exact child. */
        public val childNames: Map<Path, String>,
    ) : DirectoryPickerLoadState {
        init {
            require(requestId > 0) { "A directory-picker request id must be positive." }
            require(childNames.keys == children.toSet()) { "Every listed child must have exactly one display name." }
        }
    }

    /**
     * A failed request whose target is retained for retry or confirmation.
     * @throws IllegalArgumentException if the request id is not positive.
     */
    public data class Failed(
        override val requestId: Long,
        override val requestedDirectory: Path,
        /** Typed failure that the renderer must make recoverable or dismissible. */
        public val failure: DirectoryPickerFailure,
        public val parentDirectory: Path? = null,
    ) : DirectoryPickerLoadState {
        init {
            require(requestId > 0) { "A directory-picker request id must be positive." }
        }
    }
}

/** Typed reason why a requested directory could not be validated or listed. */
public sealed interface DirectoryPickerFailure {
    /** A path beginning with `~` was requested, but no user home was available. */
    public data object HomeDirectoryUnavailable : DirectoryPickerFailure

    /** The resolved path does not identify an existing directory. */
    public data class NotDirectory(
        public val directory: Path,
    ) : DirectoryPickerFailure

    /**
     * A filesystem operation failed for a reason without a portable typed representation.
     * @throws IllegalArgumentException if the detail is blank.
     */
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
        val ready = loadState as? DirectoryPickerLoadState.Ready ?: return emptyList()
        val children = ready.children
        if (filterQuery.isEmpty()) return children
        return children.filter { child ->
            ready.childNames.getValue(child).ifEmpty { child.toString() }.contains(filterQuery, ignoreCase = true)
        }
    }

/** Whether the current non-loading target has a navigable parent. */
public val DirectoryPickerState.canNavigateUp: Boolean
    get() = parentDirectory != null

/** Parent supplied by the directory capability; loading never exposes navigation. */
public val DirectoryPickerState.parentDirectory: Path?
    get() = when (val load = loadState) {
        is DirectoryPickerLoadState.Loading -> null
        is DirectoryPickerLoadState.Ready -> load.parentDirectory
        is DirectoryPickerLoadState.Failed -> load.parentDirectory
    }
