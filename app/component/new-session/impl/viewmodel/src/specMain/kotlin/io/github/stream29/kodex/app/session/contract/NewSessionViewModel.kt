package io.github.stream29.kodex.app.session.contract

import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.openai.KodexAgentSettings

/**
 * Frontend contract for one non-persisted New Session surface.
 *
 * This ViewModel captures and consumes its own settings and composer when
 * [materialize] is called. Its inherited settings contract is backed by one
 * process-local [kotlinx.coroutines.flow.MutableStateFlow].
 *
 * It is the same child held by the Application registry before persistence; there is no second
 * component wrapper, creation receipt model or page-state copy. Tab selection only mounts its
 * renderer. The registry closes this child when replacing/removing it, not on renderer disposal.
 */
public interface NewSessionViewModel : SessionViewModel {
    public val composer: ComposerViewModel
    /** Stable configuration child of this draft; neither rendering nor tab selection owns it. */
    public val runtimeConfiguration: RuntimeConfigurationViewModel

    /**
     * Clears the explicit thread name and restores the derived default name.
     * @throws IllegalStateException if the draft is closed or already allocated.
     * @throws kotlinx.coroutines.CancellationException if the caller stops waiting for admission.
     */
    public suspend fun clearExplicitThreadName(): Unit

    /**
     * Materializes the latest settings and composer as a persisted Session.
     *
     * The command is serialized with this ViewModel's edits. A known successful
     * creation consumes its settings even if a later step fails; it is not rolled
     * back or recreated. Failure escapes to the caller. Success returns the stable
     * persisted child that must replace this exact surface.
     *
     * @throws IllegalStateException if the owner was closed before/while allocation completed.
     * @throws kotlinx.coroutines.CancellationException if the caller is cancelled; cancellation
     * does not promise rollback of an already allocated Session or an already appended message.
     * @throws Exception if allocation, explicit rename, initial append or persisted-child opening
     * fails. The known allocation identity is retained rather than manufacturing another Session.
     */
    public suspend fun materialize(): PersistedSessionViewModel
}

/**
 * Explicit per-instance inputs for an independent virtual Session surface.
 * @throws IllegalArgumentException if [defaultName] is blank.
 */
public data class NewSessionViewModelArguments(
    public val defaultName: String,
    public val initialSettings: KodexAgentSettings,
) {
    init {
        require(defaultName.isNotBlank()) {
            "A New Session default display name must not be blank."
        }
    }
}

/**
 * Creates one independently owned virtual Session surface with stable Composer/configuration
 * children and borrowed model observation. Construction does not allocate backend storage.
 */
public fun interface NewSessionViewModelFactory {
    public fun create(arguments: NewSessionViewModelArguments): NewSessionViewModel
}
