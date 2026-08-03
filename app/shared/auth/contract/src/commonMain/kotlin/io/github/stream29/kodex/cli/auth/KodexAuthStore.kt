package io.github.stream29.kodex.cli.auth

import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Current availability of the subscription credentials selected in global settings. */
public sealed interface KodexAuthState {
    /** Complete credentials ready for an OpenAI request. */
    public data class Authenticated(
        public val value: OpenAiSubscriptionAuthState,
    ) : KodexAuthState

    /** The selected source cannot currently provide credentials. */
    public data class Unavailable(
        public val message: String,
    ) : KodexAuthState
}

/**
 * Resolves the authentication source selected by global settings and publishes
 * its request-ready credentials.
 *
 * The source selection itself belongs to global settings. Implementations own
 * only credential loading and Kodex-managed refresh.
 */
public interface KodexAuthStore : AutoCloseable {
    /** Latest availability and credentials for an OpenAI request. */
    public val state: StateFlow<KodexAuthState>

    /** Reloads the configured authentication source and publishes its credentials. */
    public suspend fun reload()

    /** Starts a browser sign-in flow that writes only Kodex-managed credentials. */
    public suspend fun startKodexLogin(): KodexAuthLoginAttempt
}

/** One started browser sign-in flow for Kodex-managed subscription credentials. */
public interface KodexAuthLoginAttempt {
    /** One-time browser authorization URL. It must not be persisted. */
    public val authorizationUrl: String

    /** Waits until credentials are exchanged, durably stored, and selected in global settings. */
    public suspend fun awaitCompletion()

    /** Stops the local callback listener without changing existing credentials. */
    public fun cancel()
}

/** Holds fixed authenticated credentials in memory. */
public class InMemoryKodexAuthStore(
    initialAuth: OpenAiSubscriptionAuthState,
) : KodexAuthStore {
    override val state: StateFlow<KodexAuthState>
        field = MutableStateFlow(KodexAuthState.Authenticated(initialAuth))

    override suspend fun reload(): Unit = Unit

    override suspend fun startKodexLogin(): KodexAuthLoginAttempt =
        error("In-memory authentication does not support browser sign-in.")

    override fun close(): Unit = Unit
}
