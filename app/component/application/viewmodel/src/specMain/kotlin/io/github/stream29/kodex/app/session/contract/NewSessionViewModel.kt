package io.github.stream29.kodex.app.session.contract

import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import kotlinx.io.files.Path

/**
 * Application-owned, reusable New Session surface, independent of persisted tabs.
 *
 * Only Composer and working directory are local drafts. The four model configuration fields
 * read and edit the shared backend New Session defaults; settings is their read-only projection
 * with the local directory. Selecting another surface or unmounting the renderer closes nothing.
 * The Application closes this child at shutdown, without closing borrowed global/session services.
 */
public interface NewSessionViewModel : AgentSettingsViewModel, AutoCloseable {
    public val composer: ComposerViewModel
    /** Stable configuration child, owned by this singleton rather than its renderer. */
    public val runtimeConfiguration: RuntimeConfigurationViewModel

    /**
     * Admits the exact Composer revision, creates/opens a Session, appends the unmodified text
     * and admits its resume. Empty (length zero), stale and in-flight duplicate calls return null
     * without queueing another creation. Nonzero whitespace is valid input.
     *
     * Success clears only the consumed revision and resets only an unedited working directory.
     * This child remains usable for another creation. Later edits are never consumed by an old
     * callback. Failure propagates, without replay, compensation, deletion or forced navigation.
     *
     * The host must call this from its surviving frontend command owner, not a renderer Job.
     * Cancelling a renderer's wait must not cancel that accepted command or lose its error outlet.
     * The host adds/selects the returned persisted handle; this operation does not navigate.
     *
     * @throws IllegalStateException if this child is closed, the created binding expires before
     * resume admission, or the consumed Composer cannot advance its revision.
     * @throws kotlinx.coroutines.CancellationException if the command owner is cancelled.
     * @throws Exception if creation, opening, initial append or synchronous resume admission fails.
     * An exception does not establish whether an entity or message was already persisted.
     */
    public suspend fun submit(expectedRevision: Long): PersistedSessionViewModel?
}

/** The CLI startup directory, restored after each successfully consumed directory draft. */
public data class NewSessionViewModelArguments(
    public val initialWorkingDirectory: Path,
)

/** Creates stable Composer/configuration children without allocating backend storage. */
public fun interface NewSessionViewModelFactory {
    public fun create(arguments: NewSessionViewModelArguments): NewSessionViewModel
}
