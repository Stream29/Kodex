package io.github.stream29.kodex.openai.accountusage

import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import kotlinx.coroutines.CoroutineScope

/** Creates a live, account-isolated Codex usage store. */
public expect fun CodexAccountUsageStore(
    client: OpenAiClient,
    authStore: OpenAiAuthStore,
): CodexAccountUsageStore

/** Backend ownership and result semantics, without changing the legacy CLI factory. */
public expect fun CoroutineScope.ownedCodexAccountUsageStore(
    client: OpenAiClient,
    authStore: OpenAiAuthStore,
): CodexAccountUsageStore
