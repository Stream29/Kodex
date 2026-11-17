package io.github.stream29.kodex.app.settings.contract

/** One account-management command owned by Settings > OpenAI. */
public enum class SettingsAuthenticationOperation {
    /** Remove only the selected source's local credentials; no remote revoke. */
    Logout,
}

/** Frontend-safe lifecycle, independent of the shared authentication projection. */
public sealed interface SettingsAuthenticationOperationState {
    /** Render normal source, login and logout actions. */
    public data object Idle : SettingsAuthenticationOperationState
    /** Disable renderer actions and show progress; programmatic source writes remain admitted. */
    public data object SigningOut : SettingsAuthenticationOperationState

    /**
     * Render a safe acknowledgement/retry-via-confirmation prompt. An unknown failure does not
     * prove credentials were kept or removed. The host remains the shared failure authority.
     */
    public data class Failed(
        public val operation: SettingsAuthenticationOperation,
    ) : SettingsAuthenticationOperationState
}
