@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.openai.client.test

import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import kotlinx.coroutines.flow.StateFlow

/** Fixed authenticated state for tests that exercise an OpenAI client. */
public expect class InMemoryOpenAiAuthStore(initialState: OpenAiAuthState) : OpenAiAuthStore {
    public constructor(credentials: OpenAiSubscriptionAuthState)
    override val state: StateFlow<OpenAiAuthState>
}
