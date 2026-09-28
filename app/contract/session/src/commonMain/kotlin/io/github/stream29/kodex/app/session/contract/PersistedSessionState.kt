package io.github.stream29.kodex.app.session.contract

/**
 * UI-facing lifetime of one persisted Session handle.
 *
 * A handle can temporarily lose its local child while RPC observations recover.
 */
public sealed interface PersistedSessionLifecycleState {
    public data object Loading : PersistedSessionLifecycleState
    public data object Open : PersistedSessionLifecycleState

    public data class Failed(
        public val detail: String,
    ) : PersistedSessionLifecycleState {
        init {
            require(detail.isNotBlank()) {
                "A persisted Session lifecycle failure detail must not be blank."
            }
        }
    }

    public data object Closed : PersistedSessionLifecycleState
}
