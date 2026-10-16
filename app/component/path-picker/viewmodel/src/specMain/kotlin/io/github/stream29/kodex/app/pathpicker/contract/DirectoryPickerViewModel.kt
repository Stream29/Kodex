package io.github.stream29.kodex.app.pathpicker.contract

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/**
 * UI-framework-free state machine for one short-lived directory-picker
 * component.
 *
 * An implementation receives [DirectoryPickerDependencies] and an owner
 * lifecycle from its composition root. It must serialize state transitions,
 * ignore stale directory-listing results, and publish only component-owned
 * state. The renderer must consume [state] and [effects] rather than
 * inspecting the dependency or implementation.
 *
 * Closing the ViewModel cancels in-flight work, closes the effect stream, and
 * makes subsequent interactions no-ops.
 */
public interface DirectoryPickerViewModel : AutoCloseable {
    /** State that the renderer must treat as an atomic snapshot. */
    public val state: StateFlow<DirectoryPickerState>

    /**
     * One-shot component outputs. A [DirectoryPickerEffect.DirectorySelected]
     * effect means the caller should accept the resolved directory and dismiss
     * the component. Use one collector per component; effects are delivered
     * to that consumer rather than broadcast as persistent state.
     */
    public val effects: Flow<DirectoryPickerEffect>

    /**
     * Starts loading [directory], clears the filter, and assigns a newer
     * request id. A result from an older request must not replace the current
     * state.
     *
     * @throws IllegalStateException only when the implementation cannot
     * represent another request id.
     */
    public fun navigateTo(directory: Path): Unit

    /**
     * Requests the parent of the current non-loading directory.
     *
     * If the current state is loading or has no parent, this is a no-op.
     * Otherwise it clears the filter and starts a newer load request.
     *
     * @throws IllegalStateException if another request id cannot be represented.
     */
    public fun navigateUp(): Unit

    /**
     * Replaces the filter query without starting a filesystem request.
     *
     * Rendering must apply the filter case-insensitively to the currently
     * loaded direct child directories.
     */
    public fun updateFilter(query: String): Unit

    /** Clears the current filter without changing the requested directory. */
    public fun clearFilter(): Unit

    /**
     * Retries only the latest failed directory request, retaining the filter.
     *
     * @throws IllegalStateException if another request id cannot be represented.
     */
    public fun retry(): Unit

    /**
     * Validates the current requested directory and emits its resolved
     * directory without waiting for children to load.
     *
     * A failed validation is represented by [DirectoryPickerLoadState.Failed].
     * Repeated confirmation for the same request must not emit duplicate
     * effects while validation is in flight.
     *
     * Validation runs in the owner scope; cancellation stops the work rather
     * than throwing synchronously from this command. Selection is suppressed
     * if navigation has changed the request before validation completes.
     */
    public fun confirm(): Unit

    /**
     * Stops the component and releases its owner-scoped resources.
     *
     * The renderer must stop collecting the component after this call.
     */
    override fun close(): Unit
}

/**
 * Constructs one independently disposable component with explicit dependencies.
 *
 * The implementation must publish an initial [DirectoryPickerLoadState.Loading]
 * for the initial directory and begin loading through [DirectoryPickerDependencies.browser].
 * Child work belongs to the supplied owner scope; closing the child must not cancel its owner.
 */
public fun interface DirectoryPickerViewModelFactory {
    public fun create(
        initialDirectory: Path,
        dependencies: DirectoryPickerDependencies,
        ownerScope: CoroutineScope,
    ): DirectoryPickerViewModel
}
