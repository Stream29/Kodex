package io.github.stream29.kodex.app.session.contract

/**
 * UI-facing lifetime of one persisted Session handle.
 *
 * The factory publishes a handle only after opening the root Agent.
 */
public sealed interface PersistedSessionLifecycleState {
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

    public data object Closing : PersistedSessionLifecycleState
    public data object Closed : PersistedSessionLifecycleState
}

public enum class PersistedSessionNotificationLevel {
    Information,
    Warning,
    Error,
}

/** Latest operation result owned only by one persisted Session. */
public data class PersistedSessionNotification(
    public val id: Long,
    public val level: PersistedSessionNotificationLevel,
    public val message: String,
    public val detail: String? = null,
) {
    init {
        require(id > 0) { "A persisted Session notification id must be positive." }
        require(message.isNotBlank()) {
            "A persisted Session notification message must not be blank."
        }
    }
}
