@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Credential-free observations; private reset attempts remain in the backend. */
public expect class BackendAccountState(
    scope: CoroutineScope,
    authStore: OpenAiAuthStore,
    client: OpenAiClient,
) : AutoCloseable {
    public val authentication: StateFlow<SettingsAuthenticationState>
    public val accountUsage: StateFlow<SettingsAccountUsageState>
    public suspend fun refreshAccountUsage()
    public suspend fun consumeUsageReset(creditId: String): CodexRateLimitResetOutcome
    override fun close()
}
