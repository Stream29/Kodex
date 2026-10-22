@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.openai.client.test

import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Fixed authenticated state for tests that exercise an OpenAI client. */
public actual class InMemoryOpenAiAuthStore actual constructor(
    initialState: OpenAiAuthState,
) : OpenAiAuthStore {
    public actual constructor(credentials: OpenAiSubscriptionAuthState) :
        this(OpenAiAuthState.Authenticated(credentials))

    actual override val state: StateFlow<OpenAiAuthState> = MutableStateFlow(initialState)
}
