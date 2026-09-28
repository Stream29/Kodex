package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageState
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageStore
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import io.github.stream29.kodex.openai.accountusage.ownedCodexAccountUsageStore
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Credential-free observations; private reset attempts remain in the backend. */
public class BackendAccountState(
    scope: CoroutineScope,
    authStore: OpenAiAuthStore,
    client: OpenAiClient,
) : AutoCloseable {
    private val owner = scope.supervisorChildScope()
    private val usage: CodexAccountUsageStore = owner.ownedCodexAccountUsageStore(client, authStore)
    private val reset = Mutex()
    public val authentication: StateFlow<SettingsAuthenticationState> =
        authStore.state.map { it.toSummary() }.stateIn(
            owner, SharingStarted.Eagerly, authStore.state.value.toSummary(),
        )
    public val accountUsage: StateFlow<SettingsAccountUsageState> =
        usage.state.map { it.toSummary() }.stateIn(owner, SharingStarted.Eagerly, usage.state.value.toSummary())

    public suspend fun refreshAccountUsage(): Unit = usage.refresh()

    public suspend fun consumeUsageReset(creditId: String): CodexRateLimitResetOutcome {
        require(creditId.isNotBlank()) { "A specific reset credit is required." }
        return reset.withLock {
            usage.consumeResetAttempt(usage.createResetAttempt(creditId))
        }
    }

    override fun close() {
        try { usage.close() } finally { owner.cancel() }
    }

    internal suspend fun closeAndJoin() {
        close()
        owner.cancelAndJoin()
    }
}

private fun OpenAiAuthState.toSummary(): SettingsAuthenticationState = when (this) {
    is OpenAiAuthState.Authenticated -> SettingsAuthenticationState.Authenticated(
        credentials.accountId?.takeIf(String::isNotBlank), credentials.planType,
        credentials.email?.takeIf(String::isNotBlank),
    )
    is OpenAiAuthState.Unavailable -> SettingsAuthenticationState.Unavailable(this)
}

private fun CodexAccountUsageState.toSummary(): SettingsAccountUsageState = when (this) {
    CodexAccountUsageState.Unavailable -> SettingsAccountUsageState.Unavailable
    is CodexAccountUsageState.Loading -> SettingsAccountUsageState.Loading(previous)
    is CodexAccountUsageState.Available -> SettingsAccountUsageState.Available(snapshot)
    is CodexAccountUsageState.Failed -> SettingsAccountUsageState.Failed(message, previous)
    is CodexAccountUsageState.Redeeming -> SettingsAccountUsageState.Redeeming(snapshot)
}
