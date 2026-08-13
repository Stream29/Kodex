package io.github.stream29.kodex.app.session.contract

import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.openai.KodexAgentSettings

/**
 * Frontend contract for one non-persisted New Session surface.
 *
 * This ViewModel captures and consumes its own settings and composer when
 * [materialize] is called. Its inherited settings contract is backed by one
 * process-local [kotlinx.coroutines.flow.MutableStateFlow].
 */
public interface NewSessionViewModel : SessionViewModel {
    public val composer: ComposerViewModel

    /** Clears the explicit thread name and restores the derived default name. */
    public suspend fun clearExplicitThreadName(): Unit

    /**
     * Materializes the latest settings and composer as a persisted Session.
     *
     * The command is serialized with this ViewModel's edits. Failure leaves the
     * draft editable and escapes to the caller; success consumes the draft and
     * returns the stable persisted child that must replace this exact surface.
     */
    public suspend fun materialize(): PersistedSessionViewModel
}

/** Explicit per-instance inputs for an independent virtual Session surface. */
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

/** Creates one independently owned virtual Session surface. */
public fun interface NewSessionViewModelFactory {
    public fun create(arguments: NewSessionViewModelArguments): NewSessionViewModel
}
