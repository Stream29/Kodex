package io.github.stream29.kodex.openai.accountusage

import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import kotlinx.coroutines.CoroutineScope

/** Creates a live, account-isolated Codex usage store owned by this scope. */
public expect fun CoroutineScope.ownedCodexAccountUsageStore(
    client: OpenAiClient,
    authStore: OpenAiAuthStore,
): CodexAccountUsageStore
