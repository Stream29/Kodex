package io.github.stream29.kodex.app.pathpicker.contract

import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path

/**
 * External capabilities required by a directory-picker ViewModel.
 *
 * The component owns request sequencing, filtering, confirmation, and state
 * publication. The dependency owns path resolution and directory observation;
 * a concrete implementation must not be required by the component spec.
 */
public interface DirectoryPickerDependencies {
    public val browser: DirectoryPickerBrowser
}

/**
 * Platform-independent directory capability consumed by the picker state
 * machine.
 *
 * Implementations must convert filesystem failures into result values and
 * propagate cancellation rather than treating it as a filesystem failure.
 * Resolve relative paths and a leading `~` using the backend's current
 * directory and home, returning the resolved directory on success. The
 * dependency never owns picker state and must support overlapping requests.
 */
public interface DirectoryPickerBrowser {
    /**
     * Validates and resolves one requested directory without listing children.
     * A resolved target that is absent or not a directory returns
     * [DirectoryPickerFailure.NotDirectory]; resolution errors themselves
     * return [DirectoryPickerFailure.FileSystem].
     * A `~` target without an available home returns
     * [DirectoryPickerFailure.HomeDirectoryUnavailable].
     *
     * @throws CancellationException when the owner scope is cancelled.
     */
    public suspend fun validate(directory: Path): DirectoryPickerValidationResult

    /**
     * Validates, resolves, and lists the direct child directories.
     * Exclude files and dangling links; order children by case-insensitive
     * name, then full path to break ties. A listing failure returns a failure
     * result, not a successful partial list.
     *
     * @throws CancellationException when the owner scope is cancelled.
     */
    public suspend fun load(directory: Path): DirectoryPickerLoadResult
}

/** A successfully resolved directory and its direct child directories. */
public data class DirectoryPickerListing(
    public val directory: Path,
    public val children: List<Path>,
)

/** Result of loading a directory and its direct child directories. */
public sealed interface DirectoryPickerLoadResult {
    public data class Success(
        public val listing: DirectoryPickerListing,
    ) : DirectoryPickerLoadResult

    public data class Failure(
        public val failure: DirectoryPickerFailure,
    ) : DirectoryPickerLoadResult
}

/** Result of validating a directory for confirmation. */
public sealed interface DirectoryPickerValidationResult {
    public data class Success(
        public val directory: Path,
    ) : DirectoryPickerValidationResult

    public data class Failure(
        public val failure: DirectoryPickerFailure,
    ) : DirectoryPickerValidationResult
}
