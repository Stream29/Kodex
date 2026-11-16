package io.github.stream29.kodex.openai

import kotlinx.serialization.Serializable

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
    @Serializable
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

/** Human-readable explanation for why request credentials are unavailable. */
public fun OpenAiAuthState.Unavailable.unavailableMessage(): String =
    "OpenAI authentication is unavailable: " +
        when (this) {
            OpenAiAuthState.Unavailable.NotLoaded -> "credentials have not been loaded."
            OpenAiAuthState.Unavailable.CredentialsNotFound -> "credentials were not found."
            OpenAiAuthState.Unavailable.UnsupportedAuthMode ->
                "the selected credentials use an unsupported authentication mode."
            OpenAiAuthState.Unavailable.InvalidCredentials ->
                "the selected credentials are malformed or incomplete."
            OpenAiAuthState.Unavailable.CredentialSourceUnavailable ->
                "the credential source could not be read."
            OpenAiAuthState.Unavailable.UnexpectedFailure ->
                "credential loading failed unexpectedly."
        }
