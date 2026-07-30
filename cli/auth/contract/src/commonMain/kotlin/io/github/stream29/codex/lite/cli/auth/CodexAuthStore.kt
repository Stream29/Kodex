package io.github.stream29.codex.lite.cli.auth

import io.github.stream29.codex.lite.openai.OpenAiSubscriptionAuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Current availability of the subscription credentials selected in global settings. */
public sealed interface CodexAuthState {
    /** Complete credentials ready for an OpenAI request. */
    public data class Authenticated(
        public val value: OpenAiSubscriptionAuthState,
    ) : CodexAuthState

    /** The selected source cannot currently provide credentials. */
    public data class Unavailable(
        public val message: String,
    ) : CodexAuthState
}

/**
 * Resolves the authentication source selected by global settings and publishes
 * its request-ready credentials.
 *
 * The source selection itself belongs to global settings. Implementations own
 * only credential loading and Codex Lite-managed refresh.
 */
public interface CodexAuthStore : AutoCloseable {
    /** Latest availability and credentials for an OpenAI request. */
    public val state: StateFlow<CodexAuthState>

    /** Reloads the configured authentication source and publishes its credentials. */
    public suspend fun reload()

    /** Starts a browser sign-in flow that writes only Codex Lite-managed credentials. */
    public suspend fun startCodexLiteLogin(): CodexLiteAuthLoginAttempt
}

/** One started browser sign-in flow for Codex Lite-managed subscription credentials. */
public interface CodexLiteAuthLoginAttempt {
    /** One-time browser authorization URL. It must not be persisted. */
    public val authorizationUrl: String

    /** Waits until credentials are exchanged, durably stored, and selected in global settings. */
    public suspend fun awaitCompletion()

    /** Stops the local callback listener without changing existing credentials. */
    public fun cancel()
}

/** Holds fixed authenticated credentials in memory. */
public class InMemoryCodexAuthStore(
    initialAuth: OpenAiSubscriptionAuthState,
) : CodexAuthStore {
    override val state: StateFlow<CodexAuthState>
        field = MutableStateFlow(CodexAuthState.Authenticated(initialAuth))

    override suspend fun reload(): Unit = Unit

    override suspend fun startCodexLiteLogin(): CodexLiteAuthLoginAttempt =
        error("In-memory authentication does not support browser sign-in.")

    override fun close(): Unit = Unit
}
