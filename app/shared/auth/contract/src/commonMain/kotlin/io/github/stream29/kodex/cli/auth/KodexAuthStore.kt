package io.github.stream29.kodex.cli.auth

import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Resolves the authentication source selected by global settings and publishes
 * its request-ready credentials.
 *
 * The source selection itself belongs to global settings. Implementations own
 * only credential loading and Kodex-managed refresh.
 */
public interface KodexAuthStore : OpenAiAuthStore, AutoCloseable {
    /** Reloads the configured authentication source and publishes its credentials. */
    public suspend fun reload()

    /** Starts a browser sign-in flow that writes only Kodex-managed credentials. */
    public suspend fun startKodexLogin(): KodexAuthLoginAttempt

    /** Deletes only Kodex-managed credentials without modifying a Codex auth source. */
    public suspend fun logoutKodex()
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
    private val mutableState = MutableStateFlow<OpenAiAuthState>(
        OpenAiAuthState.Authenticated(initialAuth),
    )
    override val state: StateFlow<OpenAiAuthState> = mutableState

    override suspend fun reload(): Unit = Unit

    override suspend fun startKodexLogin(): KodexAuthLoginAttempt =
        error("In-memory authentication does not support browser sign-in.")

    override suspend fun logoutKodex() {
        mutableState.value = OpenAiAuthState.Unavailable.CredentialsNotFound
    }

    override fun close(): Unit = Unit
}
