package io.github.stream29.kodex.app.settings.contract

import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionPlan
import kotlinx.serialization.Serializable

/**
 * Authentication projection that never contains request credentials. Render a signed-in
 * identity/plan or the typed unavailable reason; this is not an OAuth completion receipt.
 * Its original package and serializer descriptors are part of the existing RPC contract.
 */
@Serializable
public sealed interface SettingsAuthenticationState {
    /**
     * Safe optional identity fields. Prefer email, then account id, then "Signed in"; append
     * the plan when known. Null means unknown, not an empty identity.
     *
     * @throws IllegalArgumentException if accountId or email is non-null but blank.
     */
    @Serializable
    public data class Authenticated(
        public val accountId: String? = null,
        public val planType: OpenAiSubscriptionPlan? = null,
        public val email: String? = null,
    ) : SettingsAuthenticationState {
        init {
            require(accountId == null || accountId.isNotBlank()) {
                "A Settings account id must be null or non-blank."
            }
            require(email == null || email.isNotBlank()) {
                "A Settings account email must be null or non-blank."
            }
        }
    }

    /** Render the typed reason and a sign-in action, never raw store/OAuth diagnostics. */
    @Serializable
    public data class Unavailable(
        public val reason: OpenAiAuthState.Unavailable,
    ) : SettingsAuthenticationState
}
