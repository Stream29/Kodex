package io.github.stream29.kodex.openai

/**
 * Current credentials available to OpenAI API consumers.
 *
 * [Authenticated.credentials] contains a bearer token and must not be exposed
 * through frontend-facing contracts.
 */
public sealed interface OpenAiAuthState {
    public data class Authenticated(
        public val credentials: OpenAiSubscriptionAuthState,
    ) : OpenAiAuthState

    /** Stable reason that request credentials are not currently available. */
    public enum class Unavailable : OpenAiAuthState {
        /** The owning store has not completed its first credential load. */
        NotLoaded,

        /** The selected credential source contains no credential file. */
        CredentialsNotFound,

        /** The selected credentials use a mode this client cannot consume. */
        UnsupportedAuthMode,

        /** The selected credential file is malformed or incomplete. */
        InvalidCredentials,

        /** The selected credential source could not be read. */
        CredentialSourceUnavailable,

        /** Credential loading failed outside the expected failure categories. */
        UnexpectedFailure,
    }
}
