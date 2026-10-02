package io.github.stream29.kodex.app.workingdirectory.contract

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path

/**
 * Selection capability bound to the target captured when the chooser opened.
 * Never resolve the active tab/Agent again when processing a delayed selection.
 *
 * Application adapters await a captured target's cwd update. A suggestion adapter
 * edits only its captured pending call's configuration and may ignore a no-longer
 * pending/submitting call. Settings adapters consume the exact picker handle
 * before handing off to a revision-checked queue; stale/unwritable revisions may
 * produce no write. A normal return therefore does not universally mean durable
 * persistence. Target/handle/revision admission remains the adapter's responsibility.
 */
public fun interface WorkingDirectoryDependencies {
    /**
     * Hands the explicitly selected [directory] to the captured target/command.
     * The component never performs persistence, filesystem validation or RPC here.
     *
     * @throws CancellationException if the caller or bound operation is cancelled.
     * @throws Exception if the bound operation fails synchronously or while awaited.
     */
    public suspend fun select(directory: Path): Unit
}

/**
 * Owns one directory-browser child and the target-specific selection boundary.
 * Construct through [WorkingDirectoryViewModelFactory] with an already-created
 * picker and bound [WorkingDirectoryDependencies]; ownership transfers to this
 * component, not to the target. Do not share the same child between owners.
 *
 * Render [picker]'s atomic state and consume its effects through one browser
 * renderer: loading, ready directories/filter and recoverable failure retain
 * their Path Picker semantics. Navigation/filtering never writes cwd. Only an
 * explicitly validated selection is handed to [select]; Cancel, Escape without
 * a filter, and outside dismissal request closing the exact host handle with no
 * update. Escape with a filter first clears that filter.
 *
 * After normal selection return, request dismissal of the exact host handle,
 * not a newly active popup. Suppress callbacks from a disposed/replaced renderer.
 * Settings may remove and close the child within its selection dependency before
 * queuing a write; Application closes after its awaited command returns.
 *
 * All calls are confined to the owner's interaction dispatcher. Caller jobs are
 * owned by the host/renderer. There is no additional queue, duplicate-submission
 * policy or mirrored copy of browser state in this component.
 */
public interface WorkingDirectoryViewModel : AutoCloseable {
    /** Exact owned browser child; render its state rather than inspecting dependencies. */
    public val picker: DirectoryPickerViewModel

    /** False after close or a normally completed selection; rejects new selection calls. */
    public val isActive: Boolean

    /**
     * Hands [directory] to the bound operation once, then closes this component
     * after a normal return, including ignored/stale/queued outcomes. The path
     * is passed unchanged: browser confirmation validates it, while programmatic
     * callers are responsible for supplying an acceptable path.
     *
     * Failure/cancellation propagates without closing a still-owned component.
     * An adapter which already consumed it does not have to reopen it on failure.
     *
     * @throws IllegalStateException if the component was already closed.
     * @throws CancellationException if the caller or bound operation is cancelled.
     * @throws Exception if the bound operation fails.
     */
    public suspend fun select(directory: Path): Unit

    /**
     * Idempotently closes [picker] and rejects new selections. Never changes cwd,
     * closes the bound target, cancels the parent's scope or rolls back a command
     * already dispatched. Closing the browser cancels its local browsing work.
     */
    override fun close(): Unit
}

/** Transfers ownership of the supplied browser without initiating a cwd update. */
public fun interface WorkingDirectoryViewModelFactory {
    public fun create(
        picker: DirectoryPickerViewModel,
        dependencies: WorkingDirectoryDependencies,
    ): WorkingDirectoryViewModel
}
