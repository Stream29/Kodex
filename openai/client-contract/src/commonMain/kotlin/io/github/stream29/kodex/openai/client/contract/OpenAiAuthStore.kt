package io.github.stream29.kodex.openai.client.contract

import io.github.stream29.kodex.openai.OpenAiAuthState
import kotlinx.coroutines.flow.StateFlow

/** Read-only authentication state required by OpenAI API consumers. */
public interface OpenAiAuthStore {
    public val state: StateFlow<OpenAiAuthState>
}
