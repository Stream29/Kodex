package io.github.stream29.kodex.openai.client.contract

import io.github.stream29.kodex.openai.OpenAiAuthState
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only authentication state required by OpenAI API consumers.
 *
 * The owner of this state handles loading, refresh, and persistence; clients
 * observe it but cannot initiate those operations through this interface.
 */
public interface OpenAiAuthStore {
    /** Current account or a text-free unavailable reason. */
    public val state: StateFlow<OpenAiAuthState>
}
