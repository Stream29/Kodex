package io.github.stream29.kodex.app.session.contract

/**
 * UI-facing lifetime of one persisted Session handle, not backend execution.
 *
 * A handle can temporarily lose its borrowed local child while RPC observations
 * recover. Observe [PersistedSessionViewModel.rootAgent] separately; publication
 * of these two flows is not a single atomic state. Keep last-known name/settings
 * available during recovery, but do not admit commands using those cached values.
 */
public sealed interface PersistedSessionLifecycleState {
    /** Render loading/recovery without invoking commands on an absent old Agent. */
    public data object Loading : PersistedSessionLifecycleState
    /** Render the current non-null rootAgent when available. */
    public data object Open : PersistedSessionLifecycleState

    /**
     * Render this failure rather than an empty successful Session. Missing/failed
     * views do not silently create a backend Session or replay failed commands.
     * @throws IllegalArgumentException if detail is blank.
     */
    public data class Failed(
        public val detail: String,
    ) : PersistedSessionLifecycleState {
        init {
            require(detail.isNotBlank()) {
                "A persisted Session lifecycle failure detail must not be blank."
            }
        }
    }

    /** Terminal local closure; do not render/reuse an Agent or reactivate this handle. */
    public data object Closed : PersistedSessionLifecycleState
}
