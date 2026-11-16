package io.github.stream29.kodex.openai.modelcatalog

import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import kotlinx.coroutines.CoroutineScope

/**
 * Creates a live catalog whose background refresh work is owned by this scope.
 *
 * Supply the client's [authStore] to refresh on authentication changes and
 * discard remote metadata belonging to a previous account. Omitting it retains
 * the fixed-client, one-time startup refresh behavior.
 *
 * @param authStore Nullable for a fixed client; `null` disables authentication
 * observation, not authentication within the client itself.
 */
public expect fun CoroutineScope.ownedOpenAiModelCatalog(
    client: OpenAiClient,
    authStore: OpenAiAuthStore? = null,
): OpenAiModelCatalogStore
